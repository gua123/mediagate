package io.github.gua123.mediagate.data.storage.ftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * FTP 写回（**R14** 字幕写回视频同目录 / plan 4.1 表格 FTP 行：STOR）。
 *
 * 重点验证 R14 的两条语义：父目录不存在 → NotFound、无写权限 → AccessDenied
 * （上层据此落本地缓存并提示，绝不静默丢弃）。
 */
class FtpBackendWriteTest {

    private lateinit var root: Path
    private lateinit var server: FtpTestServer
    private lateinit var backend: FtpStorageBackend
    private var readonlyServer: FtpTestServer? = null
    private var readonlyBackend: FtpStorageBackend? = null

    @Before
    fun setUp() {
        root = newTempDir("ftp-write")
        Files.createDirectories(root.resolve("sub"))
        root.writeTextFile("existing.srt", "old-content")
        server = FtpTestServer(root)
        backend = FtpStorageBackend(server.config())
    }

    @After
    fun tearDown() {
        runCatching { backend.close() }
        runCatching { readonlyBackend?.close() }
        server.close()
        readonlyServer?.close()
        root.toFile().deleteRecursively()
    }

    /** 没有 WritePermission 的服务器（STOR 会被 550 拒掉）。 */
    private fun readonlyBackend(): FtpStorageBackend {
        if (readonlyBackend == null) {
            readonlyServer = FtpTestServer(root, writable = false, port = FtpTestServer.freePort())
            readonlyBackend = FtpStorageBackend(readonlyServer!!.config())
        }
        return readonlyBackend!!
    }

    private fun streamOf(bytes: ByteArray): InputStream = ByteArrayInputStream(bytes)

    @Test
    fun `写入新文件并可读回一致`() = runBlocking {
        val content = utf8("1\n00:00:01,000 --> 00:00:02,000\n你好\n")
        backend.write("new.srt", streamOf(content))
        assertArrayEquals(content, backend.openRead("new.srt", 0L, -1L).use { readAll(it) })
        assertEquals(content.size.toLong(), backend.stat("new.srt").size)
    }

    @Test
    fun `同名文件被覆盖而不是追加`() = runBlocking {
        backend.write("existing.srt", streamOf(utf8("new")))
        assertEquals("new", text(backend.openRead("existing.srt", 0L, -1L).use { readAll(it) }))
    }

    @Test
    fun `写进子目录成功（字幕与视频同目录）`() = runBlocking {
        backend.write("sub/movie.zh.srt", streamOf(utf8("字幕")))
        assertEquals("字幕", text(backend.openRead("sub/movie.zh.srt", 0L, -1L).use { readAll(it) }))
    }

    @Test
    fun `父目录不存在抛 NotFound`() {
        assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.write("no-such-dir/x.srt", streamOf(utf8("x"))) }
        }
    }

    @Test
    fun `无写权限抛 AccessDenied`() {
        assertThrows(StorageException.AccessDenied::class.java) {
            runBlocking { readonlyBackend().write("readonly.srt", streamOf(utf8("x"))) }
        }
    }

    @Test
    fun `写目录路径抛 AccessDenied`() {
        assertThrows(StorageException.AccessDenied::class.java) {
            runBlocking { backend.write("", streamOf(utf8("x"))) }
        }
    }

    @Test
    fun `写空内容成功`() = runBlocking {
        backend.write("empty.srt", streamOf(ByteArray(0)))
        assertEquals(0L, backend.stat("empty.srt").size)
    }

    @Test
    fun `写大文件完整落盘`() = runBlocking {
        val content = sampleBytes(256 * 1024)
        backend.write("big-sub.bin", streamOf(content))
        assertArrayEquals(content, backend.openRead("big-sub.bin", 0L, -1L).use { readAll(it) })
    }

    @Test
    fun `中文文件名可写可读（R14 字幕场景）`() = runBlocking {
        backend.write("电影.zh.srt", streamOf(utf8("中文字幕")))
        assertEquals("中文字幕", text(backend.openRead("电影.zh.srt", 0L, -1L).use { readAll(it) }))
        assertEquals("电影.zh.srt", backend.stat("电影.zh.srt").name)
    }

    @Test
    fun `basePath 之外的写入落到远端对应位置`() = runBlocking {
        val scoped = FtpStorageBackend(server.config(basePath = "/sub"))
        try {
            scoped.write("scoped.srt", streamOf(utf8("scoped")))
            assertEquals("scoped", text(scoped.openRead("scoped.srt", 0L, -1L).use { readAll(it) }))
            assertTrue(Files.exists(root.resolve("sub/scoped.srt")))
        } finally {
            scoped.close()
        }
    }

    @Test
    fun `写成功后 stat 能立刻看到新大小`() = runBlocking {
        backend.write("size.srt", streamOf(sampleBytes(1_234)))
        assertEquals(1_234L, backend.stat("size.srt").size)
        assertFalse(backend.stat("size.srt").isDirectory)
    }
}
