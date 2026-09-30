package io.github.gua123.mediagate.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.gua123.mediagate.core.common.AppLog

/**
 * 网络现场监听（**R7**：plan 4.5「`NetworkCallback` 变化即失效并重测」）。
 *
 * 采集三样东西（[NetworkContext]）：
 * - **传输类型**：Wi-Fi / 蜂窝 / 以太网 / VPN（[NetworkCapabilities] 的 TRANSPORT_*）；
 * - **SSID**：仅 Wi-Fi 且**有定位权限**时才拿得到；拿不到就是 null（R6 不为它额外申请权限，
 *   此时 SSID 规则自然不命中，规则会退化为"按网段 / 传输类型"）；
 * - **本机网段**：`LinkProperties.linkAddresses` → `192.168.1.10/24` 这样的 CIDR 串。
 *
 * 每次回调都把 [NetworkContext.revision] +1，界面据此让 60 s 缓存立刻失效。
 *
 * **只能在真机上验证**（ConnectivityManager 回调、Wi-Fi 切换、蜂窝切换）：JVM 侧只保证编译通过。
 *
 * @param context 应用上下文。
 */
class AndroidNetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val _context = MutableStateFlow(NetworkContext(online = false, revision = 0L))

    /** 当前网络现场（可在 Compose / ViewModel 里直接 collect）。 */
    val context: StateFlow<NetworkContext> = _context.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 开始监听（重复调用无副作用）。 */
    fun start() {
        if (callback != null) return
        val manager = connectivity ?: run {
            AppLog.w(TAG, "没有 ConnectivityManager，网络现场保持「未知」")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh(manager, network)

            override fun onLost(network: Network) = refresh(manager, null)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh(manager, network)

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = refresh(manager, network)
        }
        callback = networkCallback
        runCatching { manager.registerNetworkCallback(request, networkCallback) }
            .onFailure { AppLog.w(TAG, "注册网络回调失败", it) }
        refresh(manager, manager.activeNetwork)
    }

    /** 停止监听（幂等）。 */
    fun stop() {
        val manager = connectivity ?: return
        val current = callback ?: return
        callback = null
        runCatching { manager.unregisterNetworkCallback(current) }
    }

    /** 主动刷新一次（例如从设置页返回时）。 */
    fun refreshNow() {
        val manager = connectivity ?: return
        refresh(manager, manager.activeNetwork)
    }

    private fun refresh(manager: ConnectivityManager, network: Network?) {
        val previous = _context.value
        if (network == null) {
            _context.value = previous.copy(
                capability = NetworkCapability.UNKNOWN,
                ssid = null,
                localSubnets = emptySet(),
                online = false,
                revision = previous.revision + 1,
            )
            return
        }
        val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
        val props = runCatching { manager.getLinkProperties(network) }.getOrNull()
        val capability = capabilityOf(caps)
        val online = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _context.value = NetworkContext(
            capability = capability,
            ssid = if (capability == NetworkCapability.WIFI) currentSsid() else null,
            localSubnets = subnetsOf(props),
            online = online,
            revision = previous.revision + 1,
        )
    }

    /** 传输类型：以太网 > Wi-Fi > 蜂窝 > VPN（同时在线的组合里取"真实承载"）。 */
    private fun capabilityOf(caps: NetworkCapabilities?): NetworkCapability {
        if (caps == null) return NetworkCapability.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapability.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapability.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapability.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkCapability.VPN
            else -> NetworkCapability.UNKNOWN
        }
    }

    /** 本机网段（CIDR）；IPv6 会去掉 `%wlan0` 之类的 scope 后缀。 */
    private fun subnetsOf(props: LinkProperties?): Set<String> {
        val links = props?.linkAddresses ?: return emptySet()
        return links.mapNotNull { link ->
            val host = link.address?.hostAddress ?: return@mapNotNull null
            val clean = host.substringBefore('%')
            clean + "/" + link.prefixLength
        }.toSet()
    }

    /**
     * 当前 Wi-Fi SSID。
     *
     * Android 13+ 读 SSID 需要定位权限；没有权限时系统返回 `<unknown ssid>`（或抛 SecurityException），
     * 两种情况都归成 null——**不为了一个展示字段去要定位权限**（R6）。
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return null
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        return NetworkContext.normalizeSsid(runCatching { info.ssid }.getOrNull())
    }

    private companion object {
        const val TAG = "core-network"
    }
}
