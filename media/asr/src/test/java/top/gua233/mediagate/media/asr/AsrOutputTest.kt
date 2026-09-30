package io.github.gua123.mediagate.media.asr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import java.io.InputStream

/**
 * 字幕产出的 JVM 单测（**M7-B / R14**）：SRT/VTT/纯文本渲染、命名规则、
 * 写回成功、无写权限落 App 私有目录（绝不静默丢弃）、网络失败照抛。
 */
class AsrOutputTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cues = listOf(
        SubtitleCue(0L, 1_500L, "第一句"),
        SubtitleCue(1_500L, 3_000L, "第二句"),
    )

    @Test
    fun render_srtReusesSubtitleWriter() {
        val text = AsrOutput.render(cues, AsrOutputFormat.SRT)
        assertTrue(text.startsWith("1\n00:00:00,000 --> 00:00:01,500\n第一句"))
        assertTrue(text.contains("2\n00:00:01,500 --> 00:00:03,000\n第二句"))
    }

    @Test
    fun render_vttStartsWithHeader() {
        val text = AsrOutput.render(cues, AsrOutputFormat.VTT)
        assertTrue(text.startsWith("WEBVTT"))
        assertTrue(text.contains("00:00:01.500 --> 00:00:03.000"))
    }

    @Test
    fun render_textKeepsOnlyLines() {
        val text = AsrOutput.render(cues, AsrOutputFormat.TEXT)
        assertEquals("第一句\n第二句", text)
    }

    @Test
    fun naming_followsVideoNameAndDirectory() {
        assertEquals("Movie.srt", AsrOutput.fileNameFor("/Movies/Movie.mkv"))
        assertEquals("/Movies/Movie.srt", AsrOutput.siblingPathFor("/Movies/Movie.mkv"))
        assertEquals("Movie.txt", AsrOutput.fileNameFor("/Movies/Movie.mkv", AsrOutputFormat.TEXT))
        assertEquals("Movie.vtt", AsrOutput.fileNameFor("Movie.mkv", AsrOutputFormat.VTT))
    }

    @Test
    fun formatMapping_matchesSubtitleFormats() {
        assertEquals(AsrOutputFormat.DEFAULT, AsrOutputFormat.SRT)
        assertEquals(AsrOutputFormat.VTT, AsrOutputFormat.of(SubtitleFormat.VTT))
        assertEquals(AsrOutputFormat.SRT, AsrOutputFormat.of(SubtitleFormat.ASS))
        assertEquals(SubtitleFormat.SRT, AsrOutputFormat.SRT.subtitleFormat)
        assertEquals(null, AsrOutputFormat.TEXT.subtitleFormat)
    }

    @Test
    fun write_writesIntoTheBackendWhenWritable() = runTest {
        val backend = FakeBackend()
        val result = AsrOutput.write(backend, "/Movies/Movie.mkv", AsrOutputFormat.SRT, cues, fallbackDir())
        assertEquals(AsrWriteKind.WRITTEN, result.kind)
        assertTrue(result.isWritten)
        assertEquals("/Movies/Movie.srt", result.path)
        assertTrue(backend.files.getValue("/Movies/Movie.srt").contains("第一句"))
        assertTrue(result.message.contains("已生成"))
    }

    @Test
    fun write_fallsBackToAppDirectoryWhenAccessDenied() = runTest {
        val backend = FakeBackend(writeError = StorageException.AccessDenied("只读"))
        val dir = fallbackDir()
        val result = AsrOutput.write(backend, "/Movies/Movie.mkv", AsrOutputFormat.SRT, cues, dir)

        assertEquals(AsrWriteKind.LOCAL_FALLBACK, result.kind)
        assertFalse(result.isWritten)
        val file = java.io.File(result.path)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("第一句"))
        assertTrue(result.message.contains("无写入权限"))
        assertTrue(result.message.contains(dir.absolutePath))
    }

    @Test
    fun write_fallsBackWhenBackendIsNotSupported() = runTest {
        val backend = FakeBackend(writeError = StorageException.NotSupported())
        val result = AsrOutput.write(backend, "/Movies/Movie.mkv", AsrOutputFormat.VTT, cues, fallbackDir())
        assertEquals(AsrWriteKind.LOCAL_FALLBACK, result.kind)
        assertTrue(java.io.File(result.path).readText().startsWith("WEBVTT"))
    }

    @Test
    fun write_propagatesRealFailures() = runTest {
        val backend = FakeBackend(writeError = StorageException.Network("断了"))
        val error = runCatching {
            AsrOutput.write(backend, "/Movies/Movie.mkv", AsrOutputFormat.SRT, cues, fallbackDir())
        }.exceptionOrNull()
        assertTrue(error is StorageException.Network)
    }

    @Test
    fun write_withoutBackendSavesLocally() = runTest {
        val result = AsrOutput.write(null, "/Movies/Movie.mkv", AsrOutputFormat.SRT, cues, fallbackDir())
        assertEquals(AsrWriteKind.LOCAL_FALLBACK, result.kind)
        assertTrue(java.io.File(result.path).isFile)
    }

    @Test
    fun write_producesExactlyOneFile() = runTest {
        val dir = fallbackDir()
        val backend = FakeBackend()
        AsrOutput.write(backend, "/Movies/Movie.mkv", AsrOutputFormat.SRT, cues, dir)
        assertEquals(1, backend.files.size)
        // 没有写出第二个格式，也没有在兜底目录里留东西
        assertFalse(backend.files.containsKey("/Movies/Movie.vtt"))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    private fun fallbackDir() = folder.newFolder("subtitles")

    /** 只实现 write 的假后端；其余方法不会被调用（调到就是 bug，直接抛）。 */
    private class FakeBackend(private val writeError: Throwable? = null) : StorageBackend {

        val files = mutableMapOf<String, String>()

        override val id: String = "fake"
        override val caps: Caps = Caps(writable = true)

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = error("未使用")

        override suspend fun stat(path: String): RemoteEntry = error("未使用")

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream = error("未使用")

        override suspend fun write(path: String, data: InputStream) {
            writeError?.let { throw it }
            files[path] = data.readBytes().toString(Charsets.UTF_8)
        }

        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

        override fun close() = Unit
    }
}
