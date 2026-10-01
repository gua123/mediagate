package io.github.gua123.mediagate.data.storage.webdav

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.ByteArrayInputStream

/** [WebDavStorageBackend.write] 的单测（R14 字幕写回视频同目录）。 */
class WebDavBackendWriteTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var backend: WebDavStorageBackend

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.addDir("dav")
        server.start()
        backend = WebDavStorageBackend(WebDavConfig(baseUrl = server.baseUrl + "/dav"))
    }

    @After
    fun tearDown() {
        backend.close()
        server.stop()
    }

    @Test
    fun `PUT 成功并带上 Content-Length`() = runTest {
        backend.write("影片.srt", ByteArrayInputStream(utf8("1\n00:00:01,000 --> 00:00:02,000\n字幕\n")))
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n字幕\n", text(server.files["dav/影片.srt"]!!))
        val request = server.requestsFor("PUT", "/dav/%E5%BD%B1%E7%89%87.srt").single()
        assertEquals("application/octet-stream", request.headers["content-type"])
        assertTrue(request.headers["content-length"]!!.toInt() > 0)
    }

    @Test
    fun `PUT 覆盖同名文件`() = runTest {
        backend.write("a.srt", ByteArrayInputStream(utf8("旧")))
        backend.write("a.srt", ByteArrayInputStream(utf8("新")))
        assertEquals("新", text(server.files["dav/a.srt"]!!))
        assertEquals(2, server.requestsFor("PUT", "/dav/a.srt").size)
    }

    @Test
    fun `PUT 403 抛 AccessDenied`() = runTest {
        server.readOnly = true
        try {
            backend.write("a.srt", ByteArrayInputStream(utf8("x")))
            fail("应当抛 AccessDenied（R14：上层据此落本地缓存）")
        } catch (e: StorageException.AccessDenied) {
            assertTrue(e.message!!.contains("a.srt"))
        }
    }

    @Test
    fun `父目录不存在 409 抛 NotFound`() = runTest {
        try {
            backend.write("不存在/文件.srt", ByteArrayInputStream(utf8("x")))
            fail("应当抛 NotFound")
        } catch (e: StorageException.NotFound) {
            assertTrue(e.message!!.contains("父目录"))
        }
    }

    @Test
    fun `写目录路径抛 AccessDenied`() = runTest {
        try {
            backend.write("子目录/", ByteArrayInputStream(utf8("x")))
            fail("应当抛 AccessDenied")
        } catch (_: StorageException.AccessDenied) {
        }
        try {
            backend.write("/", ByteArrayInputStream(utf8("x")))
            fail("应当抛 AccessDenied")
        } catch (_: StorageException.AccessDenied) {
        }
    }

    @Test
    fun `PUT 501 抛 NotSupported`() = runTest {
        server.forcedStatus["PUT /dav/a.srt"] = 501
        try {
            backend.write("a.srt", ByteArrayInputStream(utf8("x")))
            fail("应当抛 NotSupported")
        } catch (_: StorageException.NotSupported) {
        }
    }

    @Test
    fun `PUT 带 Basic 认证`() = runTest {
        server.requireAuthorization = "Basic dXNlcjpwYXNz"
        val authed = WebDavStorageBackend(
            WebDavConfig(baseUrl = server.baseUrl + "/dav", username = "user", password = "pass"),
        )
        try {
            authed.write("a.srt", ByteArrayInputStream(utf8("ok")))
            assertEquals("ok", text(server.files["dav/a.srt"]!!))
            assertEquals("Basic dXNlcjpwYXNz", server.requestsFor("PUT", "/dav/a.srt").single().headers["authorization"])
        } finally {
            authed.close()
        }
    }

    @Test
    fun `写更深一层目录需要父目录已存在`() = runTest {
        server.addDir("dav/sub")
        backend.write("sub/a.srt", ByteArrayInputStream(utf8("ok")))
        assertEquals("ok", text(server.files["dav/sub/a.srt"]!!))
    }
}
