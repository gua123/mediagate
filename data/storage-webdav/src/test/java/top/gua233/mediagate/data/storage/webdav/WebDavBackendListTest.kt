package io.github.gua123.mediagate.data.storage.webdav

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * [WebDavStorageBackend.list] / [WebDavStorageBackend.stat] 的单测（R2 列目录 / R8 错误分类）。
 *
 * 全部打本机假 WebDAV 服务器（[FakeWebDavServer]），不碰真实网络。
 */
class WebDavBackendListTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var backend: WebDavStorageBackend

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.addDir("dav")
        server.addFile("dav/a.txt", "aaa")
        server.addFile("dav/B文件.txt", "bb")
        server.addDir("dav/子目录")
        server.addFile("dav/子目录/inner.mkv", "0123456789")
        server.start()
        backend = WebDavStorageBackend(WebDavConfig(baseUrl = server.baseUrl + "/dav"))
    }

    @After
    fun tearDown() {
        backend.close()
        server.stop()
    }

    @Test
    fun `列目录 目录优先加名称排序且过滤自身`() = runTest {
        val entries = backend.list("")
        assertEquals(listOf("子目录", "a.txt", "B文件.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals(-1L, entries[0].size)
        assertFalse(entries[1].isDirectory)
        assertEquals(3L, entries[1].size)
        assertEquals("a.txt", entries[1].path)
        assertEquals(FakeWebDavServer.MODIFIED_AT, entries[1].mtime)
        assertEquals("\"etag:dav/a.txt\"", entries[1].etag)
        assertEquals("application/octet-stream", entries[1].mimeType)
    }

    @Test
    fun `列目录 中文空格井号文件名`() = runTest {
        server.addFile("dav/中文 名字#1.txt", "hello")
        val entry = backend.list("").first { it.name == "中文 名字#1.txt" }
        assertEquals("中文 名字#1.txt", entry.path)
        assertEquals(5L, entry.size)
    }

    @Test
    fun `列嵌套目录 路径是树内相对路径`() = runTest {
        val entries = backend.list("子目录")
        assertEquals(1, entries.size)
        assertEquals("inner.mkv", entries[0].name)
        assertEquals("子目录/inner.mkv", entries[0].path)
        assertEquals(10L, entries[0].size)
        assertEquals("1", server.requestsFor("PROPFIND", "/dav/%E5%AD%90%E7%9B%AE%E5%BD%95/").single().depth)
    }

    @Test
    fun `分页 offset 与 limit`() = runTest {
        assertEquals(listOf("a.txt", "B文件.txt"), backend.list("", Page(offset = 1, limit = 2)).map { it.name })
        assertEquals(listOf("B文件.txt"), backend.list("", Page(offset = 2, limit = 2)).map { it.name })
        assertEquals(3, backend.list("", Page(offset = 0, limit = 0)).size)
        assertTrue(backend.list("", Page(offset = 9, limit = 2)).isEmpty())
    }

    @Test
    fun `PROPFIND 请求体规范且 Depth 正确`() = runTest {
        backend.list("")
        val request = server.requestsFor("PROPFIND", "/dav/").single()
        assertEquals("<D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/><D:getcontentlength/>" +
            "<D:getlastmodified/><D:getetag/><D:getcontenttype/></D:prop></D:propfind>", request.body)
        assertEquals("1", request.depth)
        assertEquals("application/xml; charset=utf-8", request.headers["content-type"])
    }

    @Test
    fun `stat 文件`() = runTest {
        val entry = backend.stat("a.txt")
        assertEquals("a.txt", entry.name)
        assertEquals("a.txt", entry.path)
        assertFalse(entry.isDirectory)
        assertEquals(3L, entry.size)
        assertEquals(FakeWebDavServer.MODIFIED_AT, entry.mtime)
        // Depth: 0 且不带尾斜杠
        assertEquals("0", server.requestsFor("PROPFIND", "/dav/a.txt").single().depth)
    }

    @Test
    fun `stat 目录与根目录`() = runTest {
        val dir = backend.stat("子目录")
        assertTrue(dir.isDirectory)
        assertEquals(-1L, dir.size)
        assertEquals("子目录", dir.name)

        val root = backend.stat("")
        assertTrue(root.isDirectory)
        assertEquals("dav", root.name) // 根名字取 basePath 末段
        assertEquals("", root.path)
    }

    @Test
    fun `stat 404 抛 NotFound`() = runTest {
        try {
            backend.stat("nope.txt")
            fail("应当抛 NotFound")
        } catch (e: StorageException.NotFound) {
            assertTrue(e.message!!.contains("nope.txt"))
        }
    }

    @Test
    fun `list 404 抛 NotFound`() = runTest {
        try {
            backend.list("nope")
            fail("应当抛 NotFound")
        } catch (_: StorageException.NotFound) {
        }
    }

    @Test
    fun `list 405 抛 NotSupported`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 405
        try {
            backend.list("")
            fail("应当抛 NotSupported")
        } catch (e: StorageException.NotSupported) {
            assertTrue(e.message!!.contains("405"))
        }
    }

    @Test
    fun `list 401 抛 Auth`() = runTest {
        server.requireAuthorization = "Basic bm9ib2R5Om5vcGFzcw=="
        try {
            backend.list("")
            fail("应当抛 Auth")
        } catch (e: StorageException.Auth) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `list 403 抛 AccessDenied`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 403
        try {
            backend.list("")
            fail("应当抛 AccessDenied")
        } catch (_: StorageException.AccessDenied) {
        }
    }

    @Test
    fun `list 500 归网络类`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 500
        try {
            backend.list("")
            fail("应当抛 Network")
        } catch (_: StorageException.Network) {
        }
    }

    @Test
    fun `list 文件路径抛 NotSupported`() = runTest {
        try {
            backend.list("a.txt")
            fail("应当抛 NotSupported")
        } catch (e: StorageException.NotSupported) {
            assertTrue(e.message!!.contains("不是目录"))
        }
    }

    @Test
    fun `href 是完整 URL 时也能列目录`() = runTest {
        server.hrefStyle = HrefStyle.FULL_URL
        assertEquals(listOf("子目录", "a.txt", "B文件.txt"), backend.list("").map { it.name })
        assertEquals("子目录/inner.mkv", backend.list("子目录").single().path)
    }

    @Test
    fun `前缀写成小写或省略也能解析`() = runTest {
        server.xmlPrefix = "d"
        assertEquals(3, backend.list("").size)
        server.xmlPrefix = ""
        assertEquals(3, backend.list("").size)
    }

    @Test
    fun `只认状态 200 的 propstat`() = runTest {
        server.includeNotFoundPropstat = true
        server.notFoundPropstatLength = 999_999L
        val entry = backend.stat("a.txt")
        assertEquals(3L, entry.size)
    }

    @Test
    fun `集合尾斜杠 301 重定向仍用 PROPFIND 跟随`() = runTest {
        // Apache 对不带尾斜杠的集合就是回 301 → /dav/；OkHttp 的自动跟随会把 PROPFIND 改成 GET
        server.redirects["/dav"] = "/dav/"
        val root = backend.stat("")
        assertTrue(root.isDirectory)
        assertEquals("dav", root.name)
        val propfinds = server.requests.filter { it.method == "PROPFIND" }
        assertEquals(2, propfinds.size)
        assertEquals("/dav", propfinds[0].rawPath)
        assertEquals("/dav/", propfinds[1].rawPath)
        assertTrue(server.requests.none { it.method == "GET" }) // 绝不能被降级成 GET
    }
}
