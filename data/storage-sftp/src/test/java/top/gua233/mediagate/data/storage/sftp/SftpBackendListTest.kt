package io.github.gua123.mediagate.data.storage.sftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.nio.file.Files
import java.nio.file.Path

/**
 * SFTP 列目录 / stat（**R2** 四协议之列目录 / plan 4.1 表格 SFTP 行）。
 *
 * 用嵌入式 sshd 服务器跑真 readdir，排序与分页口径必须与 FileStorageBackend 一致。
 */
class SftpBackendListTest {

    private lateinit var root: Path
    private lateinit var server: SftpTestServer
    private lateinit var backend: SftpStorageBackend

    @Before
    fun setUp() {
        root = newTempDir("sftp-list")
        // 目录 / 文件 / 大小写混合，专门用来验证「目录优先 + 名称大小写不敏感」
        root.writeTextFile("alpha/inside.txt", "inside")
        root.writeTextFile("Zeta/z.txt", "z")
        root.writeTextFile("apple.txt", "a")
        root.writeTextFile("Beta.txt", "bb")
        root.writeTextFile("Cherry.txt", "ccc")
        root.writeFile("movie.ts", sampleBytes(4096))
        server = SftpTestServer(root)
        backend = SftpStorageBackend(server.config())
    }

    @After
    fun tearDown() {
        runCatching { backend.close() }
        server.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `list 目录优先且名称大小写不敏感排序`() = runBlocking {
        val entries = backend.list("/")
        assertEquals(
            listOf("alpha", "Zeta", "apple.txt", "Beta.txt", "Cherry.txt", "movie.ts"),
            entries.map { it.name },
        )
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[1].isDirectory)
        assertFalse(entries[2].isDirectory)
    }

    @Test
    fun `list 过滤点目录`() = runBlocking {
        val names = backend.list("/").map { it.name }
        assertFalse(names.contains("."))
        assertFalse(names.contains(".."))
    }

    @Test
    fun `list 分页 offset 与 limit`() = runBlocking {
        val all = backend.list("/")
        val page = backend.list("/", Page(offset = 1, limit = 2))
        assertEquals(2, page.size)
        assertEquals(all[1].name, page[0].name)
        assertEquals(all[2].name, page[1].name)
    }

    @Test
    fun `list limit 小于等于 0 表示不限制`() = runBlocking {
        val all = backend.list("/")
        assertEquals(all.size, backend.list("/", Page(offset = 0, limit = 0)).size)
        assertEquals(all.size, backend.list("/", Page(offset = 0, limit = -5)).size)
    }

    @Test
    fun `list offset 超过总数返回空`() = runBlocking {
        assertTrue(backend.list("/", Page(offset = 999, limit = 10)).isEmpty())
    }

    @Test
    fun `list 子目录返回树内相对路径`() = runBlocking {
        val entries = backend.list("alpha")
        assertEquals(1, entries.size)
        assertEquals("inside.txt", entries[0].name)
        assertEquals("alpha/inside.txt", entries[0].path)
    }

    @Test
    fun `list 目录不存在抛 NotFound`() {
        val e = assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.list("/no-such-dir") }
        }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("不存在"))
    }

    @Test
    fun `list 目标是文件抛 NotSupported`() {
        assertThrows(StorageException.NotSupported::class.java) {
            runBlocking { backend.list("apple.txt") }
        }
    }

    @Test
    fun `stat 文件给出大小与毫秒时间戳`() = runBlocking {
        val entry = backend.stat("apple.txt")
        assertEquals("apple.txt", entry.name)
        assertEquals("apple.txt", entry.path)
        assertFalse(entry.isDirectory)
        assertEquals(1L, entry.size)
        assertTrue("mtime 应该是 Unix 毫秒：" + entry.mtime, entry.mtime > 1_600_000_000_000L)
    }

    @Test
    fun `stat 目录的大小是 -1`() = runBlocking {
        val entry = backend.stat("alpha")
        assertTrue(entry.isDirectory)
        assertEquals(-1L, entry.size)
        assertEquals("alpha", entry.name)
    }

    @Test
    fun `stat 不存在抛 NotFound`() {
        assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.stat("nope.txt") }
        }
    }

    @Test
    fun `list 根目录用空串等价于斜杠`() = runBlocking {
        assertEquals(backend.list("/").map { it.name }, backend.list("").map { it.name })
    }

    @Test
    fun `大目录分页拼接与全量一致`() = runBlocking {
        repeat(25) { index -> root.writeTextFile(String.format("bulk-%02d.txt", index), "n") }
        val all = backend.list("/")
        assertEquals(31, all.size)
        // 每页 5 条翻 7 页，拼起来必须与一次取全完全一致（分页不重不漏，plan 4.10）
        val paged = (0 until 7).flatMap { page -> backend.list("/", Page(offset = page * 5, limit = 5)) }
        assertEquals(all.map { it.name }, paged.map { it.name })
    }

    @Test
    fun `路径越界被拒`() {
        assertThrows(StorageException.AccessDenied::class.java) {
            runBlocking { backend.list("../etc") }
        }
    }

    @Test
    fun `basePath 之下的相对路径语义正确`() = runBlocking {
        val scoped = SftpStorageBackend(server.config(basePath = "/alpha"))
        try {
            val entries = scoped.list("/")
            assertEquals(listOf("inside.txt"), entries.map { it.name })
            assertEquals("inside.txt", entries[0].path)
            assertEquals("inside", text(readAll(scoped.openRead("inside.txt"))))
            // 越出 basePath 的相对路径在服务端不存在 → NotFound（不是越权访问）
            assertThrows(StorageException.NotFound::class.java) { runBlocking { scoped.stat("/Beta.txt") } }
            Unit
        } finally {
            scoped.close()
        }
    }

    @Test
    fun `关闭后端后操作直接失败`() {
        backend.close()
        assertThrows(StorageException.Unknown::class.java) { runBlocking { backend.list("/") } }
    }

    @Test
    fun `caps 声明随机读与通道池并行度`() {
        assertEquals(4, backend.caps.maxParallelReads)
        assertTrue(backend.caps.randomAccess)
        assertTrue(backend.caps.writable)
        assertFalse(backend.caps.rangeHeader)
        assertFalse(backend.caps.resumeByRest)
        assertEquals(2, SftpStorageBackend(server.config(maxChannels = 2)).caps.maxParallelReads)
    }

    @Test
    fun `id 带主机端口与根路径`() {
        assertEquals("sftp://" + server.username + "@127.0.0.1:" + server.port + "/", backend.id)
    }

    @Test
    fun `列表不返回目录的 size 冒充 0`() = runBlocking {
        backend.list("/").filter { it.isDirectory }.forEach { entry ->
            assertEquals(entry.name, -1L, entry.size)
        }
    }

    @Test
    fun `空目录返回空列表`() = runBlocking {
        Files.createDirectories(root.resolve("empty"))
        assertTrue(backend.list("empty").isEmpty())
    }
}
