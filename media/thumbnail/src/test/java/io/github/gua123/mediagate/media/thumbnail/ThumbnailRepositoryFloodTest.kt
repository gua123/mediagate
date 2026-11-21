package io.github.gua123.mediagate.media.thumbnail

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **回归护栏（2026-11-21 真机 SIGABRT）**：大目录里"每行都要缩略图"的洪峰必须被挡在仓库门口。
 *
 * 事故现场：进 /home/nas/movie（数百个视频）后存储层收到 464 个读任务/秒，线程数无上限增长，
 * bionic 建线程栈守卫页失败 → SIGABRT。缩略图这条路上，旧实现只限并发、不限排队。
 *
 * 这里用真线程真闸门验证：40 个请求同时到，只有 并发 3 + 排队 4 = 7 个会真的去开远端流，
 * 其余 33 个被"丢最旧"策略跳过——**一次 openRead 都不发**。
 *
 * **去时序依赖（2026-11-21 P2）**：不再用 `delay(200)` 赌"调度器已经把 40 个请求排好"，改为
 * ① [AtomicInteger] 到达计数 + [CountDownLatch] 等 40 个请求全部进场；
 * ② 用**可判定的结算等式**等洪峰落位：进 = 在跑 + 排队 + 跳过 = 40。
 */
class ThumbnailRepositoryFloodTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(tag: String) = RemoteEntry(name = "$tag.mp4", path = "/dir/$tag.mp4", size = 1_000L, mtime = 1_000L)

    /** 抽帧器：卡在闸门上，记录并发峰值与调用次数。 */
    private class GatedExtractor(private val gate: CountDownLatch) : FrameExtractor {

        val calls = AtomicInteger()

        val peak = AtomicInteger()

        private val concurrent = AtomicInteger()

        override suspend fun extract(
            source: RandomAccessSource,
            mimeHint: String?,
            positionMs: Long,
            targetWidth: Int,
        ): ByteArray? {
            calls.incrementAndGet()
            val now = concurrent.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                gate.await()
                return ByteArray(8) { 7 }
            } finally {
                concurrent.decrementAndGet()
            }
        }
    }

    /** 轮询一个可判定的事实（不 sleep 赌时序）。 */
    private suspend fun awaitTrue(timeoutMs: Long, what: String, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(5)
        }
        return predicate()
    }

    @Test
    fun `40 个缩略图请求同时到达：只放行 并发 3 加排队 4，其余跳过且不开流`() = runBlocking {
        val gate = CountDownLatch(1)
        val extractor = GatedExtractor(gate)
        val repo = ThumbnailRepository(
            cache = ThumbnailCache(rootDir = tmp.newFolder()),
            primary = extractor,
            parallelism = 3,
            slowParallelism = 2,
            pendingCapacity = 4,
        )
        val backend = FakeBackend()
        val gotImage = CopyOnWriteArrayList<Boolean>()

        val arrived = AtomicInteger()
        val allArrived = CountDownLatch(40)
        val jobs = (1..40).map { index ->
            launch(Dispatchers.Default) {
                arrived.incrementAndGet()
                allArrived.countDown()
                gotImage += (repo.thumbnail(entry("v$index"), backend) != null)
            }
        }

        assertTrue("40 个请求要全部进场（10 s 内）", allArrived.await(10, TimeUnit.SECONDS))
        assertEquals("到达计数", 40, arrived.get())

        // 结算等式：进 40 = 在跑 3 + 排队 4 + 跳过 33。三个数都来自限流器自己，不靠等待时长。
        val settled = awaitTrue(10_000, "洪峰落位") {
            repo.skippedRequests(backend) +
                repo.pendingRequests(backend) +
                repo.inFlightRequests(backend) == 40L
        }
        assertTrue(
            "40 个请求必须在 10 s 内全部落位（在跑 " + repo.inFlightRequests(backend) +
                " + 排队 " + repo.pendingRequests(backend) +
                " + 跳过 " + repo.skippedRequests(backend) + "）",
            settled,
        )
        assertTrue(
            "排队数必须被上限 4 挡住：${repo.pendingRequests(backend)}",
            repo.pendingRequests(backend) <= 4,
        )

        gate.countDown()
        jobs.joinAll()

        assertEquals("一条都没超时", 40, gotImage.size)
        assertTrue("并发峰值不许超过 3：${extractor.peak.get()}", extractor.peak.get() <= 3)
        assertEquals("只有 并发 3 + 排队 4 = 7 个真的抽了帧", 7, extractor.calls.get())
        assertEquals(
            "被跳过的 33 个不该碰远端（openRead 次数 = 放行的 7 个）",
            7,
            backend.openCount.get(),
        )
        assertTrue("必须记录被跳过的请求数", repo.skippedRequests(backend) >= 33)
        assertEquals("结束以后不留排队", 0, repo.pendingRequests(backend))
        assertEquals(3, repo.parallelismFor(backend))
        assertEquals(4, repo.pendingCapacityFor(backend))
    }
}
