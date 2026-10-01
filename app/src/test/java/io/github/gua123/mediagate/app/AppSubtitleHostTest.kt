package io.github.gua123.mediagate.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.feature.player.video.SubtitleWriteResult
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleParser
import java.io.InputStream
import java.nio.file.Files

/**
 * [AppSubtitleHost] 的 JVM 单测（R14：同目录匹配 / 读取解析 / 写回，无写权限必须落本地并回传路径）。
 *
 * 用内存假后端驱动真实实现，覆盖三种写回结局：写回原目录成功、无写权限落 App 私有目录、其它失败。
 * 这里验证的是 **R14 的"绝不静默丢弃"**：无权限时一定有一个真实存在的本地文件 + 一条可提示的路径。
 */
class AppSubtitleHostTest {

    private val sample = "1\n00:00:01,000 --> 00:00:03,000\n第一条\n"

    @Test
    fun loadParsesSubtitleFromBackend() = runTest {
        val backend = FakeAppBackend()
        backend.put("Movies/b.zh.srt", sample)
        val host = host(backend)

        val result = host.load("Movies/b.zh.srt")

        assertEquals(1, result.count)
        assertEquals("第一条", result.cues.single().text)
    }

    @Test
    fun discoverMatchesSubtitleInTheSameDirectory() = runTest {
        val backend = FakeAppBackend()
        backend.listing(
            "Movies",
            listOf(entry("Movies/b.mkv"), entry("Movies/b.zh.srt"), entry("Movies/b.txt")),
        )
        val host = host(backend)

        val candidates = host.discover("Movies/b.mkv")

        assertEquals(listOf("Movies/b.zh.srt"), candidates.map { it.path })
        assertEquals("zh", candidates.single().language)
    }

    @Test
    fun listReturnsBackendEntries() = runTest {
        val backend = FakeAppBackend()
        backend.listing("Movies", listOf(entry("Movies/a.srt")))
        val host = host(backend)

        assertEquals(listOf("Movies/a.srt"), host.list("Movies").map { it.path })
    }

    @Test
    fun withoutBackendLoadFailsWithAccessDenied() = runTest {
        val host = host(null)

        try {
            host.load("Movies/b.zh.srt")
            fail("没有根目录后端时应当抛「无访问权限」，而不是静默返回空字幕")
        } catch (expected: StorageException.AccessDenied) {
            assertTrue(expected.message.orEmpty().contains("根目录"))
        }
    }

    @Test
    fun writeBackWritesToTheSameNameInTheVideoDirectory() = runTest {
        val backend = FakeAppBackend()
        val host = host(backend)
        val cues = SubtitleParser.parse(sample, SubtitleFormat.SRT).cues

        val result = host.writeBack("Movies/b.mkv", SubtitleFormat.SRT, cues)

        assertTrue(result is SubtitleWriteResult.Written)
        assertEquals("Movies/b.srt", (result as SubtitleWriteResult.Written).path)
        assertEquals(cues, SubtitleParser.parse(backend.saved.getValue("Movies/b.srt").toString(Charsets.UTF_8), SubtitleFormat.SRT).cues)
    }

    @Test
    fun writeBackWithoutPermissionFallsBackToPrivateDirectory() = runTest {
        val backend = FakeAppBackend()
        backend.writeFailure = StorageException.AccessDenied("只读共享")
        val fallbackDir = Files.createTempDirectory("subtitle-fallback").toFile()
        try {
            val host = host(backend, fallbackDir)
            val cues = SubtitleParser.parse(sample, SubtitleFormat.SRT).cues

            val result = host.writeBack("Movies/b.mkv", SubtitleFormat.SRT, cues)

            assertTrue(result is SubtitleWriteResult.LocalFallback)
            val path = (result as SubtitleWriteResult.LocalFallback).path
            val file = java.io.File(path)
            assertTrue(file.exists())
            assertEquals(fallbackDir.absolutePath, file.parentFile?.absolutePath)
            assertEquals("b.srt", file.name)
            // 内容可用：解析回来与写回前一致（R14"可分享/稍后重试"）
            assertEquals(cues, SubtitleParser.parse(file.readText(), SubtitleFormat.SRT).cues)
        } finally {
            fallbackDir.deleteRecursively()
        }
    }

    @Test
    fun notSupportedWriteAlsoFallsBackToLocal() = runTest {
        val backend = FakeAppBackend()
        backend.writeFailure = StorageException.NotSupported("该后端不支持写入")
        val fallbackDir = Files.createTempDirectory("subtitle-fallback-ns").toFile()
        try {
            val host = host(backend, fallbackDir)
            val cues = SubtitleParser.parse(sample, SubtitleFormat.SRT).cues

            val result = host.writeBack("Movies/b.mkv", SubtitleFormat.VTT, cues)

            assertTrue(result is SubtitleWriteResult.LocalFallback)
            assertTrue(java.io.File((result as SubtitleWriteResult.LocalFallback).path).name.endsWith(".vtt"))
        } finally {
            fallbackDir.deleteRecursively()
        }
    }

    @Test
    fun otherWriteFailureIsReportedAndNothingIsWritten() = runTest {
        val backend = FakeAppBackend()
        backend.writeFailure = StorageException.Network("网络不可用")
        val fallbackDir = Files.createTempDirectory("subtitle-fallback-net").toFile()
        try {
            val host = host(backend, fallbackDir)
            val cues = SubtitleParser.parse(sample, SubtitleFormat.SRT).cues

            val result = host.writeBack("Movies/b.mkv", SubtitleFormat.SRT, cues)

            assertTrue(result is SubtitleWriteResult.Failed)
            assertEquals("网络不可用", (result as SubtitleWriteResult.Failed).reason)
            assertFalse(fallbackDir.exists() && fallbackDir.listFiles().orEmpty().isNotEmpty())
        } finally {
            fallbackDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ 测试脚手架

    private fun host(backend: StorageBackend?, fallbackDir: java.io.File = Files.createTempDirectory("subtitle-fallback-default").toFile()) =
        AppSubtitleHost(backend = { backend }, fallbackDir = fallbackDir)

    private fun entry(path: String): RemoteEntry = RemoteEntry(
        name = path.substringAfterLast('/'),
        path = path,
        size = 128L,
    )
}

/** 内存假后端：list / openRead / write 都够用，可注入写失败。 */
private class FakeAppBackend : StorageBackend {

    private val files = mutableMapOf<String, ByteArray>()
    private val listings = mutableMapOf<String, List<RemoteEntry>>()

    /** 成功写入的内容。 */
    val saved = mutableMapOf<String, ByteArray>()

    var writeFailure: Throwable? = null

    override val id: String = "fake:app"

    override val caps: Caps = Caps(randomAccess = true, writable = true, maxParallelReads = 2)

    fun put(path: String, text: String) {
        files[path] = text.toByteArray(Charsets.UTF_8)
    }

    fun listing(dir: String, entries: List<RemoteEntry>) {
        listings[dir] = entries
    }

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = listings[dir].orEmpty()

    override suspend fun stat(path: String): RemoteEntry = RemoteEntry(name = path, path = path)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        val bytes = files[path] ?: throw StorageException.NotFound(path)
        return object : RangeStream {
            private var cursor = offset
            override val length: Long = bytes.size.toLong()
            override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (cursor >= bytes.size) return -1
                val count = minOf(len.toLong(), bytes.size - cursor).toInt()
                System.arraycopy(bytes, cursor.toInt(), buf, off, count)
                cursor += count
                return count
            }

            override suspend fun seek(position: Long) {
                cursor = position
            }

            override fun position(): Long = cursor
            override fun close() = Unit
        }
    }

    override suspend fun write(path: String, data: InputStream) {
        writeFailure?.let { throw it }
        saved[path] = data.readBytes()
    }

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}
