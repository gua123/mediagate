package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 协议种类（**R2/R8**）——取值与 `:core:database` 的 `Protocols` 常量一一对应
 * （数据库里存的是字符串，这里给一个强类型视图，避免各页面各自写字符串）。
 *
 * 四种协议（本地 / WebDAV / SFTP / FTP）都有真实后端；这里只负责"协议种类"这一层强类型视图，
 * 谁有握手实现由调用方注入（见 [ConnectionTester]）。
 */
enum class ProtocolKind(val id: String, val zhText: String) {
    LOCAL("LOCAL", "本地目录"),
    WEBDAV("WEBDAV", "WebDAV"),
    SFTP("SFTP", "SFTP"),
    FTP("FTP", "FTP"),
    ;

    companion object {
        /** 字符串 → 枚举；认不出返回 null（调用方决定怎么提示）。 */
        fun fromId(id: String?): ProtocolKind? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * 协议握手探测（**R8**，plan 4.5 第 3 段：DNS → TCP → 协议握手）。
 *
 * 第 3 段是"真正的决定权"：TCP 通了不代表协议对（可能连到了别的服务）。
 * 各协议的握手指令不同（WebDAV = PROPFIND 期望 207/200；SFTP = banner + 认证；
 * FTP = 220 + 登录 + PASV），由调用方注入；:feature:connections 四种协议都注入（LOCAL 查目录）。
 */
fun interface ProtocolHandshake {

    /**
     * 对 [address] 做一次协议握手。
     *
     * **不应抛异常**（取消除外）：失败信息放在 [HandshakeOutcome] 里，方便"测试全部"跑完所有地址。
     */
    suspend fun handshake(address: SelectableAddress): HandshakeOutcome
}

/**
 * 协议握手的结果（**R8** 第 3 段）。
 *
 * @param ok 握手是否成功。
 * @param elapsedMs 本段耗时（毫秒）。
 * @param error 失败分类；成功为 null。
 * @param message 中文提示（如「401 账号或密码错误」）。
 */
data class HandshakeOutcome(
    val ok: Boolean,
    val elapsedMs: Long = 0L,
    val error: ConnectivityError? = null,
    val message: String? = null,
)

/**
 * 单个地址的三段测试结果（**R8**：每地址三段耗时 + 错误分类）。
 *
 * @param address 被测试的地址。
 * @param ok 该地址是否可用（三段的结论：[handshakeSkipped] = true 时表示"只验证到 TCP"）。
 * @param dnsMs / [connectMs] / [handshakeMs] 三段耗时（本地协议前两段恒为 0）。
 * @param error 失败分类；成功为 null。
 * @param failedStage 失败发生在哪一段（成功为 null）。
 * @param message 中文失败原因（可展示）。
 * @param notice 需要如实告知的补充说明（如「该协议没有握手实现，本次只验证 DNS/TCP 两段」）。
 * @param handshakeSkipped 协议握手是否被跳过（该协议本轮没有实现）。
 */
data class AddressTestResult(
    val address: SelectableAddress,
    val ok: Boolean,
    val dnsMs: Long = 0L,
    val connectMs: Long = 0L,
    val handshakeMs: Long = 0L,
    val error: ConnectivityError? = null,
    val failedStage: TestStage? = null,
    val message: String? = null,
    val notice: String? = null,
    val handshakeSkipped: Boolean = false,
) {
    /** 三段合计。 */
    val totalMs: Long get() = dnsMs + connectMs + handshakeMs

    /** 中文一行：「DNS 2 ms · TCP 15 ms · 握手 41 ms」。 */
    val timingLine: String
        get() = "DNS " + dnsMs + " ms · TCP " + connectMs + " ms · " +
            (if (handshakeSkipped) "握手未测" else "握手 " + handshakeMs + " ms")
}

/**
 * 连通性测试编排（**R8**，plan 4.5「连通性测试三段计时」）。
 *
 * 流程（每个地址独立跑一遍）：
 * 1. **DNS**：`TcpProbe` 内部先解析域名并单独计时；
 * 2. **TCP**：解析成功后建立连接（1.5 s 预算）；
 * 3. **协议握手**：只有前两段通过才做；失败即整体失败，并带上 HTTP/协议层的错误分类；
 * 4. 三段耗时与分类写进 [AddressTestResult]，绝不抛异常（取消除外）。
 *
 * 特殊情形：
 * - [ProtocolKind.LOCAL]：没有 DNS/TCP 可言（前两段恒为 0），只有"路径是否存在/可读"这一段；
 * - 调用方没注入握手实现的协议：只测到 TCP 两段，结果里带
 *   [AddressTestResult.handshakeSkipped] = true 与中文 [AddressTestResult.notice]，
 *   界面据此显示「仅 TCP 通（未做协议握手）」而不是谎报"正常"
 *   （四种协议的握手都由 :feature:connections 注入，正常流程走不到这个分支）。
 *
 * @param tcp TCP 探测器。
 * @param handshakes 已实现的协议握手；未给的协议走"跳过握手"分支。
 * @param io 调度器。
 */
class ConnectionTester(
    private val tcp: TcpProbe = TcpProbe(),
    private val handshakes: Map<ProtocolKind, ProtocolHandshake> = emptyMap(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 该协议是否已实现协议握手（界面用来提前提示"只能测 TCP"）。 */
    fun supportsHandshake(protocol: ProtocolKind): Boolean = handshakes.containsKey(protocol)

    /**
     * 测一个地址。
     *
     * @param timeoutMs 前两段的 TCP 超时（plan 4.5：1.5 s）。
     */
    suspend fun test(
        protocol: ProtocolKind,
        address: SelectableAddress,
        timeoutMs: Long = TcpProbe.DEFAULT_TIMEOUT_MS,
    ): AddressTestResult = withContext(io) {
        if (protocol == ProtocolKind.LOCAL) {
            return@withContext handshakeOnly(protocol, address, dnsMs = 0L, connectMs = 0L)
        }
        val tcpResult = tcp.connect(address.host, address.port, timeoutMs)
        if (!tcpResult.ok) {
            return@withContext AddressTestResult(
                address = address,
                ok = false,
                dnsMs = tcpResult.dnsMs,
                connectMs = tcpResult.connectMs,
                error = tcpResult.error ?: ConnectivityError.UNKNOWN,
                failedStage = tcpResult.failedStage ?: TestStage.TCP,
                message = (tcpResult.error ?: ConnectivityError.UNKNOWN).zhText +
                    (tcpResult.detail?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: ""),
            )
        }
        handshakeOnly(protocol, address, dnsMs = tcpResult.dnsMs, connectMs = tcpResult.connectMs)
    }

    /**
     * 并行测试多个地址（**R8「测试全部」**）。
     *
     * 并行是为了让"测 10 个连接 × 2 个地址"在秒级完成；各地址互不影响，
     * 单地址失败不影响其它地址（每个 [test] 都不抛异常）。
     */
    suspend fun testAll(
        protocol: ProtocolKind,
        addresses: List<SelectableAddress>,
        timeoutMs: Long = TcpProbe.DEFAULT_TIMEOUT_MS,
    ): List<AddressTestResult> {
        if (addresses.isEmpty()) return emptyList()
        return coroutineScope {
            addresses.map { address ->
                async(io) { test(protocol, address, timeoutMs) }
            }.awaitAll()
        }
    }

    /** 第 3 段（含"未实现则跳过"的分支）；前两段的结果由调用方传入。 */
    private suspend fun handshakeOnly(
        protocol: ProtocolKind,
        address: SelectableAddress,
        dnsMs: Long,
        connectMs: Long,
    ): AddressTestResult {
        val probe = handshakes[protocol]
        if (probe == null) {
            val notice = protocol.zhText + " 暂无协议握手实现：本次只验证到 " +
                if (protocol == ProtocolKind.LOCAL) "本地路径检查" else "DNS/TCP 两段，未做协议握手"
            return AddressTestResult(
                address = address,
                ok = protocol != ProtocolKind.LOCAL,
                dnsMs = dnsMs,
                connectMs = connectMs,
                handshakeMs = 0L,
                error = if (protocol == ProtocolKind.LOCAL) ConnectivityError.NOT_IMPLEMENTED else null,
                failedStage = if (protocol == ProtocolKind.LOCAL) TestStage.HANDSHAKE else null,
                message = if (protocol == ProtocolKind.LOCAL) notice else null,
                notice = notice,
                handshakeSkipped = true,
            )
        }
        val outcome = try {
            probe.handshake(address)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            HandshakeOutcome(
                ok = false,
                elapsedMs = 0L,
                error = ConnectivityError.fromThrowable(t),
                message = t.javaClass.simpleName + (t.message?.let { ": " + it } ?: ""),
            )
        }
        return AddressTestResult(
            address = address,
            ok = outcome.ok,
            dnsMs = dnsMs,
            connectMs = connectMs,
            handshakeMs = outcome.elapsedMs,
            error = outcome.error,
            failedStage = if (outcome.ok) null else TestStage.HANDSHAKE,
            message = outcome.message,
        )
    }
}
