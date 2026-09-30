package io.github.gua123.mediagate.data.storage.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException

/** 路径 / URL / Range / 日期 / 状态码这些**脱网纯逻辑**的单测（R2 / R4 / R8）。 */
class WebDavPathTest {

    @Test
    fun `路径段编码 空格中文井号百分号`() {
        assertEquals("a%20b.txt", encodePathSegment("a b.txt"))
        assertEquals("%E4%B8%AD%E6%96%87.txt", encodePathSegment("中文.txt"))
        assertEquals("a%231.txt", encodePathSegment("a#1.txt"))
        assertEquals("100%25.txt", encodePathSegment("100%.txt"))
        // '+' 是 RFC 3986 的 pchar，路径里原样保留（不能按表单语义当空格）
        assertEquals("a+b", encodePathSegment("a+b"))
        assertEquals("-._~", encodePathSegment("-._~"))
    }

    @Test
    fun `百分号解码 中文空格与加号`() {
        assertEquals("中文.txt", percentDecode("%E4%B8%AD%E6%96%87.txt"))
        assertEquals("a b", percentDecode("a%20b"))
        assertEquals("a+b", percentDecode("a+b")) // 加号不变成空格
        assertEquals("a+b", percentDecode("a%2Bb")) // %2B 解出来就是加号
        assertEquals("/dav/中文 目录/", percentDecode("/dav/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95/"))
    }

    @Test
    fun `百分号解码 非法序列原样保留`() {
        assertEquals("100%zz", percentDecode("100%zz"))
        assertEquals("tail%", percentDecode("tail%"))
    }

    @Test
    fun `归一化路径 首尾斜杠反斜杠点段与重复斜杠`() {
        assertEquals("", normalizeWebDavPath("/"))
        assertEquals("", normalizeWebDavPath(""))
        assertEquals("a/b", normalizeWebDavPath("/a//b/"))
        assertEquals("a/b", normalizeWebDavPath("a\\b"))
        assertEquals("a/b", normalizeWebDavPath("a/./b"))
        assertEquals("中文 目录/a.mkv", normalizeWebDavPath("/中文 目录/a.mkv"))
    }

    @Test
    fun `归一化路径 拒绝越界`() {
        try {
            normalizeWebDavPath("../etc/passwd")
            fail("应当抛 AccessDenied")
        } catch (e: StorageException.AccessDenied) {
            assertTrue(e.message!!.contains("越出根目录"))
        }
        try {
            normalizeWebDavPath("a/../../b")
            fail("应当抛 AccessDenied")
        } catch (_: StorageException.AccessDenied) {
        }
    }

    @Test
    fun `拼请求URL 逐段编码`() {
        assertEquals(
            "https://dav.example.com/media/a%20b/%E4%B8%AD%E6%96%87.mkv",
            buildFileUrl("https://dav.example.com/media", "a b/中文.mkv"),
        )
        // 已经是根：只回基址
        assertEquals("https://dav.example.com/media", buildFileUrl("https://dav.example.com/media", ""))
        // 井号必须编码，否则会被当成 fragment
        assertEquals("https://h/a%231.txt", buildFileUrl("https://h", "/a#1.txt"))
    }

    @Test
    fun `拼请求URL 集合补尾斜杠`() {
        assertEquals("https://h/dav/", buildFileUrl("https://h/dav", "", collection = true))
        assertEquals("https://h/dav/sub/", buildFileUrl("https://h/dav", "sub", collection = true))
        assertEquals("https://h/", buildFileUrl("https://h", "", collection = true))
    }

    @Test
    fun `href 转路径 剥掉 baseUrl 前缀`() {
        assertEquals("a.txt", hrefToPath("/dav/a.txt", "/dav"))
        assertEquals("sub/b.txt", hrefToPath("/dav/sub/b.txt", "/dav"))
        assertEquals("", hrefToPath("/dav", "/dav"))
        assertEquals("", hrefToPath("/dav/", "/dav"))
        assertEquals("中文 目录/x.mkv", hrefToPath("/dav/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95/x.mkv", "/dav"))
        // basePath 自己是百分号编码时，比较前要先解码
        assertEquals("a.txt", hrefToPath("/my%20dav/a.txt", percentDecode("/my%20dav")))
    }

    @Test
    fun `href 转路径 完整URL与查询串`() {
        assertEquals("a.txt", hrefToPath("https://dav.example.com:8080/dav/a.txt", "/dav"))
        assertEquals("a.txt", hrefToPath("https://dav.example.com/dav/a.txt?x=1", "/dav"))
        assertEquals("a.txt", hrefToPath("http://127.0.0.1:8080/dav/a.txt#frag", "/dav"))
    }

    @Test
    fun `href 转路径 服务器不回前缀时容错`() {
        // 少数服务器回相对 href，或压根不带 baseUrl 前缀：当成树内路径处理，不丢条目
        assertEquals("a.txt", stripBasePrefix("a.txt", "/dav"))
        assertEquals("other/a.txt", stripBasePrefix("/other/a.txt", "/dav"))
    }

    @Test
    fun `Range 头构造`() {
        assertEquals("bytes=0-0", buildRangeHeader(0, 1))
        assertEquals("bytes=100-199", buildRangeHeader(100, 100))
        assertEquals("bytes=200-", buildRangeHeader(200, -1))
        assertEquals("bytes=0-", buildRangeHeader(0, -1))
    }

    @Test
    fun `Content-Range 解析`() {
        assertEquals(ContentRange(100, 199, 1234), parseContentRange("bytes 100-199/1234"))
        assertEquals(ContentRange(0, 0, 10), parseContentRange("Bytes 0-0/10"))
        assertEquals(ContentRange(0, 9, -1), parseContentRange("bytes 0-9/*"))
        assertEquals(ContentRange(0, 9, 10), parseContentRange("bytes=0-9/10"))
        assertNull(parseContentRange("bytes */1234"))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("items 0-1/2"))
    }

    @Test
    fun `HTTP 日期解析`() {
        // 纪元起点是确定的，用它验证 RFC 1123 的解析正确性
        assertEquals(0L, parseHttpDate("Thu, 1 Jan 1970 00:00:00 GMT"))
        assertEquals(86_401_000L, parseHttpDate("Fri, 2 Jan 1970 00:00:01 GMT"))
        assertEquals(86_401_000L, parseHttpDate("1970-01-02T00:00:01Z"))
        // 假服务器用的固定时间戳，格式化后再解析要能回到原值
        val formatted = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(FakeWebDavServer.MODIFIED_AT))
        assertEquals(FakeWebDavServer.MODIFIED_AT, parseHttpDate(formatted))
        assertEquals(0L, parseHttpDate("看不懂的日期"))
        assertEquals(0L, parseHttpDate(null))
    }

    @Test
    fun `URL 拆解 host 与 path`() {
        assertEquals("dav.example.com", hostOf("https://dav.example.com:8080/dav"))
        assertEquals("192.168.1.10", hostOf("http://192.168.1.10:8080"))
        assertEquals("dav.example.com", hostOf("https://user:pass@dav.example.com/dav"))
        assertEquals("/dav", urlPathOf("https://dav.example.com:8080/dav"))
        assertEquals("/", urlPathOf("https://dav.example.com"))
        assertEquals("dav.example.com:8080", authorityOf("https://dav.example.com:8080/dav"))
    }

    @Test
    fun `请求基址 拼 rootPath`() {
        assertEquals("https://h/dav", buildRequestBaseUrl("https://h/dav/", "/"))
        assertEquals("https://h/dav/media", buildRequestBaseUrl("https://h/dav/", "media"))
        assertEquals("https://h", buildRequestBaseUrl("https://h", "/"))
    }

    @Test
    fun `目录优先排序与分页`() {
        val entries = listOf(
            RemoteEntry(name = "b.txt", path = "b.txt", size = 1),
            RemoteEntry(name = "A目录", path = "A目录", isDirectory = true),
            RemoteEntry(name = "a.txt", path = "a.txt", size = 2),
            RemoteEntry(name = "Z目录", path = "Z目录", isDirectory = true),
        ).sortedWith(DIRECTORY_FIRST)
        assertEquals(listOf("A目录", "Z目录", "a.txt", "b.txt"), entries.map { it.name })
        assertEquals(listOf("Z目录", "a.txt"), paginateWebDav(entries, Page(offset = 1, limit = 2)).map { it.name })
        assertEquals(listOf("a.txt", "b.txt"), paginateWebDav(entries, Page(offset = 2, limit = 0)).map { it.name })
        assertEquals(4, paginateWebDav(entries, null).size)
        assertTrue(paginateWebDav(entries, Page(offset = 99, limit = 2)).isEmpty())
    }

    @Test
    fun `状态码映射到语义异常`() {
        assertTrue(statusException(404, "x") is StorageException.NotFound)
        assertTrue(statusException(401, "x") is StorageException.Auth)
        assertTrue(statusException(403, "x") is StorageException.AccessDenied)
        assertTrue(statusException(405, "x") is StorageException.NotSupported)
        assertTrue(statusException(501, "x") is StorageException.NotSupported)
        assertTrue(statusException(500, "x") is StorageException.Network)
        assertTrue(statusException(503, "x") is StorageException.Network)
        assertTrue(statusException(418, "x") is StorageException.Unknown)
    }

    @Test
    fun `接口异常映射到网络类`() {
        assertTrue(mapIoFailure(java.net.UnknownHostException("h"), "x") is StorageException.Network)
        assertTrue(mapIoFailure(java.net.SocketTimeoutException("t"), "x") is StorageException.Network)
        assertTrue(mapIoFailure(java.net.ConnectException("c"), "x") is StorageException.Network)
        // 已经是 StorageException 的原样返回，不被包成 Network
        val original = StorageException.NotFound("nope")
        assertTrue(mapIoFailure(original, "x") === original)
    }
}
