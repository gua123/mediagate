package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **回归护栏（2026-11-21 只读审计的两条 P0）**：存储层池满时的两条铁律。
 *
 * 1. **满员不得在提交线程内联执行阻塞体**——上一版用 `SynchronousQueue + CallerRunsPolicy`，
 *    池满时 `executor.execute` 直接把任务跑在提交线程上：Main（`rememberCoroutineScope` 调
 *    `listCacheFolders`）、`Dispatchers.Default`（`ioScope` 当 `readAheadScope`）、代理/Media3 loader
 *    线程都被塞进过 JSch 阻塞读。现在满员只**挂起**提交方协程（[StorageDispatchers.io] 的许可闸门）。
 *
 * 2. **池满时硬截止仍然成立**——[TimeoutStorageBackend.withHardDeadline] 里 `scope.async { block() }`
 *    曾在 `withTimeout` 之前：内联执行时 `block()` 先跑起来、计时器还没启动 ⇒ 永不超时 ⇒
 *    `onHardTimeout()` 永不触发 ⇒ SFTP 会话永不作废 ⇒ 池更满（正反馈）。现在 async 建在
 *    `withTimeout` 之内，且调度器不会内联。
 *
 * **饱和怎么造**：每个占位任务都由**独立提交线程**用 `runBlocking { withContext(io) { 闸门 } }` 提交，
 * 于是"内联"与"挂起"两种实现都能被压到 32/32（否则 CallerRuns 会把提交线程自己卡住，连饱和都造不出来）。
 *
 * 池饱和不是病态：每段预读原先要 2 个 io 线程（一个 `withContext(io){job.awaitDone()}` 轮询 +
 * 一个 256 KiB 读），20 段 = 40 > 32。
 */
class StorageSaturationNoInlineTest {

    /** 轮询一个事实（条件驱动，不 sleep 赌时序）。 */
    private fun awaitTrue(timeoutMs: Long, what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(2)
        }
        assertTrue(timeoutMs.toString() + " ms 内没等到：" + what, predicate())
    }

    /** 用 [threads] 个独立提交线程把 [dispatcher] 的许可/线程全占住（内联与挂起两种实现都能压满）。 */
    private fun saturate(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        threads: Int,
        gate: CountDownLatch,
        started: AtomicInteger,
    ): java.util.concurrent.ExecutorService {
        val submitters = Executors.newFixedThreadPool(threads) { r -> Thread(r, "blocker-submitter").apply { isDaemon = true } }
        repeat(threads) {
            submitters.execute {
                runBlocking {
                    withContext(dispatcher) {
                        started.incrementAndGet()
                        gate.await()
                    }
                }
            }
        }
        return submitters
    }

    @Test
    fun `池满时第 33 个任务挂起等待，绝不在提交线程内联执行`() {
        val gate = CountDownLatch(1)
        val started = AtomicInteger()
        val ceiling = StorageDispatchers.IO_MAX_THREADS
        val submitters = saturate(StorageDispatchers.io, ceiling, gate, started)
        try {
            awaitTrue(10_000, "32 个占位任务都跑起来（池被压满）") { started.get() == ceiling }
            awaitTrue(10_000, "32 个 io 线程都在闸门上等") {
                StorageDispatchers.ioFacts().liveThreads == ceiling
            }

            val ranOnThread = AtomicReference<String?>(null)
            val finished = CountDownLatch(1)
            // 模拟"饱和时从 Main/Default 提交一次存储读"：提交线程有名字，便于断言没被内联
            val submitter = Thread({
                runBlocking {
                    withContext(StorageDispatchers.io) {
                        ranOnThread.set(Thread.currentThread().name)
                    }
                    finished.countDown()
                }
            }, "saturated-submitter").apply { isDaemon = true; start() }

            // 观察窗：上一版（CallerRuns）会在**纳秒级**跑在 saturated-submitter 上并立刻结束
            val earlyDone = finished.await(250, TimeUnit.MILLISECONDS)
            assertFalse(
                "池满时任务不许被内联执行（250 ms 内就结束了，说明又跑回提交线程了）",
                earlyDone,
            )
            assertNull(
                "池满时提交线程不许执行存储体（ranOn=" + ranOnThread.get() + "）",
                ranOnThread.get(),
            )

            gate.countDown()
            assertTrue("放闸后任务必须完成（挂起等待 ≠ 丢任务）", finished.await(5, TimeUnit.SECONDS))
            submitter.join(5_000)
            val ranOn = ranOnThread.get()
            assertNotNull("任务最终必须真的跑了", ranOn)
            assertTrue(
                "任务必须在存储池线程上跑，实际：" + ranOn,
                ranOn!!.startsWith("mg-storage-io"),
            )
            assertTrue(
                "许可数不能漏：跑完要还回去（inFlight=" + StorageDispatchers.ioFacts().inFlight + "）",
                StorageDispatchers.ioFacts().inFlight <= ceiling,
            )
        } finally {
            gate.countDown()
            submitters.shutdown()
            submitters.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    /** 挂在闸门上、且会记录"有没有真的被调用"的假后端。 */
    private class GatedBackend(private val slowOp: CountDownLatch) : StorageBackend {
        override val id: String = "slow:/"
        override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 4)
        val listCalls = AtomicInteger()
        val hardTimeouts = AtomicInteger()

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> {
            listCalls.incrementAndGet()
            // 上一版会把它内联到提交线程上跑：这里只等 2 s，足够让"永不超时"暴露出来
            slowOp.await(2, TimeUnit.SECONDS)
            return emptyList()
        }

        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = "a.mp4", path = path, size = 1L, mtime = 1L)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            throw StorageException.NotSupported("测试不读流")

        override suspend fun write(path: String, data: InputStream) = Unit
        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)
        override fun close() = Unit
        override fun onHardTimeout() {
            hardTimeouts.incrementAndGet()
        }
    }

    @Test
    fun `池满时硬截止仍然成立：200ms 超时在 1s 内抛 Timeout 并作废会话`() {
        val gate = CountDownLatch(1)
        val started = AtomicInteger()
        val slowOp = CountDownLatch(1)
        val backend = GatedBackend(slowOp)
        val ceiling = StorageDispatchers.DEADLINE_MAX_THREADS
        val submitters = saturate(StorageDispatchers.hardDeadline, ceiling, gate, started)
        try {
            awaitTrue(10_000, "32 个占位 worker 都跑起来（硬截止池被压满）") { started.get() == ceiling }
            awaitTrue(10_000, "32 个硬截止线程都在闸门上等") {
                StorageDispatchers.deadlineFacts().liveThreads == ceiling
            }

            val guard = TimeoutStorageBackend(backend, operationTimeoutMs = 200L, readTimeoutMs = 200L)
            val startedAt = System.currentTimeMillis()
            val thrown = runCatching { runBlocking { guard.list("/movie") } }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue("必须是存储超时，实际：" + thrown, thrown is StorageException.Timeout)
            assertTrue(
                "不许「根本没等就抛」：超时是 200 ms，至少得等够 200 ms，实际 " + elapsed + " ms",
                elapsed >= 200L,
            )
            assertTrue(
                "池满时硬截止必须照常生效（≤1 s），实际 " + elapsed + " ms",
                elapsed < 1_000L,
            )
            assertEquals(
                "超时必须通知后端作废会话（onHardTimeout），否则 SFTP 会话永不释放",
                1,
                backend.hardTimeouts.get(),
            )
            assertEquals(
                "池满时下层调用一次都不许跑（跑了就是内联/越过闸门）",
                0,
                backend.listCalls.get(),
            )

            // 收尾：放闸让 32 个占位 worker 跑完，再确认**许可全部还回去**——
            // 漏一个许可就是闸门永久窄一格，漏满 32 个就是死锁（2026-11-21 第三轮审计 P2 的形态）。
            gate.countDown()
            awaitTrue(2_000, "放闸后硬截止池的许可全部还回去（inFlight 归零）") {
                StorageDispatchers.deadlineFacts().inFlight == 0
            }
        } finally {
            gate.countDown()
            slowOp.countDown()
            submitters.shutdown()
            submitters.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    /**
     * **形状巡检（不是行为测试）**：只钉住"池的拒绝策略没有被人改回 CallerRunsPolicy"。
     *
     * 它证明不了"满员时不会内联"——真正的判据在用例 1（挂起等待、绝不在提交线程跑阻塞体）。
     * 这里能测到的只有形状：`SynchronousQueue` 零容量 + `AbortPolicy` ⇒ 第 33 个直接抛
     * [RejectedExecutionException]；若 handler 被换成 `CallerRunsPolicy`，第 33 个会**在提交线程上
     * 跑起来**（本用例的 `gate.await()` 会把测试线程自己卡死），于是这条断言立刻红。
     *
     * 历史背景：上一版就是照这个形状改了 CallerRuns，把"在线程里内联跑阻塞体"这条死路引回来的。
     */
    @Test
    fun `形状巡检：池的拒绝策略是 AbortPolicy，不是 CallerRunsPolicy（直接投池第 33 个被拒）`() {
        val pool = StorageDispatchers.ioPool
        assertEquals(
            "拒绝策略必须是 AbortPolicy（8b65eb1 的历史形状；CallerRuns 已移除）",
            "AbortPolicy",
            pool.rejectedExecutionHandler.javaClass.simpleName,
        )
        val gate = CountDownLatch(1)
        var submitted = 0
        try {
            repeat(StorageDispatchers.IO_MAX_THREADS) {
                pool.execute { gate.await() }
                submitted++
            }
            assertEquals("32 个任务必须都拿到线程", 32, pool.poolSize)
            val rejected = runCatching { pool.execute { } }.exceptionOrNull()
            assertTrue("第 33 个必须被拒绝（池满没有排队层），实际：" + rejected, rejected is RejectedExecutionException)
            assertEquals("被拒绝的任务不许混进 already-submitted", 32, submitted)
        } finally {
            gate.countDown()
        }
    }
}
