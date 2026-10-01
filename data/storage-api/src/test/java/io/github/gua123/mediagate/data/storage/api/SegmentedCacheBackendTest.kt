package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream

/**
 * [SegmentedCacheBackend] 的 JVM 单测（**plan 4.1 降级链第二级**）。
 *
 * 用"不支持随机读的假后端"驱动：这才是分段缓存存在的理由——
 * 让只会顺序读的后端也能被跳着读，而且同一个段不重复过网。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentedCacheBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val segment = 1_024L
    private val payload = ByteArray(4_096) { (it % 251).toByte() }

    private fun backend(
        maxBytes: Long = 64L * 1024,
        fetches: MutableList<Pair<Long, Long>> = mutableListOf(),
    ) = SegmentedCacheBackend(
        delegate = FakeSequentialBackend(payload, fetches),
        rootDir = tmp.newFolder(),
        segmentBytes = segment,
        maxBytes = maxBytes,
        io = Dispatchers.Unconfined,
    )

    @Test
    fun `对外声明支持随机读`() {
        val cache = backend()
        assertTrue("分段缓存就是为了让「不会 seek」的后端也能被跳着读", cache.caps.randomAccess)
        assertEquals("后端 id 要透传（缓存 key 靠它）", "fake-sequential:/root", cache.id)
    }

    @Test
    fun `顺序读一次只拉一个段`() = runTest {
        val fetches = mutableListOf<Pair<Long, Long>>()
        val cache = backend(fetches = fetches)
        val stream = cache.openRead("/movie.ts", offset = 0L, length = 32L)
        val buffer = ByteArray(32)

        val read = stream.read(buffer, 0, 32)

        assertEquals(32, read)
        assertArrayEquals(payload.copyOfRange(0, 32), buffer)
        assertEquals("只该拉第 0 段", 1, fetches.size)
        assertEquals(0L to segment, fetches[0])
        stream.close()
    }

    @Test
    fun `跨段的顺序读会补齐第二段`() = runTest {
        val fetches = mutableListOf<Pair<Long, Long>>()
        val cache = backend(fetches = fetches)
        val stream = cache.openRead("/movie.ts", offset = segment - 8, length = 64L)
        val buffer = ByteArray(64)

        assertEquals(64, stream.read(buffer, 0, 64))

        assertEquals(2, fetches.size)
        assertArrayEquals(payload.copyOfRange((segment - 8).toInt(), (segment + 56).toInt()), buffer)
        stream.close()
    }

    @Test
    fun `同一个段内来回 seek 不重复过网`() = runTest {
        val fetches = mutableListOf<Pair<Long, Long>>()
        val cache = backend(fetches = fetches)
        val stream = cache.openRead("/movie.ts", offset = 0L, length = segment)
        val buffer = ByteArray(16)

        stream.read(buffer, 0, 16)
        stream.seek(500L)
        stream.read(buffer, 0, 16)
        stream.seek(0L)
        stream.read(buffer, 0, 16)

        assertEquals("三次读都在第 0 段里，只该拉一次", 1, fetches.size)
        stream.close()
    }

    @Test
    fun `跳到远处只拉落点所在段`() = runTest {
        val fetches = mutableListOf<Pair<Long, Long>>()
        val cache = backend(fetches = fetches)
        val stream = cache.openRead("/movie.ts", offset = 3L * segment + 10, length = 8L)
        val buffer = ByteArray(8)

        stream.read(buffer, 0, 8)

        assertEquals(1, fetches.size)
        assertEquals("落点在第 3 段，就该只拉第 3 段", 3L * segment to segment, fetches[0])
        assertArrayEquals(payload.copyOfRange((3 * segment + 10).toInt(), (3 * segment + 18).toInt()), buffer)
        stream.close()
    }

    @Test
    fun `预取之后读同一个段不再过网`() = runTest {
        val fetches = mutableListOf<Pair<Long, Long>>()
        val cache = backend(fetches = fetches)

        cache.prefetch("/movie.ts", offset = 2L * segment)
        val before = fetches.size
        val stream = cache.openRead("/movie.ts", offset = 2L * segment + 4, length = 8L)
        stream.read(ByteArray(8), 0, 8)

        assertEquals("预取过了，读的时候不该再拉", before, fetches.size)
        stream.close()
    }

    @Test
    fun `超出上限会淘汰最旧的段`() = runTest {
        val fetches = mutableListOf<Pair<Long, Long>>()
        // 上限只够放两段
        val cache = backend(maxBytes = 2 * segment, fetches = fetches)
        repeat(3) { index ->
            val stream = cache.openRead("/movie.ts", offset = index * segment, length = 16L)
            stream.read(ByteArray(16), 0, 16)
            stream.close()
            // 让 mtime 有区分度，否则淘汰顺序不可预测
            Thread.sleep(5)
        }

        assertTrue("缓存不该无限长：${cache.cachedBytes()}", cache.cachedBytes() <= 2 * segment)
    }

    @Test
    fun `length 按请求长度或文件剩余量计算`() = runTest {
        val cache = backend()
        assertEquals(64L, cache.openRead("/movie.ts", offset = 0L, length = 64L).length)
        assertEquals(
            "读到文件尾",
            payload.size.toLong() - 16L,
            cache.openRead("/movie.ts", offset = 16L, length = -1L).length,
        )
    }

    /** 只会顺序读的假后端：`openRead` 记下每次请求的 (offset, length)。 */
    private class FakeSequentialBackend(
        private val payload: ByteArray,
        private val fetches: MutableList<Pair<Long, Long>>,
    ) : StorageBackend {

        override val id: String = "fake-sequential:/root"

        override val caps: Caps = Caps(randomAccess = false, maxParallelReads = 1)

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = path.substringAfterLast('/'), path = path, size = payload.size.toLong(), mtime = 1L)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
            fetches += offset to length
            return object : RangeStream {
                private var cursor = offset
                override val length: Long = payload.size.toLong() - offset
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    if (cursor >= payload.size) return -1
                    val count = minOf(len.toLong(), payload.size - cursor, length.takeIf { length >= 0 } ?: Long.MAX_VALUE).toInt()
                    if (count <= 0) return -1
                    System.arraycopy(payload, cursor.toInt(), buf, off, count)
                    cursor += count
                    return count
                }

                override suspend fun seek(position: Long) {
                    cursor = offset + position
                }

                override fun position(): Long = cursor - offset

                override fun close() = Unit
            }
        }

        override suspend fun write(path: String, data: InputStream) = Unit

        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

        override fun close() = Unit
    }
}
