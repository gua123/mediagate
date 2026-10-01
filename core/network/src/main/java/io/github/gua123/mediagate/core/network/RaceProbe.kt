package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 一个地址的竞速结果（**R7**）。
 *
 * @param target 被探测的地址。
 * @param result TCP 探测结果（含 DNS / TCP 两段耗时与错误分类）。
 * @param order 在输入列表里的下标——结果排序按它，保证输出顺序与输入一致（可复现）。
 */
data class RaceAttempt(
    val target: SelectableAddress,
    val result: TcpProbeResult,
    val order: Int,
)

/**
 * 一轮竞速的结论（**R7**）。
 *
 * @param winner 先成功者；全部失败为 null。
 * @param attempts 已完成的尝试（按输入顺序排列；赢家出现后未完成的地址不在其中）。
 * @param elapsedMs 本轮耗时。
 * @param fromCache 是否直接命中 60 s 缓存（没有真的重测）。
 */
data class RaceOutcome(
    val winner: SelectableAddress?,
    val attempts: List<RaceAttempt>,
    val elapsedMs: Long,
    val fromCache: Boolean = false,
) {
    /** 是否选出了可用地址。 */
    val ok: Boolean get() = winner != null

    /** 是否全部失败（调用方据此给出"每个地址分别为什么不通"的中文提示）。 */
    val allFailed: Boolean get() = winner == null
}

/**
 * 多地址并行竞速（**R7**，plan 4.5「无规则时并行竞速探测（TCP 1.5 s）」）。
 *
 * 语义：
 * - **先成功者胜**：任意一个地址 TCP 连上就立刻返回，不再等其余地址；
 * - **其余取消**：赢家产生后取消其它探测任务。注意 `Socket.connect` 是阻塞调用，
 *   取消不会打断已经进内核的连接尝试——所以这里用一个独立子作用域跑各地址探测，
 *   拿到赢家即返回，**不等待落败者退出**（它们的 socket 会按自己的超时自然结束并关闭）；
 * - **全部失败**：返回 [RaceOutcome.winner] = null 与每一条失败明细
 *   （界面要显示"哪个地址、为什么不通"）；
 * - **结果缓存 60 s**：命中缓存直接返回（[RaceOutcome.fromCache] = true）；
 *   网络变化时由调用方 [ProbeCache.invalidate] 立刻失效（plan 4.5）。
 *
 * 可注入 [TcpProbe] 与 [ProbeClock]，JVM 单测用假探测器验证"快者胜 / 慢者被取消 / 全员失败 / 缓存 60 s"。
 *
 * @param tcp TCP 探测器。
 * @param clock 单调时钟（缓存与耗时都依赖它）。
 * @param cache 结果缓存（默认 60 s）。
 * @param io 探测所在调度器。
 */
class RaceProbe(
    private val tcp: TcpProbe,
    private val clock: ProbeClock = ProbeClock.System,
    private val cache: ProbeCache<RaceOutcome> = ProbeCache(clock),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 并行探测 [targets]，返回先成功者。
     *
     * @param timeoutMs 单地址 TCP 超时（plan 4.5：1.5 s）。
     * @param cacheKey 缓存 key；null = 不读不写缓存。默认按地址集合生成。
     * @param useCache 是否使用 60 s 缓存（用户点"重新测试"时传 false 强制重测）。
     */
    suspend fun race(
        targets: List<SelectableAddress>,
        timeoutMs: Long = AddressSelector.DEFAULT_RACE_TIMEOUT_MS,
        cacheKey: String? = defaultCacheKey(targets, timeoutMs),
        useCache: Boolean = true,
    ): RaceOutcome {
        if (targets.isEmpty()) {
            return RaceOutcome(winner = null, attempts = emptyList(), elapsedMs = 0L)
        }
        if (useCache && cacheKey != null) {
            cache.get(cacheKey)?.let { return it.copy(fromCache = true) }
        }
        val outcome = withContext(io) { runRace(targets, timeoutMs) }
        if (useCache && cacheKey != null) cache.put(cacheKey, outcome)
        return outcome
    }

    /** 清空竞速缓存（网络变化时调用）。 */
    fun invalidateCache() {
        cache.invalidate()
    }

    private suspend fun runRace(targets: List<SelectableAddress>, timeoutMs: Long): RaceOutcome {
        val started = clock.nowMs()
        val channel = Channel<RaceAttempt>(Channel.UNLIMITED)
        // 独立子作用域（父 Job 仍是调用方，调用方取消会一并取消）；拿到赢家就整体 cancel
        val children = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        return try {
            targets.forEachIndexed { index, target ->
                children.launch {
                    val result = try {
                        tcp.connect(target.host, target.port, timeoutMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        TcpProbeResult(
                            host = target.host,
                            port = target.port,
                            ok = false,
                            dnsMs = 0L,
                            connectMs = 0L,
                            error = ConnectivityError.fromThrowable(t),
                            detail = t.message,
                        )
                    }
                    channel.send(RaceAttempt(target = target, result = result, order = index))
                }
            }
            var winner: RaceAttempt? = null
            val attempts = ArrayList<RaceAttempt>(targets.size)
            for (index in targets.indices) {
                val attempt = channel.receive()
                attempts += attempt
                if (attempt.result.ok) {
                    winner = attempt
                    break
                }
            }
            val elapsed = (clock.nowMs() - started).coerceAtLeast(0L)
            RaceOutcome(
                winner = winner?.target,
                attempts = attempts.sortedBy { it.order },
                elapsedMs = elapsed,
            )
        } finally {
            children.cancel()
        }
    }

    companion object {
        /** 默认缓存 key：地址集合 + 超时（连接内地址变了就是另一轮）。 */
        fun defaultCacheKey(targets: List<SelectableAddress>, timeoutMs: Long): String =
            targets.joinToString("|") { it.key } + "#" + timeoutMs
    }
}
