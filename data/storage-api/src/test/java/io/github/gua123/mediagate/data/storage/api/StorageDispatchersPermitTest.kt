package io.github.gua123.mediagate.data.storage.api

import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

/**
 * **许可记账的最底层不变式**（2026-11-21 第三轮审计 P2）：[StorageDispatchers.GatedTask.releasePermit]
 * 必须幂等——只有真正从"持有"翻到"未持有"的那一次还许可。
 *
 * 为什么值得单独钉：`StorageDispatchers.io` 是进程级单例、线程前缀挂在真实池上，"投递失败但任务
 * 已经在别处跑完"这种并发很难从外部造出来；而漏/多还一格许可的后果都是闸门失效：
 *
 * - **漏** 32 格（= 池上限）⇒ 闸门永久死锁（这正是上一版"只 catch RejectedExecutionException"
 *   的死法：`execute` 抛 OOM/Error 时许可已经 `hold()` 却没人还）；
 * - **多还** 1 格 ⇒ 许可总数超过池上限，闸门从此形同虚设（比泄漏更难查：池照样满，闸门挡不住）。
 */
class StorageDispatchersPermitTest {

    /** 8 个线程同时对同一个任务调 releasePermit：只能有一个成功，许可回到原值。 */
    @Test
    fun `并发 releasePermit 只还一次许可_而不是 volatile 的 check-then-act`() {
        val gate = Semaphore(2)
        assertTrue("预置：先占掉一格，模拟 dispatch 已经 tryAcquire 成功", gate.tryAcquire())
        val task = StorageDispatchers.GatedTask(EmptyCoroutineContext, gate, Runnable { })
        task.hold()
        assertEquals("持有许可时闸门少一格", 1, gate.availablePermits)

        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val released = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "release-race").apply { isDaemon = true } }
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (task.releasePermit()) released.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("8 个 releasePermit 必须都跑完", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("只有一次能真正还许可（CAS 的赢家）", 1, released.get())
        assertEquals("许可必须回到预置值，绝不许多出来", 2, gate.availablePermits)
        assertFalse("已经还过就再也不许还", task.releasePermit())
        assertEquals("重复调用不许再动许可", 2, gate.availablePermits)
        assertFalse("不再持有许可", task.holdsPermit)
    }

    /** 没持许可的任务调用 releasePermit 不许"凭空多出一格"。 */
    @Test
    fun `没持许可时 releasePermit 不还许可`() {
        val gate = Semaphore(2)
        val task = StorageDispatchers.GatedTask(EmptyCoroutineContext, gate, Runnable { })
        assertFalse(task.releasePermit())
        assertEquals("闸门许可一格都不许多", 2, gate.availablePermits)
    }

    /** `run()` 正常路径：跑完还许可；之后再 releasePermit 也不会多还。 */
    @Test
    fun `run 跑完还一次许可_重复调用不再还`() {
        val gate = Semaphore(1)
        assertTrue(gate.tryAcquire())
        val ran = AtomicInteger()
        val task = StorageDispatchers.GatedTask(EmptyCoroutineContext, gate, Runnable { ran.incrementAndGet() })
        task.hold()

        task.run()

        assertEquals("阻塞体必须真的跑了", 1, ran.get())
        assertEquals("跑完许可归还", 1, gate.availablePermits)
        assertFalse("run 之后再 release 不许再还", task.releasePermit())
        assertEquals("许可仍是一格", 1, gate.availablePermits)
    }
}
