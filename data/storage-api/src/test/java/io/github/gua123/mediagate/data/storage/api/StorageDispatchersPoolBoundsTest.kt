package io.github.gua123.mediagate.data.storage.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **回归护栏（2026-11-21 真机 SIGABRT）**：存储层线程池必须始终是「有上限的池」。
 *
 * ### 事故
 *
 * 0.1.113 真机进 `/home/nas/movie`（数百个视频）后 3/3 在 4–40 秒内 SIGABRT，
 * tombstone 只有 4 帧且全在 libc：`abort ← __init_additional_stacks ← __pthread_start`
 * （新建线程时栈守卫页 `mprotect(0x…, 16384)` 返回 ENOMEM）。崩溃前
 * `MG/storage-timeout: worker 启动：读取` 打到 464 条/秒。
 *
 * ### 回归点与两次修复
 *
 * ① 8b65eb1（v0.1.113 实测过的有界形态）= `ThreadPoolExecutor(core=12, max=32,
 * keepAlive=30 s, SynchronousQueue, AbortPolicy)`；8676fcc 把它换成
 * `Executors.newCachedThreadPool()`（max = Integer.MAX_VALUE），闸门被拆掉。
 * 历史实证：无上限那版线程数飙到 **6287**（预读失败 1900 条/秒、打开 600 次/秒）。
 *
 * ② 上一版为了"不丢存储读"又换成 **CallerRunsPolicy**——池满时任务在**提交线程内联执行**，
 * 于是 Main / Default / 代理线程都被塞进过阻塞读，硬截止也失效（见 `StorageSaturationNoInlineTest`）。
 * 现在保留 12/32/30 s/SynchronousQueue + AbortPolicy 的历史形状，池前面加一道
 * 许可闸门（[StorageDispatchers.io]）：满员时**挂起**提交方协程，绝不开新线程、绝不内联。
 *
 * ### 这些用例钉住的
 *
 * 池类型、core/max 数值、队列容量（必须 0 = 没有"排队等线程"死锁层）、拒绝策略
 * （AbortPolicy：历史口径，调度器层的闸门保证走不到它），以及"压到饱和时线程数正好是 32、
 * 一个都不多开、200 个任务一个都不少"。
 */
class StorageDispatchersPoolBoundsTest {

    /** 轮询事实（条件驱动，不 sleep 赌时序）。 */
    private fun awaitTrue(timeoutMs: Long, what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(2)
        }
        assertTrue(timeoutMs.toString() + " ms 内没等到：" + what, predicate())
    }

    @Test
    fun `存储 IO 池沿用历史有界形状：core 12 max 32 SynchronousQueue`() {
        val pool = StorageDispatchers.ioPool
        assertEquals("常驻线程数（历史 12 = OpenSSH 单会话 10 通道 + 2 余量）", 12, pool.corePoolSize)
        assertEquals("上限常量与池一致", StorageDispatchers.IO_MAX_THREADS, pool.maximumPoolSize)
        assertEquals("线程上限就是 32（真机跑过的量级）", 32, pool.maximumPoolSize)
        assertTrue("队列必须是零容量的 SynchronousQueue（有界）", pool.queue is SynchronousQueue)
        assertEquals("SynchronousQueue 剩余容量恒为 0", 0, pool.queue.remainingCapacity())
        assertSame(
            "拒绝策略必须是 AbortPolicy（8b65eb1 的历史形状；CallerRuns 是上次新加的、正是坏掉的那半）",
            ThreadPoolExecutor.AbortPolicy::class.java,
            pool.rejectedExecutionHandler.javaClass,
        )
        val thread = pool.threadFactory.newThread {}!!
        assertEquals("线程名要能一眼认出（logcat / tombstone）", "mg-storage-io", thread.name)
        assertTrue("必须是 daemon：不许拖住进程退出", thread.isDaemon)

        val facts = StorageDispatchers.ioFacts()
        assertEquals("许可闸门的上限 = 池上限（满员挂起，不开线程）", 32, facts.admissionCeiling)
        assertTrue("在飞任务数不许超过许可数", facts.inFlight <= 32)
        assertEquals("拒绝策略快照", "AbortPolicy", facts.rejectionPolicy)
    }

    @Test
    fun `硬截止池同样有上限且与 IO 池分开`() {
        val pool = StorageDispatchers.deadlinePool
        assertEquals("常驻线程数", 12, pool.corePoolSize)
        assertEquals("上限", StorageDispatchers.DEADLINE_MAX_THREADS, pool.maximumPoolSize)
        assertEquals("上限就是 32", 32, pool.maximumPoolSize)
        assertNotSame(
            "两个池必须分开（合成一个会自锁：worker 全在等下层 IO）",
            StorageDispatchers.ioPool as Any,
            StorageDispatchers.deadlinePool as Any,
        )
        assertTrue(pool.queue is SynchronousQueue)
        assertSame(
            "拒绝策略同样是 AbortPolicy",
            ThreadPoolExecutor.AbortPolicy::class.java,
            pool.rejectedExecutionHandler.javaClass,
        )
        assertEquals("许可闸门", 32, StorageDispatchers.deadlineFacts().admissionCeiling)

        assertEquals("mg-storage-io", StorageDispatchers.ioPool.threadFactory.newThread {}!!.name)
        assertEquals("mg-storage-deadline", StorageDispatchers.deadlinePool.threadFactory.newThread {}!!.name)
    }

    @Test
    fun `200 个并发阻塞任务也顶不破 32 个线程的上限，且一个都不少`() = runBlocking {
        val gate = CountDownLatch(1)
        val ran = AtomicInteger()
        val jobs = (1..200).map {
            async(Dispatchers.Default) {
                withContext(StorageDispatchers.io) {
                    gate.await()
                    ran.incrementAndGet()
                }
            }
        }
        // 等池把能开的线程都开满（32 个许可都被占住、32 个线程都在闸门上等）
        awaitTrue(10_000, "32 个线程被占满") {
            val facts = StorageDispatchers.ioFacts()
            facts.inFlight == 32 && facts.liveThreads == 32
        }
        val facts = StorageDispatchers.ioFacts()
        gate.countDown()
        jobs.awaitAll()

        assertTrue(
            "并发 200 个任务，线程数不能超过上限：live=" + facts.liveThreads + " ceiling=" + facts.threadCeiling,
            facts.liveThreads <= StorageDispatchers.IO_MAX_THREADS,
        )
        assertTrue(
            "历史峰值也不能超过上限：largest=" + facts.largestThreads,
            facts.largestThreads <= StorageDispatchers.IO_MAX_THREADS,
        )
        assertTrue("在飞任务数同样受许可封顶：inFlight=" + facts.inFlight, facts.inFlight <= 32)
        assertTrue("poolSize / largest 都读得到（池没被关闭）", facts.largestThreads >= 1)
        assertEquals("200 个任务必须全部真的跑完（挂起等待，不是拒绝、不是丢弃）", 200, ran.get())
        assertEquals("跑完以后许可要全部还回去", 0, StorageDispatchers.ioFacts().inFlight)
    }

    @Test
    fun `池饱和时正好用满 32 个线程，第 33 个由 AbortPolicy 拒绝`() {
        val pool = StorageDispatchers.ioPool
        val gate = CountDownLatch(1)
        try {
            // 直接投池（绕过调度器）：32 个任务各占一个线程，线程数同步涨到 32
            repeat(32) { pool.execute { gate.await() } }
            val facts = StorageDispatchers.ioFacts()
            assertEquals("饱和时线程数正好等于上限", 32, facts.liveThreads)
            assertEquals("峰值也正好等于上限", 32, facts.largestThreads)
            assertEquals("队列容量恒为 0（没有排队等线程这一层）", 0, facts.queueCapacity)
            assertEquals("排队任务数恒为 0", 0, facts.queuedTasks)
            assertEquals("拒绝策略", "AbortPolicy", facts.rejectionPolicy)
            // 第 33 个：SynchronousQueue 无处排队 + 线程已到 32 ⇒ 抛 RejectedExecutionException。
            // 这是历史口径；走 StorageDispatchers.io 的路径有许可闸门，永远到不了这里。
            val rejected = runCatching { pool.execute { } }.exceptionOrNull()
            assertTrue(
                "第 33 个必须被拒绝（AbortPolicy，不内联、不新开线程），实际：" + rejected,
                rejected is java.util.concurrent.RejectedExecutionException,
            )
            assertEquals("被拒绝也不该多开线程", 32, StorageDispatchers.ioFacts().liveThreads)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `空闲线程按历史值 30 秒回收`() {
        assertEquals(30L, StorageDispatchers.IDLE_KEEP_ALIVE_SECONDS)
        assertEquals(
            "空闲回收时间",
            StorageDispatchers.IDLE_KEEP_ALIVE_SECONDS,
            StorageDispatchers.ioPool.getKeepAliveTime(TimeUnit.SECONDS),
        )
        assertEquals(
            "两个池同一口径",
            StorageDispatchers.IDLE_KEEP_ALIVE_SECONDS,
            StorageDispatchers.deadlinePool.getKeepAliveTime(TimeUnit.SECONDS),
        )
    }
}
