package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressTestResult
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.NetworkContext
import io.github.gua123.mediagate.core.network.NetworkRule
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.core.network.TestStage

/**
 * 单个地址的测试结果（**R8**：每地址三段耗时 + 错误分类）——界面模型。
 *
 * @param display 地址展示串（如 `局域网 · http://192.168.1.10:8080`）。
 * @param errorText 中文错误分类（null = 没有错误）。
 * @param handshakeSkipped 协议握手是否被跳过（SFTP/FTP 本轮没有后端）。
 * @param cached 该结果是否来自 60 s 缓存（plan 4.5）。
 */
data class AddressTestUi(
    val addressId: Long,
    val display: String,
    val ok: Boolean,
    val dnsMs: Long,
    val connectMs: Long,
    val handshakeMs: Long,
    val handshakeSkipped: Boolean,
    val failedStage: TestStage?,
    val errorCode: String?,
    val errorText: String?,
    val message: String?,
    val notice: String?,
    val cached: Boolean = false,
) {

    /** 三段合计。 */
    val totalMs: Long get() = dnsMs + connectMs + handshakeMs

    /** 一行中文耗时：「DNS 2 ms · TCP 15 ms · 握手 41 ms」。 */
    val timingLine: String
        get() = "DNS " + dnsMs + " ms · TCP " + connectMs + " ms · " +
            (if (handshakeSkipped) "握手未测" else "握手 " + handshakeMs + " ms")

    /** 状态文案（R8 的"错误分类"要能直接读出来）。 */
    val statusText: String
        get() = when {
            ok && handshakeSkipped -> "TCP 可达（未做协议握手）"
            ok -> "正常"
            else -> errorText ?: "失败"
        }
}

/**
 * 一次连接测试的汇总（**R8**）。
 *
 * @param testedAtMs 本次测试的时刻（墙上时间，界面显示"最近检测"）。
 * @param fromCache 整份结果是否直接来自 60 s 缓存。
 */
data class ConnectionTestUi(
    val connectionId: Long,
    val testedAtMs: Long,
    val addresses: List<AddressTestUi>,
    val fromCache: Boolean = false,
) {

    /** 通过的地址数。 */
    val okCount: Int get() = addresses.count { it.ok }

    /** 失败的地址数。 */
    val failCount: Int get() = addresses.count { !it.ok }

    /** 整个连接是否可用（至少一个地址通过）。 */
    val ok: Boolean get() = okCount > 0

    /** 部分可用（有的通有的不通）。 */
    val partial: Boolean get() = okCount in 1 until addresses.size

    /** 中文一行：「2 个地址：1 通 1 失败」。 */
    val summaryLine: String
        get() = addresses.size.toString() + " 个地址：" + okCount + " 通 " + failCount + " 失败"
}

/**
 * 「测试全部」的聚合结果（**R8**：多连接并行测试后的汇总与排序）——**纯函数**，单测覆盖。
 *
 * 排序口径（便于排查）：
 * 1. **失败的连接排前面**（用户点"测试全部"就是为了找哪个不通）；
 * 2. 同组内按连接 id 升序（与列表顺序一致，结果可复现）；
 * 3. 连接内部**失败的地址排前面**，同状态按地址 id 升序。
 */
object ConnectionTestAggregator {

    /** 汇总入口。 */
    fun summarize(results: List<ConnectionTestUi>): TestAllSummary {
        val ordered = sort(results)
        return TestAllSummary(
            totalConnections = ordered.size,
            okConnections = ordered.count { it.ok },
            failedConnections = ordered.count { !it.ok },
            totalAddresses = ordered.sumOf { it.addresses.size },
            okAddresses = ordered.sumOf { it.okCount },
            failedAddresses = ordered.sumOf { it.failCount },
            ordered = ordered,
        )
    }

    /** 排序（失败优先、其次 id 升序；连接内失败地址优先）。 */
    fun sort(results: List<ConnectionTestUi>): List<ConnectionTestUi> = results
        .sortedWith(compareBy<ConnectionTestUi> { if (it.ok) 1 else 0 }.thenBy { it.connectionId })
        .map { it.copy(addresses = sortAddresses(it.addresses)) }

    /** 连接内的地址排序：失败优先，其次按地址 id。 */
    fun sortAddresses(addresses: List<AddressTestUi>): List<AddressTestUi> =
        addresses.sortedWith(compareBy<AddressTestUi> { if (it.ok) 1 else 0 }.thenBy { it.addressId })
}

/**
 * 「测试全部」的汇总数据（**R8**）。
 */
data class TestAllSummary(
    val totalConnections: Int,
    val okConnections: Int,
    val failedConnections: Int,
    val totalAddresses: Int,
    val okAddresses: Int,
    val failedAddresses: Int,
    val ordered: List<ConnectionTestUi>,
) {

    /** 全部连接都通。 */
    val allOk: Boolean get() = failedConnections == 0

    /** 中文汇总：「测试 3 个连接 / 5 个地址：2 通 1 失败」。 */
    val summaryLine: String
        get() = "测试 " + totalConnections + " 个连接 · " + totalAddresses + " 个地址：" +
            okConnections + " 通 " + failedConnections + " 失败"

    companion object {
        /** 空结果（还没测过）。 */
        val Empty = TestAllSummary(0, 0, 0, 0, 0, 0, emptyList())
    }
}

/** 核心层的单地址结果 → 界面模型（**R8**）。 */
internal fun AddressTestResult.toUi(): AddressTestUi = AddressTestUi(
    addressId = address.id,
    display = address.displayWithLabel(),
    ok = ok,
    dnsMs = dnsMs,
    connectMs = connectMs,
    handshakeMs = handshakeMs,
    handshakeSkipped = handshakeSkipped,
    failedStage = failedStage,
    errorCode = error?.code,
    errorText = error?.let { it.zhText + "（" + it.hint + "）" },
    message = message,
    notice = notice,
)

/** `局域网 · http://192.168.1.10:8080`。 */
internal fun SelectableAddress.displayWithLabel(): String {
    val label = AddressLabel.entries.firstOrNull { it == this.label } ?: AddressLabel.LAN
    return label.zhText + " · " + display
}

/** 失败分类 → 中文（没错误时返回 null）。 */
internal fun ConnectivityError?.displayOrNull(): String? = this?.let { it.zhText + " · " + it.hint }

/**
 * 网络切换判定键（**R7**：plan 4.5「`NetworkCallback` 变化即失效并重测」）。
 *
 * 只认"影响选路的三件事"——传输类型 / SSID / 本机网段 / 在线与否；
 * 单纯的 revision 自增（同一网络的能力回调）不算切换，避免把用户刚看到的测试结果清掉。
 */
internal val NetworkContext.switchKey: String
    get() = capability.id + "|" + ssid.orEmpty() + "|" + localSubnets.sorted().joinToString(",") + "|" + online
