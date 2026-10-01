package io.github.gua123.mediagate.data.storage.sftp

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
 * SFTP 写回（**R14** 字幕写回视频同目录 / plan 4.1 表格 SFTP 行）。
 *
 * 重点验证 R14 的两条语义：父目录不存在 → NotFound、无写权限 → AccessDenied
 * （上层据此落本地缓存并提示，绝不静默丢弃）。
 *
 * 注意：测试进程通常是 root，chmod 拦不住 root，所以「只读目录」由嵌入式服务器
 * 在打开写句柄时显式回 SSH_FX_PERMISSION_DENIED（见 [SftpTestServer]）。
 */
class SftpBackendWriteTest {

    private lateinit var root: Path
    private lateinit var server: SftpTestServer
    private lateinit var backend: SftpStorageBackend

    @Before
    fun setUp() {
        root = newTempDir("sftp-write")
        Files.createDirectories(root.resolve("sub"))
        Files.createDirectories(root.resolve("readonly"))
        root.writeTextFile("existing.srt", "old-content")
        server = SftpTestServer(root, readonlyPrefix = READONLY_DIR)
        backend = SftpStorageBackend(server.config())
    }

    @After
    fun tearDown() {
        runCatching { backend.close() }
        server.close()
        root.toFile().deleteRecursively()
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
        val e = assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.write("no-such-dir/x.srt", streamOf(utf8("x"))) }
        }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("不存在"))
    }

    @Test
    fun `无写权限抛 AccessDenied`() {
        assertThrows(StorageException.AccessDenied::class.java) {
            runBlocking { backend.write(READONLY_DIR + "/x.srt", streamOf(utf8("x"))) }
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
    fun `不关闭调用方传入的流（契约）`() = runBlocking {
        var closed = false
        val stream = object : ByteArrayInputStream(utf8("data")) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        backend.write("contract.srt", stream)
        assertFalse("StorageBackend.write 约定由调用方关闭 data", closed)
    }

    @Test
    fun `中文文件名可写可读（R14 字幕场景）`() = runBlocking {
        backend.write("电影.zh.srt", streamOf(utf8("中文字幕")))
        assertEquals("中文字幕", text(backend.openRead("电影.zh.srt", 0L, -1L).use { readAll(it) }))
        assertEquals("电影.zh.srt", backend.stat("电影.zh.srt").name)
    }

    @Test
    fun `basePath 之外的写入落到远端对应位置`() = runBlocking {
        val scoped = SftpStorageBackend(server.config(basePath = "/sub"))
        try {
            scoped.write("scoped.srt", streamOf(utf8("scoped")))
            assertEquals("scoped", text(scoped.openRead("scoped.srt", 0L, -1L).use { readAll(it) }))
            assertTrue(Files.exists(root.resolve("sub/scoped.srt")))
        } finally {
            scoped.close()
        }
    }

    private companion object {
        const val READONLY_DIR = "readonly"
    }
}
