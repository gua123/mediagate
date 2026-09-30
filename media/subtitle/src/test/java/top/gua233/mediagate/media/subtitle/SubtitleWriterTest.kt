package io.github.gua123.mediagate.media.subtitle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.File
import java.nio.file.Files

/** 字幕写回与序列化（R14：SRT/VTT 往返一致；无写权限时异常必须抛给上层落本地缓存）。 */
class SubtitleWriterTest {

    private val cues = listOf(
        SubtitleCue(1_000L, 3_500L, "第一行"),
        SubtitleCue(4_000L, 5_000L, "第二行\n带换行"),
    )

    @Test
    fun srtSerializationUsesIndexAndCommaTimecode() {
        val text = SubtitleWriter.serialize(cues, SubtitleFormat.SRT)

        assertTrue(text.startsWith("1\n00:00:01,000 --> 00:00:03,500\n第一行\n\n"))
        assertTrue(text.contains("2\n00:00:04,000 --> 00:00:05,000\n第二行\n带换行"))
    }

    @Test
    fun vttSerializationHasHeaderAndDotTimecode() {
        val text = SubtitleWriter.serialize(cues, SubtitleFormat.VTT)

        assertTrue(text.startsWith("WEBVTT\n\n"))
        assertTrue(text.contains("00:00:01.000 --> 00:00:03.500"))
        assertFalse(text.contains("00:00:01,000"))
    }

    @Test
    fun srtRoundTripKeepsCues() {
        val text = SubtitleWriter.serialize(cues, SubtitleFormat.SRT)

        val reparsed = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(cues, reparsed.cues)
        assertEquals(0, reparsed.dropped)
        assertEquals(0, reparsed.truncatedLines)
    }

    @Test
    fun vttRoundTripKeepsCues() {
        val text = SubtitleWriter.serialize(cues, SubtitleFormat.VTT)

        val reparsed = SubtitleParser.parse(text, SubtitleFormat.VTT)

        assertEquals(cues, reparsed.cues)
        assertEquals(0, reparsed.dropped)
    }

    @Test
    fun roundTripKeepsMillisecondsAcrossHourBoundary() {
        val long = listOf(
            SubtitleCue(0L, 1L, "零"),
            SubtitleCue(3_599_999L, 3_600_001L, "跨小时"),
            SubtitleCue(7_200_123L, 7_200_999L, "两小时"),
        )

        assertEquals(long, SubtitleParser.parse(SubtitleWriter.serialize(long, SubtitleFormat.SRT), SubtitleFormat.SRT).cues)
        assertEquals(long, SubtitleParser.parse(SubtitleWriter.serialize(long, SubtitleFormat.VTT), SubtitleFormat.VTT).cues)
    }

    @Test
    fun timecodeFormattingClampsNegativeToZero() {
        assertEquals("00:00:00,000", SubtitleWriter.formatTimecode(-500L, SubtitleFormat.SRT))
        assertEquals("00:00:00.000", SubtitleWriter.formatTimecode(-500L, SubtitleFormat.VTT))
        assertEquals("01:01:01,001", SubtitleWriter.formatTimecode(3_661_001L, SubtitleFormat.SRT))
    }

    @Test
    fun serializeRejectsAssAndSsa() {
        for (format in listOf(SubtitleFormat.ASS, SubtitleFormat.SSA)) {
            try {
                SubtitleWriter.serialize(cues, format)
                fail("ASS/SSA 不支持写回，应当抛出：" + format)
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("SRT"))
            }
        }
    }

    @Test
    fun serializeSortsAndDropsInvalidCues() {
        val messy = listOf(
            SubtitleCue(5_000L, 6_000L, "后"),
            SubtitleCue(9_000L, 8_000L, "倒挂"),
            SubtitleCue(7_000L, 8_000L, "   "),
            SubtitleCue(1_000L, 2_000L, "前"),
        )

        val reparsed = SubtitleParser.parse(SubtitleWriter.serialize(messy, SubtitleFormat.SRT), SubtitleFormat.SRT)

        assertEquals(listOf("前", "后"), reparsed.cues.map { it.text })
    }

    @Test
    fun writeBackSendsSerializedBytes() = runTest {
        val backend = FakeSubtitleBackend()

        SubtitleWriter.writeBack(backend, "Movies/a.zh.srt", SubtitleFormat.SRT, cues)

        val written = backend.written.getValue("Movies/a.zh.srt").toString(Charsets.UTF_8)
        assertEquals(cues, SubtitleParser.parse(written, SubtitleFormat.SRT).cues)
    }

    @Test
    fun writeBackPropagatesAccessDenied() = runTest {
        val backend = FakeSubtitleBackend()
        backend.writeFailure = StorageException.AccessDenied("只读目录")

        try {
            SubtitleWriter.writeBack(backend, "Movies/a.zh.srt", SubtitleFormat.SRT, cues)
            fail("无写权限必须抛给上层（R14：落本地缓存并提示）")
        } catch (expected: StorageException.AccessDenied) {
            assertEquals("只读目录", expected.message)
        }
        assertTrue(backend.written.isEmpty())
    }

    @Test
    fun writeBackPropagatesNotSupported() = runTest {
        val backend = FakeSubtitleBackend()
        backend.writeFailure = StorageException.NotSupported("该后端不支持写入")

        try {
            SubtitleWriter.writeBack(backend, "a.srt", SubtitleFormat.SRT, cues)
            fail("不支持写入必须抛给上层")
        } catch (expected: StorageException.NotSupported) {
            assertEquals("该后端不支持写入", expected.message)
        }
    }

    @Test
    fun writeLocalCreatesParentDirectories() {
        val root = Files.createTempDirectory("subtitle-writer").toFile()
        try {
            val file = File(root, "nested/out.srt")

            SubtitleWriter.writeLocal(file, SubtitleFormat.SRT, cues)

            assertTrue(file.exists())
            assertEquals(cues, SubtitleParser.parse(file.readText(), SubtitleFormat.SRT).cues)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun siblingPathOfKeepsVideoDirectoryAndBaseName() {
        assertEquals("Movies/a.srt", SubtitleWriter.siblingPathOf("Movies/a.mkv", SubtitleFormat.SRT))
        assertEquals("a.vtt", SubtitleWriter.siblingPathOf("a.mkv", SubtitleFormat.VTT))
        assertEquals("Movies/Movie.2024.zh.srt", SubtitleWriter.siblingPathOf("Movies/Movie.2024.zh.mkv", SubtitleFormat.SRT))
    }
}
