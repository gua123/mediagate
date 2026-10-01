package io.github.gua123.mediagate.data.storage.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException

/** 207 multistatus 解析的单测：命名空间无关是重点（服务器前缀怎么写都要认）。 */
class DavMultistatusTest {

    /** 假服务器那个固定时间戳的 RFC 1123 写法（保证 mtime 断言是往返一致的）。 */
    private val dateText: String = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(FakeWebDavServer.MODIFIED_AT))

    private val twoResponses = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/%E4%B8%AD%E6%96%87/a.txt</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>12</D:getcontentlength>
                <D:getlastmodified>$dateText</D:getlastmodified>
                <D:getetag>"abc"</D:getetag>
                <D:getcontenttype>text/plain</D:getcontenttype>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/sub/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                <D:getcontentlength>0</D:getcontentlength>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun `解析文件与目录条目`() {
        val entries = parseMultistatus(twoResponses.toByteArray())
        assertEquals(2, entries.size)
        assertEquals("/dav/%E4%B8%AD%E6%96%87/a.txt", entries[0].href)
        assertEquals(false, entries[0].isDirectory)
        assertEquals(12L, entries[0].size)
        assertEquals(FakeWebDavServer.MODIFIED_AT, entries[0].mtime)
        assertEquals("\"abc\"", entries[0].etag)
        assertEquals("text/plain", entries[0].mimeType)
        assertTrue(entries[1].isDirectory)
        assertEquals(-1L, entries[1].size) // 目录恒 -1，不用 0 冒充未知
    }

    @Test
    fun `前缀小写或省略都能解析`() {
        val lower = twoResponses.replace("D:", "d:")
        assertEquals(2, parseMultistatus(lower.toByteArray()).size)
        val noPrefix = twoResponses
            .replace("<D:", "<")
            .replace("</D:", "</")
            .replace(" xmlns:D=\"DAV:\"", " xmlns=\"DAV:\"")
        val entries = parseMultistatus(noPrefix.toByteArray())
        assertEquals(2, entries.size)
        assertTrue(entries[1].isDirectory)
    }

    @Test
    fun `只认 200 的 propstat 忽略 404`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/a.txt</D:href>
                <D:propstat>
                  <D:prop><D:getcontentlength>7</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop><D:getcontentlength>999999</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = parseMultistatus(xml.toByteArray())
        assertEquals(1, entries.size)
        assertEquals(7L, entries[0].size)
    }

    @Test
    fun `空响应与空属性不炸`() {
        assertTrue(parseMultistatus(ByteArray(0)).isEmpty())
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/empty.txt</D:href>
                <D:propstat><D:prop><D:getcontentlength/><D:getlastmodified>bad-date</D:getlastmodified></D:prop></D:propstat>
              </D:response>
              <D:response><D:propstat><D:prop/></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = parseMultistatus(xml.toByteArray())
        assertEquals(1, entries.size) // 没有 href 的条目直接跳过
        assertEquals(-1L, entries[0].size)
        assertEquals(0L, entries[0].mtime)
    }

    @Test
    fun `根节点不对时报 Unknown`() {
        try {
            parseMultistatus("<html><body>not dav</body></html>".toByteArray())
            fail("应当抛 Unknown")
        } catch (e: StorageException.Unknown) {
            assertTrue(e.message!!.contains("multistatus"))
        }
    }

    @Test
    fun `坏 XML 报 Unknown`() {
        try {
            parseMultistatus("<D:multistatus".toByteArray())
            fail("应当抛 Unknown")
        } catch (_: StorageException.Unknown) {
        }
    }
}
