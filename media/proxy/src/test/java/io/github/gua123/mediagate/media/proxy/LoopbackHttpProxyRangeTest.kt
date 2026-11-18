package io.github.gua123.mediagate.media.proxy

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * L4 回环代理层测试（2026-11-18）。
 *
 * 代理是「播放器 ↔ 存储层」的中间层：VLC 每个拖动都发 Range 请求，代理答错一个字节，
 * 上层就是花屏/卡死，而且极难从日志看出来。这里用内存假后端把它钉死。
 */
class LoopbackHttpProxyRangeTest {

    private val data = ByteArray(SIZE) { (it * 31 % 251).toByte() }
    private val backend = FakeBackend(data)
    private lateinit var proxy: LoopbackHttpProxy

    @Before
    fun setUp() {
        proxy = LoopbackHttpProxy(backend)
    }

    @After
    fun tearDown() {
        runCatching { proxy.close() }
    }

    private fun open(range: String?): HttpURLConnection {
        val url = URL(proxy.playUrl("fake", "video.mp4"))
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 5000
        c.readTimeout = 10000
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private fun bodyOf(c: HttpURLConnection): ByteArray =
        c.inputStream.use { it.readBytes() }

    @Test
    fun 无Range返回200与全量() {
        val c = open(null)
        assertEquals(200, c.responseCode)
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertEquals(SIZE.toLong(), c.getHeaderField("Content-Length")!!.toLong())
        val body = bodyOf(c)
        assertEquals(SIZE, body.size)
        assertTrue("首字节应一致", body[0] == data[0])
    }

    @Test
    fun 单段Range返回206且字节精确() {
        val from = 1000
        val to = 1999
        val c = open("bytes=" + from + "-" + to)
        assertEquals(206, c.responseCode)
        assertEquals("bytes " + from + "-" + to + "/" + SIZE, c.getHeaderField("Content-Range"))
        val body = bodyOf(c)
        assertEquals(to - from + 1, body.size)
        // 逐字节比对，这是最关键的一条
        for (i in body.indices) {
            assertEquals("第 " + i + " 字节不一致", data[from + i], body[i])
        }
    }

    @Test
    fun 开放结尾Range() {
        val from = SIZE - 100
        val c = open("bytes=" + from + "-")
        assertEquals(206, c.responseCode)
        val body = bodyOf(c)
        assertEquals(100, body.size)
        assertEquals(data[from], body[0])
    }

    @Test
    fun 后缀Range取末尾() {
        val c = open("bytes=-100")
        assertEquals(206, c.responseCode)
        val body = bodyOf(c)
        assertEquals(100, body.size)
        assertEquals(data[SIZE - 100], body[0])
    }

    @Test
    fun 越界Range返回416() {
        val c = open("bytes=" + (SIZE + 10) + "-" + (SIZE + 20))
        assertEquals(416, c.responseCode)
    }

    @Test
    fun 非法方法返回405() {
        val url = URL(proxy.playUrl("fake", "video.mp4"))
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        assertEquals(405, c.responseCode)
    }

    @Test
    fun HEAD不带body但头一致() {
        val url = URL(proxy.playUrl("fake", "video.mp4"))
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        assertEquals(200, c.responseCode)
        assertEquals(SIZE.toLong(), c.getHeaderField("Content-Length")!!.toLong())
    }

    @Test
    fun token形态地址可播放特殊字符路径() {
        val url = URL(proxy.playUrlSafe("fake", "目录/#Akari 2024.8.3.mp4"))
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=0-99")
        assertEquals(206, c.responseCode)
        assertEquals(100, bodyOf(c).size)
    }

    private companion object {
        const val SIZE = 1024 * 1024
    }

    /** 内存假后端：只服务一个文件，随机读语义与真实后端一致。 */
    private class FakeBackend(private val data: ByteArray) : StorageBackend {
        override val id: String = "fake"
        override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 4)

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> =
            listOf(RemoteEntry(name = "video.mp4", path = "video.mp4", isDirectory = false, size = data.size.toLong(), mtime = 0L))

        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = "video.mp4", path = path, isDirectory = false, size = data.size.toLong(), mtime = 0L)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
            val start = offset.coerceAtLeast(0L)
            val endExclusive = if (length < 0L) data.size.toLong() else minOf(data.size.toLong(), start + length)
            val slice = data.copyOfRange(start.toInt(), endExclusive.toInt())
            return object : RangeStream {
                private var pos = 0
                override val length: Long = slice.size.toLong()
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    if (pos >= slice.size) return -1
                    val n = minOf(len, slice.size - pos)
                    System.arraycopy(slice, pos, buf, off, n)
                    pos += n
                    return n
                }
                override suspend fun seek(position: Long) { pos = position.toInt() }
                override fun position(): Long = pos.toLong()
                override fun close() {}
            }
        }

        override suspend fun write(path: String, data: InputStream) = Unit
        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)
        override fun close() {}
    }
}
