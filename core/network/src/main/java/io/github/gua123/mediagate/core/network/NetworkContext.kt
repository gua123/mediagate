package io.github.gua123.mediagate.core.network

/**
 * 当前网络能力（**R7**：按网络自动选地址；plan 4.5 判定顺序的第一环"网络能力"）。
 *
 * [UNKNOWN] 不是"没有网络"，而是"拿不到传输类型"（例如仅 VPN、或系统没给权限）；
 * 是否在线看 [NetworkContext.online]。
 */
enum class NetworkCapability(val id: String, val zhText: String) {
    WIFI("WIFI", "Wi-Fi"),
    CELLULAR("CELLULAR", "蜂窝网络"),
    ETHERNET("ETHERNET", "以太网"),
    VPN("VPN", "VPN"),
    UNKNOWN("UNKNOWN", "未知网络"),
    ;

    companion object {
        /** 数据库 / UI 里存的字符串 → 枚举；认不出返回 [UNKNOWN]（绝不抛）。 */
        fun fromId(id: String?): NetworkCapability =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * 一次选路/探测所需的网络现场（**R7**，plan 4.5）。
 *
 * 由 :app 用 `ConnectivityManager` + `WifiManager` 采集后喂给纯逻辑 [AddressSelector]；
 * 单测直接构造，不依赖 Android。
 *
 * @param capability 传输类型（Wi-Fi / 蜂窝 / 以太网 / VPN / 未知）。
 * @param ssid 当前 Wi-Fi 名称；**没有定位权限时 Android 会返回 `<unknown ssid>`，
 *   这里统一归一成 null**（R6 不额外要权限，规则会退化为按网段/传输类型匹配）。
 * @param localSubnets 本机网段（CIDR 或 IP/前缀长度，如 `192.168.1.0/24`、`192.168.1.10/24`）。
 * @param online 是否有可用网络（`NetworkCapabilities.NET_CAPABILITY_INTERNET` + 已验证）。
 * @param revision 网络变化计数：每次 `NetworkCallback` 回调 +1，用于让缓存（60 s）立即失效。
 */
data class NetworkContext(
    val capability: NetworkCapability = NetworkCapability.UNKNOWN,
    val ssid: String? = null,
    val localSubnets: Set<String> = emptySet(),
    val online: Boolean = true,
    val revision: Long = 0L,
) {

    /** 界面展示用一行中文：「Wi-Fi · Home · 192.168.1.10/24」。 */
    val display: String
        get() = buildString {
            append(capability.zhText)
            ssid?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            localSubnets.firstOrNull()?.let { append(" · ").append(it) }
        }

    /** 没有网络时选路直接判 OFFLINE，不再浪费时间探测。 */
    val usable: Boolean get() = online

    companion object {
        /** 没网（离线）时的现场。 */
        val Offline = NetworkContext(online = false, capability = NetworkCapability.UNKNOWN)

        /** Android 在拿不到 SSID 时的占位串，统一归一成 null。 */
        const val UNKNOWN_SSID: String = "<unknown ssid>"

        /** 把系统给的 SSID 归一化：去引号、去空白、去掉 `<unknown ssid>`。 */
        fun normalizeSsid(raw: String?): String? {
            val trimmed = raw?.trim()?.trim('"')?.trim() ?: return null
            if (trimmed.isEmpty() || trimmed.equals(UNKNOWN_SSID, ignoreCase = true)) return null
            return trimmed
        }
    }
}
