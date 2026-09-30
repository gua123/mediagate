package io.github.gua123.mediagate.media.subtitle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.nio.charset.Charset

/** 字幕格式判定、文本编解码与后端读取（R14：编码容错 + 远端/本地同一套读取路径）。 */
class SubtitleIoTest {

    // ------------------------------------------------------------------ 格式

    @Test
    fun formatFromExtensionIsCaseInsensitiveAndAcceptsDot() {
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.fromExtension("srt"))
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.fromExtension(".SRT"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.fromExtension("Vtt"))
        assertEquals(SubtitleFormat.ASS, SubtitleFormat.fromExtension("ass"))
        assertEquals(SubtitleFormat.SSA, SubtitleFormat.fromExtension("ssa"))
        assertNull(SubtitleFormat.fromExtension("txt"))
        assertNull(SubtitleFormat.fromExtension(""))
    }

    @Test
    fun formatFromFileNameUsesTheLastSegment() {
        assertEquals(SubtitleFormat.SRT, SubtitleFormat.fromFileName("Movies/Movie.2024.zh.srt"))
        assertEquals(SubtitleFormat.VTT, SubtitleFormat.fromFileName("a.vtt"))
        assertNull(SubtitleFormat.fromFileName("a.mkv"))
    }

    @Test
    fun isSubtitleFileRejectsOtherExtensions() {
        assertTrue(SubtitleFormat.isSubtitleFile("a.srt"))
        assertFalse(SubtitleFormat.isSubtitleFile("a.mkv"))
        assertFalse(SubtitleFormat.isSubtitleFile("a"))
    }

    @Test
    fun onlySrtAndVttAreWritable() {
        assertTrue(SubtitleFormat.SRT.writable)
        assertTrue(SubtitleFormat.VTT.writable)
        assertFalse(SubtitleFormat.ASS.writable)
        assertFalse(SubtitleFormat.SSA.writable)
        assertEquals(listOf("srt", "vtt", "ass", "ssa"), SubtitleFormat.EXTENSIONS)
    }

    @Test
    fun formatsCarryMimeTypes() {
        assertEquals("application/x-subrip", SubtitleFormat.SRT.mimeType)
        assertEquals("text/vtt", SubtitleFormat.VTT.mimeType)
        assertEquals("text/x-ssa", SubtitleFormat.ASS.mimeType)
    }

    // ------------------------------------------------------------------ 编解码

    @Test
    fun normalizeStripsBomAndUnifiesNewlines() {
        assertEquals("a\nb", SubtitleTextCodec.normalize("\uFEFFa\r\nb"))
        assertEquals("a\nb", SubtitleTextCodec.normalize("a\rb"))
        assertEquals("a\nb\nc", SubtitleTextCodec.normalize("a\r\nb\r\nc"))
    }

    @Test
    fun decodeHandlesUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray(Charsets.UTF_8)

        assertEquals("你好", SubtitleTextCodec.decode(bytes))
    }

    @Test
    fun decodeHandlesUtf16LeBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "你好".toByteArray(Charsets.UTF_16LE)

        assertEquals("你好", SubtitleTextCodec.decode(bytes))
    }

    @Test
    fun decodeFallsBackToGbkForChineseLegacyFiles() {
        val bytes = "中文字幕".toByteArray(Charset.forName("GBK"))

        assertEquals("中文字幕", SubtitleTextCodec.decode(bytes))
    }

    @Test
    fun decodeEmptyGivesEmptyText() {
        assertEquals("", SubtitleTextCodec.decode(ByteArray(0)))
    }

    // ------------------------------------------------------------------ 读取

    @Test
    fun readerParsesSubtitleFromBackend() = runTest {
        val backend = FakeSubtitleBackend()
        backend.put("Movies/a.srt", "1\n00:00:01,000 --> 00:00:02,000\n你好\n")

        val result = SubtitleReader.read(backend, "Movies/a.srt")

        assertEquals(1, result.count)
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals("你好", result.cues[0].text)
    }

    @Test
    fun readerSniffsFormatWhenExtensionIsUnknown() = runTest {
        val backend = FakeSubtitleBackend()
        backend.put("Movies/a.dat", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n")

        val result = SubtitleReader.read(backend, "Movies/a.dat")

        assertEquals(SubtitleFormat.VTT, result.format)
        assertEquals(1, result.count)
    }

    @Test
    fun readerParsesGbkSubtitle() = runTest {
        val backend = FakeSubtitleBackend()
        backend.put("a.srt", "1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n", Charset.forName("GBK"))

        assertEquals("中文字幕", SubtitleReader.read(backend, "a.srt").cues[0].text)
    }

    @Test
    fun readerThrowsNotFoundWhenFileIsEmpty() = runTest {
        val backend = FakeSubtitleBackend()
        backend.put("a.srt", "")

        try {
            SubtitleReader.read(backend, "a.srt")
            fail("空字幕应当按「找不到内容」抛出，而不是给一份空结果")
        } catch (expected: StorageException.NotFound) {
            assertTrue(expected.message.orEmpty().contains("a.srt"))
        }
    }

    @Test
    fun readerPropagatesReadFailure() = runTest {
        val backend = FakeSubtitleBackend()
        backend.readFailure = StorageException.AccessDenied("无读权限")

        try {
            SubtitleReader.read(backend, "a.srt")
            fail("读失败要抛给上层（播放页给中文提示）")
        } catch (expected: StorageException.AccessDenied) {
            assertEquals("无读权限", expected.message)
        }
    }

    @Test
    fun readBytesRespectsLimit() = runTest {
        val backend = FakeSubtitleBackend()
        backend.put("a.srt", "0123456789abcdef")

        val bytes = SubtitleReader.readBytes(backend, "a.srt", limit = 10)

        assertEquals(10, bytes.size)
        assertEquals("0123456789", bytes.toString(Charsets.UTF_8))
    }
}
