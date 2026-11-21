package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **回归护栏（2026-11-21 真机 SIGABRT）**：缩略图抽帧的并发与排队都必须有上限。
 *
 * 事故：进 /home/nas/movie（数百个视频）后每一行列表项都在排队抽缩略图。旧实现只有
 * `Semaphore(3)`——限了"同时抽几张"，**没限排队人数**：滚出屏幕的行还挂在队里等，
 * 每个排队者下面都连着一条远端流；崩溃前 1 秒存储层能收到 464 个读任务。
 *
 * 现在的口径：并发 ≤ parallelism、排队 ≤ pendingCapacity，排队满时按策略丢弃
 * （默认丢最旧），被丢的**不执行任何抽帧/网络动作**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailConcurrencyLimiterTest {

    @Test
    fun `排队满时丢最旧：新请求进队、最老的那个直接跳过`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val limiter = ThumbnailConcurrencyLimiter(parallelism = 2, pendingCapacity = 2)
        val gate = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<String>()

        val jobs = (1..5).map { index ->
            launch(dispatcher) {
                val result = limiter.withSlot {
                    gate.await()
                    index
                }
                // withSlot 返回 null == 被丢弃（没有执行 block）
                outcomes += if (result == null) "skip$index" else "run$index"
            }
        }
        runCurrent()

        // 2 个在跑、2 个排队；第 5 个进队时把队首（第 3 个）挤掉
        assertEquals(2, limiter.inFlightCount())
        assertEquals(2, limiter.waitingCount())
        assertEquals(1, limiter.skippedCount())

        gate.complete(Unit)
        jobs.forEach { it.join() }

        assertEquals(
            "被丢的是最老的请求（3），后面两个照常执行",
            listOf("run1", "run2", "skip3", "run4", "run5").sorted(),
            outcomes.sorted(),
        )
        assertTrue("执行过的请求数 = 并发 2 + 排队 2", outcomes.count { it.startsWith("run") } == 4)
        assertEquals("结束以后槽位全部归还", 0, limiter.inFlightCount())
        assertEquals(0, limiter.waitingCount())
        assertEquals("并发峰值不许超过 parallelism", 2, limiter.peakInFlightCount())
    }

    @Test
    fun `20 个请求同时到达：并发 3 加排队 4，其余 13 个跳过`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val limiter = ThumbnailConcurrencyLimiter(parallelism = 3, pendingCapacity = 4)
        val gate = CompletableDeferred<Unit>()
        var executed = 0
        var skipped = 0

        val jobs = (1..20).map {
            launch(dispatcher) {
                val result = limiter.withSlot {
                    gate.await()
                    it
                }
                if (result == null) skipped++ else executed++
            }
        }
        runCurrent()

        assertTrue("排队数不能超过上限：${limiter.waitingCount()}", limiter.waitingCount() <= 4)
        assertEquals("同时在跑的正好是并发上限", 3, limiter.inFlightCount())

        gate.complete(Unit)
        jobs.forEach { it.join() }

        assertEquals("并发 3 + 排队 4 = 7 个真的跑过", 7, executed)
        assertEquals("其余 13 个被跳过（没抽帧、没开流）", 13, skipped)
        assertEquals(20, executed + skipped)
        assertTrue(limiter.peakInFlightCount() <= 3)
    }

    @Test
    fun `排队中的请求被取消不泄漏槽位`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val limiter = ThumbnailConcurrencyLimiter(parallelism = 1, pendingCapacity = 4)
        val gate = CompletableDeferred<Unit>()

        val running = launch(dispatcher) { limiter.withSlot { gate.await() } }
        runCurrent()
        assertEquals(1, limiter.inFlightCount())

        val queued = launch(dispatcher) { limiter.withSlot { "never" } }
        runCurrent()
        assertEquals(1, limiter.waitingCount())

        // 列表项回收 → 取消排队中的请求
        queued.cancelAndJoin()
        assertEquals("取消后必须从队列里摘掉", 0, limiter.waitingCount())

        gate.complete(Unit)
        running.join()
        assertEquals(0, limiter.inFlightCount())

        // 槽位没漏：下一个请求能直接跑
        assertEquals("after-cancel", limiter.withSlot { "after-cancel" })
    }

    @Test
    fun `排队上限为 0 时只能立刻执行否则跳过`() = runTest {
        val limiter = ThumbnailConcurrencyLimiter(parallelism = 1, pendingCapacity = 0)
        val gate = CompletableDeferred<Unit>()
        val running = launch(StandardTestDispatcher(testScheduler)) { limiter.withSlot { gate.await() } }
        runCurrent()

        assertNull("没有排队位置 → 直接跳过", limiter.withSlot { "should-not-run" })
        assertEquals(1, limiter.skippedCount())

        gate.complete(Unit)
        running.join()
    }

    @Test
    fun `默认口径：排队上限 32`() {
        val limiter = ThumbnailConcurrencyLimiter(parallelism = 3)
        assertEquals(32, limiter.pendingCapacity())
        assertEquals(ThumbnailConcurrencyLimiter.DEFAULT_PENDING_CAPACITY, limiter.pendingCapacity())
    }
}
