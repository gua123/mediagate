package io.github.gua123.mediagate.media.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaUri] 的 JVM 单测（R4/R18）：伪 URI 的形态、可逆性与容错。
 *
 * 这一层的正确性是 [BackendDataSource] 的前提——设备上 DataSpec 里的路径全靠它还原。
 */
class MediaUriTest {

    @Test
    fun `本地后端的伪 URI 可读且完全可逆`() {
        val backendId = "local-file:/storage/emulated/0"
        val path = "Music/七里香.mp3"

        val uri = MediaUri.format(backendId, path)

        assertEquals(
            "mediagate://local-file%3A%2Fstorage%2Femulated%2F0/Music/%E4%B8%83%E9%87%8C%E9%A6%99.mp3",
            uri,
        )
        assertEquals(MediaUri.Parsed(backendId, path), MediaUri.fromUri(uri))
    }

    @Test
    fun `空路径表示后端根目录`() {
        val uri = MediaUri.format("webdav:nas", "")
        assertEquals("mediagate://webdav%3Anas/", uri)
        assertEquals(MediaUri.Parsed("webdav:nas", ""), MediaUri.fromUri(uri))
    }

    @Test
    fun `没有路径段时路径为空串`() {
        // authority 会被解码回后端 id 原文
        assertEquals(MediaUri.Parsed("sftp:nas", ""), MediaUri.fromUri("mediagate://sftp%3Anas"))
    }

    @Test
    fun `空格与保留字符都会被转义`() {
        val path = "a b#c?d&e=f.txt"
        val uri = MediaUri.format("local-file:/x", path)

        assertTrue("空格必须转义：$uri", uri.contains("%20"))
        assertFalse("不能出现裸的 #（会被当成 fragment）：$uri", uri.substringAfter("local-file%3A%2Fx/").contains("#"))
        assertEquals(MediaUri.Parsed("local-file:/x", path), MediaUri.fromUri(uri))
    }

    @Test
    fun `段内的百分号字面量不会被误解码`() {
        val path = "we%2Fird/100%.txt"
        val uri = MediaUri.format("ftp:host", path)

        assertEquals(MediaUri.Parsed("ftp:host", path), MediaUri.fromUri(uri))
        assertEquals("百分号要被编码成 %25", true, uri.contains("%252F"))
    }

    @Test
    fun `多级目录逐段保留`() {
        val path = "Movies/2026/01/movie.mkv"
        assertEquals(path, MediaUri.fromUri(MediaUri.format("sftp:nas", path))?.path)
    }

    @Test
    fun `非 mediagate 的地址一律返回 null`() {
        assertNull(MediaUri.fromUri("file:///storage/a.mp3"))
        assertNull(MediaUri.fromUri("https://example.com/a.mp3"))
        assertNull(MediaUri.fromUri(""))
        assertNull(MediaUri.fromUri("mediagate:/only-one-slash"))
    }

    @Test
    fun `authority 为空时不解析`() {
        assertNull(MediaUri.fromUri("mediagate:///a.mp3"))
        assertNull(MediaUri.fromUri("mediagate://"))
    }

    @Test
    fun `非法转义原样保留而不是抛异常`() {
        assertEquals(MediaUri.Parsed("a%ZZ", "b%"), MediaUri.fromUri("mediagate://a%ZZ/b%"))
    }

    @Test
    fun `isMediaUri 判定`() {
        assertTrue(MediaUri.isMediaUri(MediaUri.format("local-file:/x", "a.mp3")))
        assertFalse(MediaUri.isMediaUri("content://media/external/audio/1"))
    }
}
