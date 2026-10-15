package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * **并行预取的实证测试**（2026-10-14 用户要求「可否单文件多线程…打开文件夹时自动缓存」）。
 *
 * 为什么必须"实证并发峰值"：本项目 0.1.43 踩过"许可池被一个任务抢光 ⇒ 并发退化成串行"的坑，
 * 那种 bug 功能照常、只是慢，肉眼看不出来。这里同时钉住两件事：
 * ① 预取确实并发；② **播放路径（顺序预取）仍然一次只发一个请求**（用户此前要求过"把并发去掉"）。
 */
class PrefetchParallelTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** 记录并发峰值的假后端：每次 openRead 停留一会儿，好让并发暴露出来。 */
    private class SlowBackend(private val payload: ByteArray) : StorageBackend {

        val peak = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)

        override val id: String = "fake-slow:/x"

        override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 8)

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = "f", path = path, size = payload.size.toLong(), mtime = 1L)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
            val current = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            delay(50)
            val slice = payload.copyOfRange(offset.toInt(), minOf(payload.size, (offset + length).toInt()))
            return object : RangeStream {
                private var cursor = 0
                override val length: Long = slice.size.toLong()
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    if (cursor >= slice.size) {
                        inFlight.decrementAndGet()
                        return -1
                    }
                    val count = minOf(len, slice.size - cursor)
                    System.arraycopy(slice, cursor, buf, off, count)
                    cursor += count
                    return count
                }

                override suspend fun seek(position: Long) { cursor = position.toInt() }

                override fun position(): Long = cursor.toLong()

                override fun close() { inFlight.decrementAndGet() }
            }
        }

        override suspend fun write(path: String, data: InputStream) = Unit

        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

        override fun close() = Unit
    }

    private fun backend(segments: Int = 4, segmentBytes: Long = 8L): Pair<SlowBackend, SegmentedCacheBackend> {
        val payload = ByteArray((segments * segmentBytes).toInt()) { (it % 251).toByte() }
        val delegate = SlowBackend(payload)
        val cache = SegmentedCacheBackend(
            delegate = delegate,
            rootDir = temporary.newFolder(),
            segmentBytes = segmentBytes,
            maxBytes = 64L * 1024 * 1024,
            namespace = "test",
        )
        return delegate to cache
    }

    @Test
    fun 并行预取确实并发_并把所有段都缓存下来() = runTest {
        val (delegate, cache) = backend()
        cache.prefetchParallel(path = "movie.mp4", offset = 0L, length = 32L, concurrency = 4)
        assertTrue("并发峰值应 ≥ 2，实际 ${delegate.peak.get()}", delegate.peak.get() >= 2)
        assertEquals(32L, cache.cachedBytes())
    }

    @Test
    fun 顺序预取保持一次一个请求_播放路径行为不变() = runTest {
        val (delegate, cache) = backend()
        cache.prefetch(path = "movie.mp4", offset = 0L, length = 32L)
        assertEquals("顺序预取不应并发（用户此前要求的播放口径）", 1, delegate.peak.get())
        assertEquals(32L, cache.cachedBytes())
    }
}
