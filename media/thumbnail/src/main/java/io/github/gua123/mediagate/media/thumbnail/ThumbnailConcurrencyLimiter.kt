package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong

/**
 * 缩略图并发闸门：**并发上限 + 有界排队 + 明确的拒绝策略（丢了哪一条是说得清的）**。
 *
 * ### 为什么不是单纯的 Semaphore
 *
 * 旧实现是 `Semaphore(3)`：它只限"同时抽几张"，**不限排队人数**。真机进
 * `/home/nas/movie`（数百个视频）时，每一行列表项都会来要缩略图，全部挂在许可上排队——
 * 排队本身不花线程，但每个排队者下面都连着一条远端流、一次 openRead、一串 timer，
 * 而且**先排队的早就滚出屏幕了**，等轮到它时用户根本看不见。
 *
 * ### 口径
 *
 * - 同时抽帧：[_parallelism] 个（普通后端 3、SFTP/FTP 2，沿用 plan 4.4）；
 * - 允许排队：[pendingCapacity] 个；
 * - 排队也满了 → 按 [policy] 处理，默认 [OverflowPolicy.DROP_OLDEST]：
 *   **丢掉队列里最老的那个（通常是刚滚出屏幕的那一行），把位置让给新请求**。
 *   被丢掉的请求立刻返回 null（= 本次不出图，也不写失败负缓存——下次可见时还能再试）。
 *   这就是"按可见区优先"的近似实现：列表滚动的时序里，新的请求≈当前可见区。
 *
 * 被丢掉的请求**不执行任何抽帧/网络动作**，所以它不会给崩溃前的"读风暴"添一条。
 *
 * @param parallelism 同时抽帧上限（≥ 1）。
 * @param pendingCapacity 排队上限（默认 [DEFAULT_PENDING_CAPACITY]；0 = 只能立刻执行，否则跳过）。
 * @param policy 排队满时的拒绝策略。
 */
class ThumbnailConcurrencyLimiter(
    private val parallelism: Int,
    private val pendingCapacity: Int = DEFAULT_PENDING_CAPACITY,
    val policy: OverflowPolicy = OverflowPolicy.DROP_OLDEST,
) {

    init {
        require(parallelism >= 1) { "并发上限必须 ≥ 1：$parallelism" }
        require(pendingCapacity >= 0) { "排队上限不能为负：$pendingCapacity" }
    }

    /** 排队满时怎么办。 */
    enum class OverflowPolicy {

        /** 丢掉队列里最老的请求（默认：让位给刚进可见区的那一行）。 */
        DROP_OLDEST,

        /** 直接拒绝新请求（新来的看不到图，队列保持原样）。 */
        DROP_NEWEST,
    }

    private val lock = Any()

    /** 剩余可用槽位。 */
    private var freeSlots: Int = parallelism

    /** 正在等槽位的请求（有界）。 */
    private val waiting = ArrayDeque<Waiter>()

    private var inFlight: Int = 0

    private var peakInFlight: Int = 0

    private val skipped = AtomicLong(0L)

    /** 同时抽帧的上限（诊断/单测）。 */
    fun parallelism(): Int = parallelism

    /** 允许的排队长度（诊断/单测）。 */
    fun pendingCapacity(): Int = pendingCapacity

    /** 当前正在执行的数量。 */
    fun inFlightCount(): Int = synchronized(lock) { inFlight }

    /** 当前排队的数量；**永不超过 [pendingCapacity]**。 */
    fun waitingCount(): Int = synchronized(lock) { waiting.size }

    /** 历史并发峰值。 */
    fun peakInFlightCount(): Int = synchronized(lock) { peakInFlight }

    /** 被拒绝（丢弃）的请求总数。 */
    fun skippedCount(): Long = skipped.get()

    /**
     * 抢一个槽位执行 [block]；排队满时按 [policy] 丢弃。
     *
     * @return [block] 的结果；被丢弃时返回 null（**没有执行 block**）。
     */
    suspend fun <T> withSlot(block: suspend () -> T): T? {
        val waiter = Waiter()
        if (!admit(waiter)) {
            skipped.incrementAndGet()
            return null
        }
        try {
            waiter.done.await()
        } catch (e: CancellationException) {
            // 调用方被取消（列表项回收/换页）：把占的槽位还回去，别让许可漏掉
            if (abandon(waiter)) endSlot()
            throw e
        }
        if (!waiter.granted) {
            skipped.incrementAndGet()
            return null
        }
        return try {
            block()
        } finally {
            endSlot()
        }
    }

    // ------------------------------------------------------------ 内部记账

    private class Waiter {
        val done = CompletableDeferred<Boolean>()

        @Volatile
        var granted: Boolean = false
    }

    /** @return true = 已拿到槽位或已入队；false = 当场被拒（未入队）。 */
    private fun admit(waiter: Waiter): Boolean = synchronized(lock) {
        if (freeSlots > 0) {
            freeSlots--
            beginSlot()
            grant(waiter)
            return true
        }
        if (waiting.size >= pendingCapacity) {
            when (policy) {
                OverflowPolicy.DROP_NEWEST -> return false
                OverflowPolicy.DROP_OLDEST -> {
                    val victim = waiting.removeFirstOrNull() ?: return false
                    victim.granted = false
                    victim.done.complete(false)
                }
            }
        }
        waiting.addLast(waiter)
        true
    }

    /** 还槽位：有人排队就把槽位直接交接给它，否则记回空闲。 */
    private fun endSlot() = synchronized(lock) {
        inFlight--
        val next = waiting.removeFirstOrNull()
        if (next != null) {
            beginSlot()
            grant(next)
        } else {
            freeSlots++
        }
    }

    /** 取消时把排队条目摘掉；@return true = 其实已经拿到槽位（调用方要负责 [endSlot]）。 */
    private fun abandon(waiter: Waiter): Boolean = synchronized(lock) {
        if (waiter.granted) return true
        waiting.remove(waiter)
        false
    }

    private fun beginSlot() {
        inFlight++
        if (inFlight > peakInFlight) peakInFlight = inFlight
    }

    private fun grant(waiter: Waiter) {
        waiter.granted = true
        waiter.done.complete(true)
    }

    companion object {

        /** 默认排队上限：32 ≈ 两屏列表（可见区之外的请求直接丢）。 */
        const val DEFAULT_PENDING_CAPACITY: Int = 32
    }
}
