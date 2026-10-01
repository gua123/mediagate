package io.github.gua123.mediagate.media.proxy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.local.FileStorageBackend
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [LoopbackHttpProxy] 的 JVM 集成测试（R4 拖拽 seek / R9 LibVLC 复用数据层 / plan 4.6）。
 *
 * 全链路真跑：真实文件 + [FileStorageBackend] + 真实回环 socket + 真实 HTTP 客户端
 * （JDK 的 [HttpURLConnection] 与原始 [Socket]），不 mock HTTP。
 *
 * 覆盖：全量 GET（200 + Content-Length + Accept-Ranges）、三种 Range（206 + Content-Range）、
 * 越界 416、HEAD、405、400、404（NotFound / 后端不匹配）、空文件、长度未知、大文件分段
 * 逐字节比对、两路并发、客户端中途断开释放底层流、close 后端口释放。
 */
class LoopbackHttpProxyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var backend: FileStorageBackend
    private lateinit var proxy: LoopbackHttpProxy

    /** 64 KiB 确定性内容（模 251，便于定位错位）。 */
    private val small = payload(64 * 1024)

    /** 1 MiB + 7 字节：不是任何缓冲区的整数倍，分段读最容易暴露边界错误。 */
    private val big = payload(1024 * 1024 + 7, seed = 3)

    @Before
    fun setUp() {
        root = tmp.newFolder("root")
        File(root, "a.bin").writePayload(small)
        File(root, "big.bin").writePayload(big)
        File(root, "empty.bin").writePayload(ByteArray(0))
        File(root, "song.mp3").writePayload(small)
        backend = FileStorageBackend(root)
        proxy = LoopbackHttpProxy(backend)
    }

    @After
    fun tearDown() {
        proxy.close()
    }

    // ------------------------------------------------------------ 形态

    @Test
    fun `只绑回环地址且端口由系统分配`() {
        assertTrue("端口必须是系统分配的有效端口", proxy.port > 0)
        assertEquals("http://127.0.0.1:" + proxy.port, proxy.baseUrl)
        assertTrue(proxy.isRunning)
        // 回环地址可连、且确实是本进程在监听
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", proxy.port), 5_000) }
    }

    @Test
    fun `播放地址由后端 id 与路径拼成`() {
        val url = proxy.playUrl(backend.id, "song.mp3")
        assertEquals(proxy.baseUrl + "/m/" + MediaUriCodec.format(backend.id, "song.mp3").removePrefix("mediagate://"), url)
        assertEquals(url, proxy.playUrlFor(MediaUriCodec.format(backend.id, "song.mp3")))
        assertNull(proxy.playUrlFor("file:///sdcard/song.mp3"))
    }

    // ------------------------------------------------------------ 200 全量

    @Test
    fun `全量 GET 返回 200 与完整内容`() {
        val conn = open(proxy.playUrl(backend.id, "a.bin"))
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
            assertEquals(small.size.toString(), conn.getHeaderField("Content-Length"))
            assertEquals("application/octet-stream", conn.getHeaderField("Content-Type"))
            assertArrayEquals(small, conn.inputStream.readBytes())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `按扩展名给出内容类型`() {
        val conn = open(proxy.playUrl(backend.id, "song.mp3"))
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("audio/mpeg", conn.getHeaderField("Content-Type"))
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `空文件返回 200 与零长度`() {
        val conn = open(proxy.playUrl(backend.id, "empty.bin"))
        try {
            assertEquals(200, conn.responseCode)
            assertEquals("0", conn.getHeaderField("Content-Length"))
            assertEquals(0, conn.inputStream.readBytes().size)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ Range → 206

    @Test
    fun `Range start-end 返回 206 与 Content-Range`() {
        val conn = open(proxy.playUrl(backend.id, "a.bin"), "bytes=10-19")
        try {
            assertEquals(206, conn.responseCode)
            assertEquals("bytes 10-19/" + small.size, conn.getHeaderField("Content-Range"))
            assertEquals("10", conn.getHeaderField("Content-Length"))
            assertArrayEquals(small.copyOfRange(10, 20), conn.inputStream.readBytes())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `Range start- 从中间读到文件末尾`() {
        val from = small.size - 7
        val conn = open(proxy.playUrl(backend.id, "a.bin"), "bytes=$from-")
        try {
            assertEquals(206, conn.responseCode)
            assertEquals("bytes $from-" + (small.size - 1) + "/" + small.size, conn.getHeaderField("Content-Range"))
            assertArrayEquals(small.copyOfRange(from, small.size), conn.inputStream.readBytes())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `Range -suffix 取文件尾部`() {
        val conn = open(proxy.playUrl(backend.id, "a.bin"), "bytes=-100")
        try {
            assertEquals(206, conn.responseCode)
            assertEquals("bytes " + (small.size - 100) + "-" + (small.size - 1) + "/" + small.size, conn.getHeaderField("Content-Range"))
            assertArrayEquals(small.copyOfRange(small.size - 100, small.size), conn.inputStream.readBytes())
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `Range 被翻译成后端的 offset 与 length`() {
        // 用真实后端跑一遍，再用替身后端核对 openRead 的入参口径
        val recording = FakeBackend(
            id = "fake:rec",
            entry = { RemoteEntry(name = it, path = it, size = 1000L) },
        ) { _, _, _ -> RecordingRangeStream(payload(1000), 1000L) }
        LoopbackHttpProxy(recording).use { fakeProxy ->
            val conn = open(fakeProxy.playUrl(recording.id, "x.bin"), "bytes=10-19")
            try {
                assertEquals(206, conn.responseCode)
                conn.inputStream.readBytes()
            } finally {
                conn.disconnect()
            }
            assertEquals("Range 必须翻译成 openRead(path, offset, length)", listOf(Triple("x.bin", 10L, 10L)), recording.opens)

            val full = open(fakeProxy.playUrl(recording.id, "x.bin"))
            try {
                assertEquals(200, full.responseCode)
                full.inputStream.readBytes()
            } finally {
                full.disconnect()
            }
            assertEquals("无 Range 时读到底", Triple("x.bin", 0L, -1L), recording.opens.last())
        }
    }

    @Test
    fun `越界 Range 返回 416 与总长信息`() {
        val conn = open(proxy.playUrl(backend.id, "a.bin"), "bytes=999999999-")
        try {
            assertEquals(416, conn.responseCode)
            assertEquals("bytes */" + small.size, conn.getHeaderField("Content-Range"))
            assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))
            assertTrue("416 应有错误说明", conn.errorStream.readBytes().isNotEmpty())
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ HEAD / 405 / 400 / 404

    @Test
    fun `HEAD 只回头部且不带 body`() {
        val head = splitHead(
            rawRequest(
                proxy.port,
                "HEAD " + ProxyUrls.format(proxy.baseUrl, backend.id, "a.bin").removePrefix(proxy.baseUrl) + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            ),
        )
        assertTrue("状态行应为 200：" + head.first.lineSequence().first(), head.first.startsWith("HTTP/1.1 200"))
        assertEquals(small.size.toString(), headerValue(head.first, "Content-Length"))
        assertEquals("bytes", headerValue(head.first, "Accept-Ranges"))
        assertEquals("HEAD 不能带 body", 0, head.second.size)
    }

    @Test
    fun `HEAD 带 Range 时给出 206 头部但不带 body`() {
        val target = ProxyUrls.format(proxy.baseUrl, backend.id, "a.bin").removePrefix(proxy.baseUrl)
        val head = splitHead(rawRequest(proxy.port, "HEAD $target HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-9\r\n\r\n"))
        assertTrue(head.first.startsWith("HTTP/1.1 206"))
        assertEquals("bytes 0-9/" + small.size, headerValue(head.first, "Content-Range"))
        assertEquals("10", headerValue(head.first, "Content-Length"))
        assertEquals(0, head.second.size)
    }

    @Test
    fun `HEAD 的错误响应同样不带 body`() {
        val target = ProxyUrls.format(proxy.baseUrl, backend.id, "missing.bin").removePrefix(proxy.baseUrl)
        val head = splitHead(rawRequest(proxy.port, "HEAD $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"))
        assertTrue(head.first.startsWith("HTTP/1.1 404"))
        assertTrue("404 也要给 Content-Length 说明", (headerValue(head.first, "Content-Length")?.toIntOrNull() ?: 0) > 0)
        assertEquals("HEAD 的 404 不能带 body", 0, head.second.size)
    }

    @Test
    fun `不支持的方法返回 405 与 Allow`() {
        val target = ProxyUrls.format(proxy.baseUrl, backend.id, "a.bin").removePrefix(proxy.baseUrl)
        val head = splitHead(
            rawRequest(proxy.port, "POST $target HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n"),
        )
        assertTrue(head.first.startsWith("HTTP/1.1 405"))
        assertEquals("GET, HEAD", headerValue(head.first, "Allow"))
    }

    @Test
    fun `路径前缀不对返回 400`() {
        val head = splitHead(rawRequest(proxy.port, "GET /not-proxy/a.bin HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"))
        assertTrue(head.first.startsWith("HTTP/1.1 400"))
    }

    @Test
    fun `后端不存在该文件返回 404`() {
        val conn = open(proxy.playUrl(backend.id, "missing.bin"))
        try {
            assertEquals(404, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `后端 id 不匹配返回 404`() {
        val conn = open(proxy.playUrl("local-file:/别处", "a.bin"))
        try {
            assertEquals(404, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `目录不能作为媒体流返回 404`() {
        File(root, "dir").mkdirs()
        val conn = open(proxy.playUrl(backend.id, "dir"))
        try {
            assertEquals(404, conn.responseCode)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `长度未知时退化为连接关闭界定的 200`() {
        val recording = FakeBackend(
            id = "fake:unknown",
            entry = { RemoteEntry(name = it, path = it, size = -1L) },
        ) { _, _, _ -> RecordingRangeStream(small, length = -1) }
        LoopbackHttpProxy(recording).use { fakeProxy ->
            val target = ProxyUrls.format(fakeProxy.baseUrl, recording.id, "x.bin").removePrefix(fakeProxy.baseUrl)
            val response = splitHead(rawRequest(fakeProxy.port, "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-9\r\n\r\n"))
            assertTrue(response.first.startsWith("HTTP/1.1 200"))
            assertNull("长度未知时不能给 Content-Length", headerValue(response.first, "Content-Length"))
            assertArrayEquals(small, response.second)
        }
    }

    // ------------------------------------------------------------ 大文件 / 并发

    @Test
    fun `大文件分 8 段 Range 逐字节与源文件一致`() {
        val chunks = 8
        val step = big.size / chunks
        val reassembled = java.io.ByteArrayOutputStream(big.size)
        for (i in 0 until chunks) {
            val start = i * step
            val end = if (i == chunks - 1) big.size - 1 else (start + step - 1)
            val conn = open(proxy.playUrl(backend.id, "big.bin"), "bytes=$start-$end")
            try {
                assertEquals("第 $i 段应为 206", 206, conn.responseCode)
                val body = conn.inputStream.readBytes()
                assertEquals("第 $i 段长度", end - start + 1, body.size)
                reassembled.write(body)
            } finally {
                conn.disconnect()
            }
        }
        assertArrayEquals("分段读回来的字节必须与源文件逐字节相同", big, reassembled.toByteArray())
    }

    @Test
    fun `两路并发全量下载互不干扰`() {
        val gate = CountDownLatch(1)
        val results = arrayOfNulls<ByteArray>(2)
        val failures = AtomicReference<Throwable?>(null)
        val threads = (0 until 2).map { index ->
            Thread {
                try {
                    gate.await(5, TimeUnit.SECONDS)
                    val conn = open(proxy.playUrl(backend.id, "big.bin"))
                    try {
                        assertEquals(200, conn.responseCode)
                        results[index] = conn.inputStream.readBytes()
                    } finally {
                        conn.disconnect()
                    }
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }.apply { isDaemon = true; start() }
        }
        gate.countDown()
        threads.forEach { it.join(30_000) }
        failures.get()?.let { throw AssertionError("并发下载失败", it) }
        assertArrayEquals(big, results[0])
        assertArrayEquals(big, results[1])
    }

    // ------------------------------------------------------------ 生命周期

    @Test
    fun `客户端中途断开时释放底层数据流`() {
        val body = payload(8 * 1024 * 1024)
        val stream = RecordingRangeStream(body, length = body.size.toLong())
        val recording = FakeBackend(
            id = "fake:abort",
            entry = { RemoteEntry(name = it, path = it, size = body.size.toLong()) },
        ) { _, _, _ -> stream }
        LoopbackHttpProxy(recording).use { fakeProxy ->
            val target = ProxyUrls.format(fakeProxy.baseUrl, recording.id, "x.bin").removePrefix(fakeProxy.baseUrl)
            requestThenAbort(fakeProxy.port, "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", drainBytes = 64 * 1024)
            assertTrue("客户端断开后底层 RangeStream 必须被 close", awaitTrue { stream.closed })
        }
    }

    @Test
    fun `close 幂等且释放端口`() {
        val port = proxy.port
        proxy.close()
        proxy.close()
        assertFalse(proxy.isRunning)
        // 端口真的放开了：能重新绑定同一个端口
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
            assertEquals(port, socket.localPort)
        }
        // 关闭后不再接受连接
        val refused = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue("close 后不应再接受连接", refused)
    }

    // ------------------------------------------------------------ 工具

    private fun open(url: String, range: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }
}
