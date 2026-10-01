package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 可注入的单调时钟（**R7/R8**：计时与 60 s 缓存都要能在单测里控制）。
 *
 * 只要求"单调递增的毫秒"，不要求是墙上时间——这样系统时间被改动也不会影响缓存判定。
 */
fun interface ProbeClock {

    /** 当前毫秒数（单调）。 */
    fun nowMs(): Long

    companion object {
        /** 默认时钟：单调纳秒换算成毫秒。 */
        val System: ProbeClock = ProbeClock { java.lang.System.nanoTime() / 1_000_000L }
    }
}

/** 主机名解析（**R8**：DNS 解析是第 1 段计时；可注入以便单测模拟 DNS 失败/耗时）。 */
fun interface HostResolver {

    /** @throws java.net.UnknownHostException 解析失败。 */
    fun resolve(host: String): List<InetAddress>

    companion object {
        /** 默认解析器：走系统 DNS（`InetAddress.getAllByName`）。 */
        val Dns: HostResolver = HostResolver { InetAddress.getAllByName(it).toList() }
    }
}

/** TCP 连接动作（**R8**：TCP 握手是第 2 段计时；可注入以便单测模拟超时/被拒）。 */
fun interface SocketConnector {

    /**
     * 连接 [address]，超时 [timeoutMs] 毫秒；成功即返回（连接随即关闭）。
     *
     * @throws java.io.IOException 连接失败（超时 / 被拒 / 不可达）。
     */
    fun connect(address: InetSocketAddress, timeoutMs: Int)

    companion object {
        /** 默认连接器：普通 Socket，连上就关（只测连通性，不发任何字节）。 */
        val Plain: SocketConnector = SocketConnector { address, timeoutMs ->
            Socket().use { it.connect(address, timeoutMs) }
        }
    }
}

/**
 * 一次 TCP 探测的结果（**R8**，plan 4.5 三段计时的前两段）。
 *
 * @param host 被探测的主机名（原样回填，便于界面显示）。
 * @param port 端口。
 * @param ok 是否连通。
 * @param dnsMs DNS 解析耗时。
 * @param connectMs TCP 握手耗时（多个解析结果依次尝试时是累计值）。
 * @param error 失败分类；成功为 null。
 * @param detail 原始异常摘要（诊断用，**不含凭据**）。
 * @param resolved 解析出的 IP 列表（诊断用）。
 */
data class TcpProbeResult(
    val host: String,
    val port: Int,
    val ok: Boolean,
    val dnsMs: Long,
    val connectMs: Long,
    val error: ConnectivityError? = null,
    val detail: String? = null,
    val resolved: List<String> = emptyList(),
) {
    /** 前两段合计。 */
    val totalMs: Long get() = dnsMs + connectMs

    /** 失败发生在哪一段（成功为 null）。 */
    val failedStage: TestStage?
        get() = when {
            ok -> null
            error == ConnectivityError.DNS_FAILED || error == ConnectivityError.PORT_INVALID -> TestStage.DNS
            else -> TestStage.TCP
        }
}

/**
 * TCP 连通性探测（**R7** 竞速 / **R8** 三段计时的前两段）——DNS 与 TCP 分开计时。
 *
 * 为什么把 DNS 单独拎出来：`Socket.connect(host, port)` 内部会先解析域名再连接，
 * 拿不到"DNS 用掉多少"，而 plan 4.5 明确要求三段耗时。这里的做法是
 * **先自己解析**（[HostResolver]，单独计时），再对解析出的每个 IP 用 [SocketConnector] 连接，
 * 这样「域名解析失败」和「解析成功但连不上」是两条不同的错误分类。
 *
 * 超时口径：整个方法共享一个 `timeoutMs` 预算（默认 1.5 s，对应 plan 4.5 的 TCP 1.5 s），
 * 多 IP 时按剩余预算依次尝试，不会因为一台主机解析出多个地址而把总耗时翻倍。
 *
 * 可注入 [clock] / [resolver] / [connector]，因此**完全不依赖网络即可 JVM 单测**。
 */
class TcpProbe(
    private val clock: ProbeClock = ProbeClock.System,
    private val resolver: HostResolver = HostResolver.Dns,
    private val connector: SocketConnector = SocketConnector.Plain,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 探测 [host]:[port] 是否可建立 TCP 连接。
     *
     * **不抛异常**（取消除外），失败信息在结果里——调用方要在"测试全部"里并行跑几十个地址。
     */
    suspend fun connect(host: String, port: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): TcpProbeResult =
        withContext(io) {
            val target = host.trim()
            if (target.isEmpty()) {
                return@withContext TcpProbeResult(
                    host = target,
                    port = port,
                    ok = false,
                    dnsMs = 0L,
                    connectMs = 0L,
                    error = ConnectivityError.DNS_FAILED,
                    detail = "地址为空",
                )
            }
            if (port !in MIN_PORT..MAX_PORT) {
                return@withContext TcpProbeResult(
                    host = target,
                    port = port,
                    ok = false,
                    dnsMs = 0L,
                    connectMs = 0L,
                    error = ConnectivityError.PORT_INVALID,
                    detail = "端口必须在 " + MIN_PORT + "-" + MAX_PORT + "：" + port,
                )
            }
            val started = clock.nowMs()
            val resolved = try {
                resolver.resolve(target)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val dnsMs = (clock.nowMs() - started).coerceAtLeast(0L)
                return@withContext TcpProbeResult(
                    host = target,
                    port = port,
                    ok = false,
                    dnsMs = dnsMs,
                    connectMs = 0L,
                    error = ConnectivityError.DNS_FAILED,
                    detail = describe(t),
                )
            }
            val dnsMs = (clock.nowMs() - started).coerceAtLeast(0L)
            if (resolved.isEmpty()) {
                return@withContext TcpProbeResult(
                    host = target,
                    port = port,
                    ok = false,
                    dnsMs = dnsMs,
                    connectMs = 0L,
                    error = ConnectivityError.DNS_FAILED,
                    detail = "域名没有解析出任何地址",
                )
            }
            val deadline = started + timeoutMs
            var lastError: ConnectivityError? = ConnectivityError.UNKNOWN
            var lastDetail: String? = null
            for (address in resolved) {
                val remaining = (deadline - clock.nowMs()).coerceAtLeast(MIN_ATTEMPT_MS)
                try {
                    connector.connect(InetSocketAddress(address, port), remaining.toInt())
                    val connectMs = (clock.nowMs() - started - dnsMs).coerceAtLeast(0L)
                    return@withContext TcpProbeResult(
                        host = target,
                        port = port,
                        ok = true,
                        dnsMs = dnsMs,
                        connectMs = connectMs,
                        resolved = resolved.mapNotNull { it.hostAddress },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastError = ConnectivityError.fromThrowable(t)
                    lastDetail = describe(t)
                }
            }
            val connectMs = (clock.nowMs() - started - dnsMs).coerceAtLeast(0L)
            TcpProbeResult(
                host = target,
                port = port,
                ok = false,
                dnsMs = dnsMs,
                connectMs = connectMs,
                error = lastError ?: ConnectivityError.UNKNOWN,
                detail = lastDetail,
                resolved = resolved.mapNotNull { it.hostAddress },
            )
        }

    /** 异常摘要：类型 + 消息（消息里不含凭据——Socket 层拿不到账号密码）。 */
    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.takeIf { it.isNotBlank() }?.let { ": " + it } ?: "")

    companion object {
        /** plan 4.5：无规则时并行竞速的 TCP 超时 1.5 s。 */
        const val DEFAULT_TIMEOUT_MS: Long = 1_500L

        /** 每次尝试至少给 1 ms（避免预算耗尽后传 0 变成无限等待）。 */
        private const val MIN_ATTEMPT_MS = 1L

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
    }
}
