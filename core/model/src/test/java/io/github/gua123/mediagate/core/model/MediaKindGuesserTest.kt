package io.github.gua123.mediagate.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** [MediaKindGuesser] 的扩展名覆盖测试（R1 三类媒体 / R3 .ts 与 m3u8 / R14 字幕）。 */
class MediaKindGuesserTest {

    @Test
    fun `视频包含通用格式与 TS 家族和 m3u8`() {
        val extensions = listOf("ts", "m2ts", "mts", "tp", "m3u8", "mkv", "mp4", "avi", "flv", "mov", "webm", "wmv", "rmvb")
        for (ext in extensions) {
            assertEquals("扩展名 .$ext 应判为视频", MediaKind.VIDEO, MediaKindGuesser.guess("movie.$ext"))
        }
    }

    @Test
    fun `音频扩展名`() {
        for (ext in listOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "ape")) {
            assertEquals("扩展名 .$ext 应判为音频", MediaKind.AUDIO, MediaKindGuesser.guess("song.$ext"))
        }
    }

    @Test
    fun `图片扩展名`() {
        for (ext in listOf("jpg", "jpeg", "png", "heic", "heif", "avif", "gif", "webp", "bmp")) {
            assertEquals("扩展名 .$ext 应判为图片", MediaKind.IMAGE, MediaKindGuesser.guess("pic.$ext"))
        }
    }

    @Test
    fun `字幕扩展名`() {
        for (ext in listOf("srt", "vtt", "ass", "ssa")) {
            assertEquals("扩展名 .$ext 应判为字幕", MediaKind.SUBTITLE, MediaKindGuesser.guess("sub.$ext"))
        }
    }

    @Test
    fun `大小写不敏感且支持完整路径`() {
        assertEquals(MediaKind.VIDEO, MediaKindGuesser.guess("MOVIE.TS"))
        assertEquals(MediaKind.VIDEO, MediaKindGuesser.guess("/storage/emulated/0/Movies/a.M2TS"))
        assertEquals(MediaKind.AUDIO, MediaKindGuesser.guess("Song.MP3"))
        assertEquals(MediaKind.IMAGE, MediaKindGuesser.guess("Pic.JPEG"))
        assertEquals(MediaKind.SUBTITLE, MediaKindGuesser.guess("Sub.SRT"))
    }

    @Test
    fun `未知与无扩展名归为 OTHER`() {
        assertEquals(MediaKind.OTHER, MediaKindGuesser.guess("README"))
        assertEquals(MediaKind.OTHER, MediaKindGuesser.guess("archive.tar.gz"))
        assertEquals(MediaKind.OTHER, MediaKindGuesser.guess("noext."))
        assertEquals(MediaKind.OTHER, MediaKindGuesser.guess(".gitignore"))
        assertEquals(MediaKind.OTHER, MediaKindGuesser.guess(""))
    }
}
