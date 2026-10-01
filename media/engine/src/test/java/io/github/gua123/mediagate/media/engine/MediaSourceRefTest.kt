package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaSourceRef] 与 media:playback 的 `MediaUri` 互转（plan 4.6 setMedia 的入参口径）。
 *
 * 模块依赖方向是 `:media:playback → :media:engine → :media:proxy`，engine 无法反向引用 playback 的
 * MediaUri，所以这里用**真实 MediaUri 跑出来的金标字符串**做交叉验证（金标取自 playback 的
 * MediaUri.format，见 :media:proxy 的 ProxyPureLogicTest 同样一份表），保证两边逐字一致、
 * 往返无损。
 */
class MediaSourceRefTest {

    @Test
    fun `伪 URI 与 MediaUri 金标逐字一致`() {
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
            val ref = MediaSourceRef(backendId, path)
            assertEquals("uri 必须与 MediaUri 逐字一致", golden, ref.uri)
            assertEquals("toUri 与 uri 同义", golden, ref.toUri())
        }
    }

    @Test
    fun `从伪 URI 解析回来完全无损`() {
        val tricky = listOf(
            "local-file:/storage/emulated/0" to "Music/歌曲 01.mp3",
            "webdav:https://dav.example.com/dav" to "/movies/影 片/预告(1).ts",
            "local-file:/x" to "weird%2Fslash.txt",
            "local-file:/root" to "",
            "后端:中文/斜杠" to "路径/带/斜杠.txt",
        )
        for ((backendId, path) in tricky) {
            val original = MediaSourceRef(backendId, path, title = "标题")
            val parsed = MediaSourceRef.fromUri(original.uri, title = original.title)
            assertEquals("往返后必须完全相等", original, parsed)
            assertEquals("再格式化回字符串也必须一致", original.uri, parsed!!.uri)
            // 假 URI 也必须能被识别
            assertTrue(MediaSourceRef.isMediaUri(original.uri))
        }
    }

    @Test
    fun `非法地址解析为 null 且不被误判`() {
        assertNull(MediaSourceRef.fromUri("file:///sdcard/a.mp3"))
        assertNull(MediaSourceRef.fromUri("mediagate://"))
        assertNull(MediaSourceRef.fromUri(""))
        assertFalse(MediaSourceRef.isMediaUri("http://127.0.0.1:1/m/x"))
    }

    @Test
    fun `展示名优先用标题其次用文件名`() {
        assertEquals("自定义标题", MediaSourceRef("b", "dir/a.mp4", "自定义标题").displayName)
        assertEquals("a.mp4", MediaSourceRef("b", "dir/a.mp4").displayName)
        assertEquals("空白标题不算数", "a.mp4", MediaSourceRef("b", "dir/a.mp4", "   ").displayName)
        assertEquals("根目录回退到路径本身", "", MediaSourceRef("b", "").displayName)
    }

    @Test
    fun `改了路径之后 uri 会跟着变`() {
        val ref = MediaSourceRef("local-file:/x", "a.mp4")
        val moved = ref.copy(path = "sub/b.mp4")
        assertEquals("mediagate://local-file%3A%2Fx/a.mp4", ref.uri)
        assertEquals("mediagate://local-file%3A%2Fx/sub/b.mp4", moved.uri)
    }
}
