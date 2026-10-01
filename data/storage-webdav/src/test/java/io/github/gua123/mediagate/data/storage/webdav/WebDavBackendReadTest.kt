package io.github.gua123.mediagate.data.storage.webdav

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.api.asRandomAccessSource

/**
 * [WebDavStorageBackend.openRead] / [WebDavRangeStream] 的单测（R4 拖拽随机读，plan 4.1）。
 *
 * 覆盖：Range 头构造、206 正常读、服务器忽略 Range 回 200 时的本地跳过与 caps 降级、
 * length = -1 读到 EOF、每次 seek 新发一个 Range 请求、路径编码、目录 / 404 的处理。
 */
class WebDavBackendReadTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var backend: WebDavStorageBackend

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.addDir("dav")
        server.addFile("dav/data.bin", "0123456789")
        server.addDir("dav/子目录")
        server.start()
        backend = WebDavStorageBackend(WebDavConfig(baseUrl = server.baseUrl + "/dav"))
    }

    @After
    fun tearDown() {
        backend.close()
        server.stop()
    }

    @Test
    fun `openRead 带 offset 与 length 时 Range 头正确`() = runTest {
        backend.openRead("data.bin", offset = 2, length = 4).use { stream ->
            assertEquals(4L, stream.length)
            assertEquals(0L, stream.position())
            assertEquals("2345", text(readAll(stream)))
            assertEquals(4L, stream.position())
        }
        val request = server.requestsFor("GET", "/dav/data.bin").single()
        assertEquals("bytes=2-5", request.range)
        assertEquals("identity", request.headers["accept-encoding"])
    }

    @Test
    fun `openRead 默认从头读全量且 length 已知`() = runTest {
        backend.openRead("data.bin").use { stream ->
            assertEquals(10L, stream.length)
            assertEquals("0123456789", text(readAll(stream)))
            assertEquals(-1, stream.read(ByteArray(4), 0, 4)) // 末尾之后仍是 -1
        }
        assertEquals("bytes=0-", server.requestsFor("GET", "/dav/data.bin").single().range)
    }

    @Test
    fun `length 为 -1 时一路读到 EOF`() = runTest {
        server.addFile("dav/big.bin", "abcdefghijklmnopqrstuvwxyz")
        backend.openRead("big.bin", offset = 20, length = -1).use { stream ->
            assertEquals(6L, stream.length)
            assertEquals("uvwxyz", text(readAll(stream)))
        }
        assertEquals("bytes=20-", server.requestsFor("GET", "/dav/big.bin").single().range)
    }

    @Test
    fun `seek 之后重新发一个 Range 请求`() = runTest {
        backend.openRead("data.bin").use { stream ->
            val head = ByteArray(3)
            assertEquals(3, stream.read(head, 0, 3))
            assertEquals("012", text(head))

            stream.seek(5)
            assertEquals(5L, stream.position())
            val tail = ByteArray(3)
            assertEquals(3, stream.read(tail, 0, 3))
            assertEquals("567", text(tail))
        }
        val requests = server.requestsFor("GET", "/dav/data.bin")
        assertEquals(2, requests.size) // 每次 seek 都是一个新的 Range 请求（plan 4.1）
        assertEquals("bytes=0-", requests[0].range)
        assertEquals("bytes=5-", requests[1].range)
    }

    @Test
    fun `有界流 seek 后 Range 是剩余区间`() = runTest {
        backend.openRead("data.bin", offset = 0, length = 4).use { stream ->
            stream.seek(2)
            val buffer = ByteArray(4)
            assertEquals(2, stream.read(buffer, 0, 4))
            assertEquals("23", text(buffer.copyOf(2)))
        }
        assertEquals("bytes=2-3", server.requestsFor("GET", "/dav/data.bin").last().range)
    }

    @Test
    fun `服务器忽略 Range 回 200 时本地跳过 offset`() = runTest {
        server.ignoresRange = true
        backend.openRead("data.bin", offset = 4, length = 3).use { stream ->
            assertEquals("456", text(readAll(stream)))
            assertEquals(3L, stream.length)
        }
        // 我们照样发 Range，是服务器没理会
        assertEquals("bytes=4-6", server.requestsFor("GET", "/dav/data.bin").single().range)
        // 探测到「忽略 Range」后 caps 必须降级（plan 4.1 的降级链）
        assertFalse(backend.caps.randomAccess)
        assertFalse(backend.caps.rangeHeader)
    }

    @Test
    fun `忽略 Range 的服务器上 seek 也能读对`() = runTest {
        server.ignoresRange = true
        backend.openRead("data.bin").use { stream ->
            stream.seek(7)
            val buffer = ByteArray(3)
            assertEquals(3, stream.read(buffer, 0, 3))
            assertEquals("789", text(buffer))
        }
        assertEquals(2, server.requestsFor("GET", "/dav/data.bin").size)
    }

    @Test
    fun `Accept-Ranges none 时 caps 降级`() = runTest {
        server.rangeSupport = false
        assertTrue(backend.caps.randomAccess) // 还没探测过：乐观
        backend.stat("data.bin")
        assertFalse(backend.caps.randomAccess)
    }

    @Test
    fun `206 响应后 caps 仍然声明支持随机读`() = runTest {
        backend.openRead("data.bin", offset = 1, length = 2).use { readAll(it) }
        assertTrue(backend.caps.randomAccess)
        assertTrue(backend.caps.rangeHeader)
        assertEquals(4, backend.caps.maxParallelReads)
        assertTrue(backend.caps.writable && !backend.caps.resumeByRest)
    }

    @Test
    fun `seek 到末尾之后 read 返回 -1 且不再发请求`() = runTest {
        backend.openRead("data.bin", offset = 0, length = 4).use { stream ->
            stream.seek(4)
            assertEquals(-1, stream.read(ByteArray(2), 0, 2))
            stream.seek(99) // 越界被夹到流末尾
            assertEquals(4L, stream.position())
        }
        assertEquals(1, server.requestsFor("GET", "/dav/data.bin").size)
    }

    @Test
    fun `length 为 0 时给空流且不发请求`() = runTest {
        backend.openRead("data.bin", offset = 0, length = 0).use { stream ->
            assertEquals(0L, stream.length)
            assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        }
        assertTrue(server.requestsFor("GET", "/dav/data.bin").isEmpty())
    }

    @Test
    fun `偏移超过文件尾 416 时读到 EOF`() = runTest {
        backend.openRead("data.bin", offset = 100, length = 5).use { stream ->
            assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        }
        assertTrue(server.requestsFor("GET", "/dav/data.bin").isNotEmpty())
    }

    @Test
    fun `openRead 404 抛 NotFound`() = runTest {
        try {
            backend.openRead("nope.bin")
            fail("应当抛 NotFound")
        } catch (e: StorageException.NotFound) {
            assertTrue(e.message!!.contains("nope.bin"))
        }
    }

    @Test
    fun `对目录 openRead 抛 NotSupported`() = runTest {
        try {
            backend.openRead("子目录")
            fail("应当抛 NotSupported")
        } catch (e: StorageException.NotSupported) {
            assertTrue(e.message!!.contains("目录"))
        }
    }

    @Test
    fun `目录返回 405 时也是 NotSupported`() = runTest {
        server.directoryGetStatus = 405
        try {
            backend.openRead("子目录")
            fail("应当抛 NotSupported")
        } catch (_: StorageException.NotSupported) {
        }
    }

    @Test
    fun `路径带空格中文井号时请求 URL 编码正确`() = runTest {
        server.addFile("dav/中文 名字#1.txt", "hello")
        backend.openRead("中文 名字#1.txt").use { stream ->
            assertEquals("hello", text(readAll(stream)))
        }
        val request = server.requestsFor("GET", "/dav/%E4%B8%AD%E6%96%87%20%E5%90%8D%E5%AD%97%231.txt").single()
        assertEquals("bytes=0-", request.range)
    }

    @Test
    fun `读流关闭后继续读会报错`() = runTest {
        val stream = backend.openRead("data.bin")
        stream.close()
        try {
            stream.read(ByteArray(1), 0, 1)
            fail("应当抛 Unknown")
        } catch (_: StorageException.Unknown) {
        }
    }

    @Test
    fun `可以直接适配成 RandomAccessSource`() = runTest {
        backend.openRead("data.bin").asRandomAccessSource().use { source ->
            assertEquals(10L, source.size)
            assertEquals("5678", text(source.readFully(5, 4)))
            assertEquals("012", text(source.readFully(0, 3)))
            assertEquals(-1, source.readAt(10, ByteArray(1), 0, 1))
        }
    }

    @Test
    fun `带 Basic 认证与自定义头`() = runTest {
        server.requireAuthorization = "Basic dXNlcjpwYXNz"
        server.rangeSupport = true
        val authed = WebDavStorageBackend(
            WebDavConfig(
                baseUrl = server.baseUrl + "/dav",
                username = "user",
                password = "pass",
                extraHeaders = mapOf("X-Test" to "1"),
            ),
        )
        try {
            authed.openRead("data.bin", offset = 0, length = 2).use { assertEquals("01", text(readAll(it))) }
            val request = server.requestsFor("GET", "/dav/data.bin").single()
            assertEquals("Basic dXNlcjpwYXNz", request.headers["authorization"])
            assertEquals("1", request.headers["x-test"])
        } finally {
            authed.close()
        }
    }
}
