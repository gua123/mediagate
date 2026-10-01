package io.github.gua123.mediagate.media.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.media.proxy.HttpRanges.RangeSpec

/**
 * 回环代理的**纯逻辑**单测：Range 解析、地址口径（MediaUriCodec / ProxyUrls）、
 * 请求头解析、Content-Type 猜测。这些是代理的行为契约，全部 JVM 可直接跑。
 *
 * 地址口径的金标字符串取自 [io.github.gua123.mediagate.media.playback.MediaUri] 的真实输出
 * （media:playback 依赖 media:engine，engine 又要依赖本模块，无法反向引用，故在此固化金标）。
 */
class ProxyPureLogicTest {

    // ------------------------------------------------------------ Range 解析

    @Test
    fun `没有 Range 头时全量返回`() {
        assertEquals(RangeSpec.Full, HttpRanges.resolve(null, 100))
        assertEquals(RangeSpec.Full, HttpRanges.resolve("", 100))
        assertEquals(RangeSpec.Full, HttpRanges.resolve("   ", 100))
    }

    @Test
    fun `bytes=start-end 解析成闭区间`() {
        val spec = HttpRanges.resolve("bytes=10-19", 100) as RangeSpec.Partial
        assertEquals(10L, spec.start)
        assertEquals(19L, spec.end)
        assertEquals("闭区间长度含两端", 10L, spec.length)
    }

    @Test
    fun `bytes=start- 一直读到文件末尾`() {
        val spec = HttpRanges.resolve("bytes=90-", 100) as RangeSpec.Partial
        assertEquals(90L, spec.start)
        assertEquals(99L, spec.end)
        assertEquals(10L, spec.length)
    }

    @Test
    fun `bytes=-suffix 取最后 N 字节`() {
        val spec = HttpRanges.resolve("bytes=-25", 100) as RangeSpec.Partial
        assertEquals(75L, spec.start)
        assertEquals(99L, spec.end)
        assertEquals(25L, spec.length)
    }

    @Test
    fun `end 超过文件长度时截到末尾`() {
        val spec = HttpRanges.resolve("bytes=95-100000", 100) as RangeSpec.Partial
        assertEquals(95L, spec.start)
        assertEquals(99L, spec.end)
    }

    @Test
    fun `suffix 大于文件长度时返回整个文件`() {
        val spec = HttpRanges.resolve("bytes=-1000", 100) as RangeSpec.Partial
        assertEquals(0L, spec.start)
        assertEquals(99L, spec.end)
    }

    @Test
    fun `起始偏移越界判为不可满足_416`() {
        assertEquals(RangeSpec.Unsatisfiable, HttpRanges.resolve("bytes=100-200", 100))
        assertEquals(RangeSpec.Unsatisfiable, HttpRanges.resolve("bytes=100-", 100))
        assertEquals("suffix 为 0 按 RFC 不可满足", RangeSpec.Unsatisfiable, HttpRanges.resolve("bytes=-0", 100))
    }

    @Test
    fun `空文件上的任何 Range 都不可满足`() {
        assertEquals(RangeSpec.Unsatisfiable, HttpRanges.resolve("bytes=0-0", 0))
        assertEquals(RangeSpec.Unsatisfiable, HttpRanges.resolve("bytes=-1", 0))
        assertEquals("无 Range 时空文件仍是 200", RangeSpec.Full, HttpRanges.resolve(null, 0))
    }

    @Test
    fun `多段 Range 与非法语法被忽略`() {
        assertEquals(RangeSpec.Full, HttpRanges.resolve("bytes=0-1,5-6", 100))
        assertEquals(RangeSpec.Full, HttpRanges.resolve("items=0-5", 100))
        assertEquals(RangeSpec.Full, HttpRanges.resolve("bytes=abc-def", 100))
        assertEquals(RangeSpec.Full, HttpRanges.resolve("bytes=5", 100))
        assertEquals(RangeSpec.Full, HttpRanges.resolve("bytes=", 100))
    }

    @Test
    fun `last 小于 first 的区间按 RFC 忽略`() {
        assertEquals(RangeSpec.Full, HttpRanges.resolve("bytes=50-10", 100))
    }

    @Test
    fun `长度未知时不使用 Range`() {
        assertEquals(RangeSpec.Full, HttpRanges.resolve("bytes=0-9", -1))
    }

    @Test
    fun `大小写与空白容错`() {
        val spec = HttpRanges.resolve("  Bytes= 5 - 9 ", 100) as RangeSpec.Partial
        assertEquals(5L, spec.start)
        assertEquals(9L, spec.end)
    }

    // ------------------------------------------------------------ 地址口径

    @Test
    fun `MediaUriCodec 输出与 playback 的 MediaUri 金标逐字一致`() {
        val cases = listOf(
            Triple("local-file:/storage/emulated/0", "Music/歌曲 01.mp3", "mediagate://local-file%3A%2Fstorage%2Femulated%2F0/Music/%E6%AD%8C%E6%9B%B2%2001.mp3"),
            Triple("local-file:/storage/emulated/0", "a/b#c?d%e.mp4", "mediagate://local-file%3A%2Fstorage%2Femulated%2F0/a/b%23c%3Fd%25e.mp4"),
            Triple("webdav:https://dav.example.com/dav", "/movies/影 片/预告(1).ts", "mediagate://webdav%3Ahttps%3A%2F%2Fdav.example.com%2Fdav//movies/%E5%BD%B1%20%E7%89%87/%E9%A2%84%E5%91%8A%281%29.ts"),
            Triple("sftp:user@host:22", "dir/100%.mkv", "mediagate://sftp%3Auser%40host%3A22/dir/100%25.mkv"),
            Triple("local-file:/root", "", "mediagate://local-file%3A%2Froot/"),
            Triple("local-file:/root", "a//b/", "mediagate://local-file%3A%2Froot/a//b/"),
            Triple("后端:中文/斜杠", "路径/带/斜杠.txt", "mediagate://%E5%90%8E%E7%AB%AF%3A%E4%B8%AD%E6%96%87%2F%E6%96%9C%E6%9D%A0/%E8%B7%AF%E5%BE%84/%E5%B8%A6/%E6%96%9C%E6%9D%A0.txt"),
            Triple("local-file:/x", "emoji 🎬/file.mp4", "mediagate://local-file%3A%2Fx/emoji%20%F0%9F%8E%AC/file.mp4"),
            Triple("local-file:/x", "weird%2Fslash.txt", "mediagate://local-file%3A%2Fx/weird%252Fslash.txt"),
            Triple("ftp:192.168.1.10:21", "媒体/电视剧/S01E01.m2ts", "mediagate://ftp%3A192.168.1.10%3A21/%E5%AA%92%E4%BD%93/%E7%94%B5%E8%A7%86%E5%89%A7/S01E01.m2ts"),
        )
        for ((backendId, path, golden) in cases) {
            assertEquals("format($backendId, $path)", golden, MediaUriCodec.format(backendId, path))
            assertEquals(
                "parse 往返",
                MediaUriCodec.Parsed(backendId, path),
                MediaUriCodec.parse(golden),
            )
        }
    }

    @Test
    fun `解析非法伪 URI 返回 null 而不抛异常`() {
        assertNull(MediaUriCodec.parse("file:///sdcard/a.mp3"))
        assertNull(MediaUriCodec.parse("mediagate://"))
        assertNull(MediaUriCodec.parse(""))
        assertTrue(MediaUriCodec.isMediaUri(MediaUriCodec.format("id", "a.mp4")))
    }

    @Test
    fun `段内的百分号编码斜杠不会被当成路径分隔符`() {
        // 文件名里带字面量 %2F：编码后是 %252F，解码必须还原成一段，而不是切成两级目录
        val uri = MediaUriCodec.format("local-file:/x", "a%2Fb/c.mp4")
        assertEquals("mediagate://local-file%3A%2Fx/a%252Fb/c.mp4", uri)
        val parsed = MediaUriCodec.parse(uri)!!
        assertEquals("a%2Fb/c.mp4", parsed.path)
        assertEquals(2, parsed.path.split('/').size)
    }

    @Test
    fun `ProxyUrls 把伪 URI 映射成回环地址并可逆`() {
        val base = "http://127.0.0.1:45678"
        val url = ProxyUrls.format(base, "local-file:/storage/emulated/0", "Music/歌曲 01.mp3")
        assertEquals(
            "http://127.0.0.1:45678/m/local-file%3A%2Fstorage%2Femulated%2F0/Music/%E6%AD%8C%E6%9B%B2%2001.mp3",
            url,
        )
        assertEquals(
            MediaUriCodec.Parsed("local-file:/storage/emulated/0", "Music/歌曲 01.mp3"),
            ProxyUrls.parse(url.removePrefix(base)),
        )
        assertEquals(url, ProxyUrls.formatFor(base, MediaUriCodec.format("local-file:/storage/emulated/0", "Music/歌曲 01.mp3")))
        assertNull(ProxyUrls.formatFor(base, "not-a-media-uri"))
    }

    @Test
    fun `ProxyUrls 忽略查询串并拒绝前缀不符的目标`() {
        val base = "http://127.0.0.1:1"
        val target = ProxyUrls.format(base, "b", "p.mp4").removePrefix(base) + "?token=1#frag"
        assertEquals(MediaUriCodec.Parsed("b", "p.mp4"), ProxyUrls.parse(target))
        assertNull(ProxyUrls.parse("/other/b/p.mp4"))
        assertNull(ProxyUrls.parse("/m/"))
    }

    // ------------------------------------------------------------ 请求头解析

    @Test
    fun `请求头解析大小写不敏感且重复字段取首个`() {
        val head = HttpRequestHead.parseLines(
            listOf(
                "GET /m/local-file%3A%2Fx/a.mp4 HTTP/1.1",
                "Host: 127.0.0.1:1234",
                "Range: bytes=0-9",
                "range: bytes=5-6",
                "没有冒号的行",
                "Accept: */*",
            ),
        )!!
        assertEquals("GET", head.method)
        assertEquals("/m/local-file%3A%2Fx/a.mp4", head.target)
        assertEquals("HTTP/1.1", head.version)
        assertEquals("bytes=0-9", head.header("Range"))
        assertEquals("bytes=0-9", head.header("RANGE"))
        assertEquals("*/*", head.header("accept"))
        assertNull(head.header("missing"))
    }

    @Test
    fun `畸形请求行解析为 null`() {
        assertNull(HttpRequestHead.parseLines(emptyList()))
        assertNull(HttpRequestHead.parseLines(listOf("GET /m/x")))
        assertNull(HttpRequestHead.parseLines(listOf("GET /m/x FTP/1.1")))
        assertNull(HttpRequestHead.parseLines(listOf("   ")))
    }

    // ------------------------------------------------------------ Content-Type

    @Test
    fun `按扩展名猜测内容类型且不猜错`() {
        assertEquals("video/mp4", HttpContentTypes.of("a/b/c.MP4"))
        assertEquals("video/mp2t", HttpContentTypes.of("录制/节目.ts"))
        assertEquals("video/x-matroska", HttpContentTypes.of("电影.mkv"))
        assertEquals("audio/flac", HttpContentTypes.of("x.flac"))
        assertEquals("application/vnd.apple.mpegurl", HttpContentTypes.of("index.m3u8"))
        assertEquals(HttpContentTypes.DEFAULT, HttpContentTypes.of("无扩展名"))
        assertEquals(HttpContentTypes.DEFAULT, HttpContentTypes.of("a.unknownext"))
        assertEquals(HttpContentTypes.DEFAULT, HttpContentTypes.of(".hidden"))
    }
}
