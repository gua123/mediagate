package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 伪 URI 的**金标**单测（从已删除的 :media:proxy 模块搬过来，2026-10-19 清理）。
 *
 * 这串金标与 :media:playback 的 MediaUri 输出必须逐字一致——两边是同一个地址口径，
 * 一处改了另一处不改，远端播放就会"找不到文件"。
 */
class MediaUriCodecTest {

    @Test
    fun `输出与 playback 的 MediaUri 金标逐字一致`() {
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
            assertEquals("parse 往返", MediaUriCodec.Parsed(backendId, path), MediaUriCodec.parse(golden))
        }
    }
}
