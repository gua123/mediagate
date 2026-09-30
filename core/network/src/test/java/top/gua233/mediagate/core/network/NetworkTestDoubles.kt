package io.github.gua123.mediagate.core.network

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 可控的假时钟（**R7/R8**：60 s 缓存与三段计时都要能在单测里精确控制）。
 *
 * 单调毫秒，测试里用 [advance] 手动推进——"拨 61 s"就能验证缓存过期。
 */
class FakeClock(private var now: Long = 0L) : ProbeClock {

    override fun nowMs(): Long = now

    /** 往前拨 [ms] 毫秒。 */
    fun advance(ms: Long) {
        now += ms
    }

    /** 直接设置当前时刻。 */
    fun set(ms: Long) {
        now = ms
    }
}

/** 记录调用次数的假解析器；按 host 配置"解析出的 IP"或"抛异常"。 */
class FakeResolver(
    private val table: Map<String, List<String>> = emptyMap(),
    private val failures: Set<String> = emptySet(),
) : HostResolver {

    val calls = AtomicInteger(0)

    override fun resolve(host: String): List<InetAddress> {
        calls.incrementAndGet()
        if (host in failures) throw UnknownHostException(host + ": 名称解析失败")
        val ips = table[host] ?: listOf(host)
        return ips.map { InetAddress.getByName(it) }
    }
}

/**
 * 假的 TCP 连接器（**R8**：模拟成功 / 被拒 / 超时 / 延迟，不需要真网络）。
 *
 * 每个 host 的行为：`succeedAfterMs` 后成功，或抛 `java.net.ConnectException`（拒绝）/ 超时；
 * `delayMs > 0` 时真的 `Thread.sleep`（用于竞速的先后顺序测试）。
 */
class FakeConnector(
    private val behaviours: Map<String, Behaviour> = emptyMap(),
    private val fallback: Behaviour = Behaviour.ok(),
) : SocketConnector {

    /** 一次连接尝试的行为。 */
    data class Behaviour(
        val ok: Boolean = true,
        val delayMs: Long = 0L,
        val failure: String = "refused",
        val timeout: Boolean = false,
    ) {
        companion object {
            fun ok(delayMs: Long = 0L) = Behaviour(ok = true, delayMs = delayMs)
            fun refused(delayMs: Long = 0L) = Behaviour(ok = false, delayMs = delayMs, failure = "refused")
            fun timeout(delayMs: Long = 0L) = Behaviour(ok = false, delayMs = delayMs, timeout = true)
            fun unreachable(delayMs: Long = 0L) = Behaviour(ok = false, delayMs = delayMs, failure = "unreachable")
        }
    }

    val calls = AtomicInteger(0)
    val callOrder: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val perHost = ConcurrentHashMap<String, AtomicInteger>()

    override fun connect(address: InetSocketAddress, timeoutMs: Int) {
        calls.incrementAndGet()
        val host = address.hostString
        callOrder.add(host)
        val index = perHost.computeIfAbsent(host) { AtomicInteger(0) }.getAndIncrement()
        val configured = behaviours[host]
        val behaviour = when (configured) {
            is Behaviour -> configured
            else -> fallback
        }
        if (behaviour.delayMs > 0L) Thread.sleep(behaviour.delayMs)
        if (!behaviour.ok) {
            if (behaviour.timeout) throw SocketTimeoutException("connect timed out")
            throw ConnectException(
                when (behaviour.failure) {
                    "unreachable" -> "Network is unreachable"
                    else -> "Connection refused"
                },
            )
        }
        // 成功：连上就关（与真实连接器语义一致）
        @Suppress("UNUSED_EXPRESSION")
        index
    }
}

/** 固定返回某个结果的假握手探测器（**R8** 第 3 段）。 */
class FakeHandshake(
    private val outcome: HandshakeOutcome = HandshakeOutcome(ok = true, elapsedMs = 30L),
    private val onCall: (() -> Unit)? = null,
) : ProtocolHandshake {

    val calls = AtomicInteger(0)

    override suspend fun handshake(address: SelectableAddress): HandshakeOutcome {
        calls.incrementAndGet()
        onCall?.invoke()
        return outcome
    }
}

/** 构造一个地址（单测里反复用）。 */
fun address(
    id: Long,
    label: AddressLabel = AddressLabel.LAN,
    host: String = "host" + id,
    port: Int = 8080,
    priority: Int = 0,
    scheme: String = "http",
): SelectableAddress = SelectableAddress(id = id, label = label, scheme = scheme, host = host, port = port, priority = priority)
