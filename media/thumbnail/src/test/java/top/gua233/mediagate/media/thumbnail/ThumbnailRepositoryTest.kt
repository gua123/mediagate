package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.RemoteEntry

/**
 * [ThumbnailRepository] 的 JVM 单测（R5，plan 4.4）。
 *
 * 用假抽帧器验证流水线顺序：内存/磁盘优先 → 负缓存 → 主策略 10%/1%/25% 换位重试 →
 * FFmpeg 兜底 → null + 负缓存；以及并发上限（默认 3、SFTP/FTP 为 2）与可取消性。
 * 真实的 MMR / ffmpeg 抽帧依赖设备，由编译期检查 + 真机验收覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(tag: String, size: Long = 1_000L, mtime: Long = 1_000L) =
        RemoteEntry(name = "$tag.mp4", path = "/dir/$tag.mp4", size = size, mtime = mtime)

    private fun bytes(value: Int, size: Int = 64): ByteArray = ByteArray(size) { value.toByte() }

    private fun newCache(
        memoryCapacity: Int = 256,
        negativeTtlMs: Long = ThumbnailCache.DEFAULT_NEGATIVE_TTL_MS,
        clock: () -> Long = System::currentTimeMillis,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = ThumbnailCache(
        rootDir = tmp.newFolder(),
        memoryCapacity = memoryCapacity,
        negativeTtlMs = negativeTtlMs,
        clock = clock,
        io = io,
    )

    private fun repository(
        primary: FrameExtractor,
        fallback: FrameExtractor? = null,
        cache: ThumbnailCache = newCache(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = ThumbnailRepository(cache = cache, primary = primary, fallback = fallback, io = io)

    @Test
    fun `缓存命中时不触发抽帧`() = runTest {
        val cache = newCache()
        val primary = FakeExtractor(responder = { _, _ -> bytes(9) })
        val primary2 = FakeExtractor()
        val repo = repository(primary, cache = cache)
        val e = entry("v")
        val backend = FakeBackend()

        // 先打一次，填充缓存
        assertArrayEquals(bytes(9), repo.thumbnail(e, backend)!!)
        assertEquals(1, primary.calls.get())

        // 内存命中：第二个仓库实例（无内存）走磁盘命中
        val fromDisk = repository(primary2, cache = ThumbnailCache(cache.rootDir))
        assertArrayEquals(bytes(9), fromDisk.thumbnail(e, backend)!!)
        assertEquals(0, primary2.calls.get())
        // 命中缓存不该打开远端
        assertEquals(1, backend.openCount.get())
    }

    @Test
    fun `主策略按 10 1 25 百分比依次换位重试`() = runTest {
        val primary = FakeExtractor(responder = { positionMs, _ -> if (positionMs == 25_000L) bytes(9) else null })
        primary.durationHint = 100_000L
        val repo = repository(primary)

        assertArrayEquals(bytes(9), repo.thumbnail(entry("v"), FakeBackend())!!)
        assertEquals(listOf(10_000L, 1_000L, 25_000L), primary.positions.toList())
    }

    @Test
    fun `自定义比例作为首选位置`() = runTest {
        val primary = FakeExtractor(responder = { positionMs, _ -> if (positionMs == 25_000L) bytes(1) else null })
        primary.durationHint = 100_000L
        val repo = repository(primary)
        assertArrayEquals(bytes(1), repo.thumbnail(entry("v"), FakeBackend(), positionRatio = 0.5)!!)
        assertEquals(listOf(50_000L, 1_000L, 25_000L), primary.positions.toList())
    }

    @Test
    fun `拿不到时长时用固定位置再退到任意帧`() = runTest {
        val primary = FakeExtractor(responder = { _, _ -> null })
        val repo = repository(primary)
        assertNull(repo.thumbnail(entry("v"), FakeBackend()))
        assertEquals(listOf(ThumbnailRepository.DEFAULT_POSITION_MS, ThumbnailRepository.ANY_FRAME_MS), primary.positions.toList())
    }

    @Test
    fun `主策略全失败后走 FFmpeg 兜底`() = runTest {
        val primary = FakeExtractor(responder = { _, _ -> null })
        primary.durationHint = 100_000L
        val fallback = FakeExtractor(responder = { _, _ -> bytes(7) })
        val repo = repository(primary, fallback)

        assertArrayEquals(bytes(7), repo.thumbnail(entry("v"), FakeBackend())!!)
        assertEquals(3, primary.calls.get())
        assertEquals(listOf(10_000L), fallback.positions.toList())
    }

    @Test
    fun `全部失败返回 null 并落负缓存`() = runTest {
        val cache = newCache()
        val primary = FakeExtractor()
        val fallback = FakeExtractor()
        val repo = repository(primary, fallback, cache)
        val e = entry("v")
        val backend = FakeBackend()
        val key = repo.keyFor(e, backend)

        assertNull(repo.thumbnail(e, backend))
        assertEquals(2, primary.calls.get())
        assertEquals(1, fallback.calls.get())
        assertTrue("失败应写入负缓存", cache.isNegative(key))
        assertFalse("失败不应留下缓存文件", cache.fileFor(key).exists())
        assertEquals(0L, cache.sizeBytes())

        // TTL 内第二次请求直接判失败，不再打远端
        assertNull(repo.thumbnail(e, backend))
        assertEquals(2, primary.calls.get())
        assertEquals("负缓存命中不该再打开远端", 1, backend.openCount.get())
    }

    @Test
    fun `负缓存到期后允许重试`() = runTest {
        var now = 1_000_000L
        val cache = newCache(negativeTtlMs = 5_000, clock = { now })
        val primary = FakeExtractor()
        val repo = repository(primary, cache = cache)
        val e = entry("v")
        val backend = FakeBackend()

        assertNull(repo.thumbnail(e, backend))
        val callsAfterFirst = primary.calls.get()
        now += 5_000
        assertNull(repo.thumbnail(e, backend))
        assertTrue("TTL 到期后应重新抽帧", primary.calls.get() > callsAfterFirst)
    }

    @Test
    fun `抽帧器抛异常也不外泄`() = runTest {
        val primary = FakeExtractor().apply { throwOnExtract = IllegalStateException("boom") }
        val fallback = FakeExtractor(responder = { _, _ -> bytes(3) })
        val repo = repository(primary, fallback)
        assertArrayEquals(bytes(3), repo.thumbnail(entry("v"), FakeBackend())!!)
    }

    @Test
    fun `打开数据源失败按失败处理`() = runTest {
        val cache = newCache()
        val primary = FakeExtractor(responder = { _, _ -> bytes(1) })
        val repo = repository(primary, cache = cache)
        val e = entry("v")
        val backend = FakeBackend(failOpenRead = true)

        assertNull(repo.thumbnail(e, backend))
        assertEquals(0, primary.calls.get())
        assertTrue(cache.isNegative(repo.keyFor(e, backend)))
    }

    @Test
    fun `目录项直接返回 null`() = runTest {
        val primary = FakeExtractor(responder = { _, _ -> bytes(1) })
        val repo = repository(primary)
        val dir = RemoteEntry(name = "folder", path = "/folder", isDirectory = true)
        assertNull(repo.thumbnail(dir, FakeBackend()))
        assertEquals(0, primary.calls.get())
    }

    @Test
    fun `并发上限默认 3`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val primary = FakeExtractor(delayMs = 100, responder = { _, _ -> bytes(1) })
        val repo = repository(primary, cache = newCache(io = dispatcher), io = dispatcher)
        val backend = FakeBackend()

        val jobs = (1..6).map { index -> launch { repo.thumbnail(entry("v$index"), backend) } }
        jobs.forEach { it.join() }

        assertEquals(6, primary.calls.get())
        assertEquals("并发峰值应等于配置的上限", 3, primary.peak.get())
    }

    @Test
    fun `SFTP FTP 后端并发上限降到 2`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val primary = FakeExtractor(delayMs = 100, responder = { _, _ -> bytes(1) })
        val repo = repository(primary, cache = newCache(io = dispatcher), io = dispatcher)
        val sftp = FakeBackend(
            id = "sftp://192.168.1.10:2222",
            caps = Caps(randomAccess = true, maxParallelReads = 8),
        )
        val ftp = FakeBackend(
            id = "ftp:192.168.1.10:21",
            caps = Caps(randomAccess = true, maxParallelReads = 1),
        )

        assertTrue(repo.isSlowBackend(sftp))
        assertTrue(repo.isSlowBackend(ftp))
        assertFalse(repo.isSlowBackend(FakeBackend()))

        val jobs = (1..6).map { index -> launch { repo.thumbnail(entry("v$index"), sftp) } }
        jobs.forEach { it.join() }
        assertEquals(2, primary.peak.get())
    }

    @Test
    fun `协程取消时中断且不写负缓存`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = newCache(io = dispatcher)
        val primary = FakeExtractor(delayMs = 10_000)
        val repo = repository(primary, cache = cache, io = dispatcher)
        val e = entry("v")
        val backend = FakeBackend()
        val key = repo.keyFor(e, backend)

        val job = launch { repo.thumbnail(e, backend) }
        runCurrent()
        // 已经进入抽帧（挂在 delay 上）
        assertEquals(1, primary.calls.get())

        job.cancelAndJoin()
        assertFalse("取消不等于失败，不该写负缓存", cache.isNegative(key))
        assertEquals("取消后不该留下缓存", 0L, cache.sizeBytes())
        assertFalse(job.isActive)
    }

    @Test
    fun `图片路径可复用同一份 key 与缓存`() = runTest {
        val cache = newCache()
        val repo = repository(FakeExtractor(), cache = cache)
        val image = RemoteEntry(name = "photo.jpg", path = "/pics/photo.jpg", size = 2_000L, mtime = 3_000L)
        val backend = FakeBackend()

        val key = repo.keyFor(image, backend)
        assertEquals(MediaKind.IMAGE, key.kind)
        assertTrue(key.relativePath.startsWith("image/"))
        assertNull(repo.cached(image, backend))

        cache.put(key, bytes(5))
        assertArrayEquals(bytes(5), repo.cached(image, backend)!!)
    }
}
