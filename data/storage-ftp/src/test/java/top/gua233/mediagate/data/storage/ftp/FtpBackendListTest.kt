package io.github.gua123.mediagate.data.storage.ftp

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
 * FTP 列目录 / stat（**R2** / plan 4.1「MLSD→LIST」）。
 *
 * 同一份目录树分别用支持 MLSD 的服务器与禁用 MLSD 的服务器列一遍：
 * 两条路径必须给出**完全一样的排序结果**（这正是「不支持就退回 LIST 解析」要保证的事）。
 */
class FtpBackendListTest {

    private lateinit var root: Path
    private lateinit var server: FtpTestServer
    private lateinit var backend: FtpStorageBackend
    private var fallbackServer: FtpTestServer? = null
    private var fallbackBackend: FtpStorageBackend? = null

    @Before
    fun setUp() {
        root = newTempDir("ftp-list")
        root.writeTextFile("alpha/inside.txt", "inside")
        root.writeTextFile("Zeta/z.txt", "z")
        root.writeTextFile("apple.txt", "a")
        root.writeTextFile("Beta.txt", "bb")
        root.writeTextFile("Cherry.txt", "ccc")
        root.writeFile("movie.ts", sampleBytes(2048))
        root.writeTextFile("含空格 的 名字.txt", "spaces")
        server = FtpTestServer(root, disabledCommands = setOf("MLST", "OPTS"))
        backend = FtpStorageBackend(server.config())
    }

    @After
    fun tearDown() {
        runCatching { backend.close() }
        runCatching { fallbackBackend?.close() }
        server.close()
        fallbackServer?.close()
        root.toFile().deleteRecursively()
    }

    /** 禁用 MLSD 的服务器（模拟老服务器 → 必须走 LIST 回退）。 */
    private fun listFallbackBackend(): FtpStorageBackend {
        if (fallbackBackend == null) {
            fallbackServer = FtpTestServer(root, disabledCommands = setOf("MLSD", "MLST", "OPTS"), port = FtpTestServer.freePort())
            fallbackBackend = FtpStorageBackend(fallbackServer!!.config())
        }
        return fallbackBackend!!
    }

    @Test
    fun `MLSD 路径下列目录目录优先且大小写不敏感排序`() = runBlocking {
        val entries = backend.list("/")
        assertEquals(
            listOf("alpha", "Zeta", "apple.txt", "Beta.txt", "Cherry.txt", "movie.ts", "含空格 的 名字.txt"),
            entries.map { it.name },
        )
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[1].isDirectory)
        assertFalse(entries[2].isDirectory)
        assertFalse(entries.any { it.name == "." || it.name == ".." })
    }

    @Test
    fun `MLSD 不可用时 LIST 回退结果一致`() = runBlocking {
        val mlsd = backend.list("/").map { it.name to it.isDirectory }
        val list = listFallbackBackend().list("/").map { it.name to it.isDirectory }
        assertEquals(mlsd, list)
        assertTrue("回退路径也要给出目录/文件判定", list.any { it.second })
    }

    @Test
    fun `分页 offset 与 limit`() = runBlocking {
        val all = backend.list("/")
        val page = backend.list("/", Page(offset = 1, limit = 2))
        assertEquals(2, page.size)
        assertEquals(all[1].name, page[0].name)
        assertEquals(all[2].name, page[1].name)
        assertEquals(all.size, backend.list("/", Page(offset = 0, limit = 0)).size)
        assertTrue(backend.list("/", Page(offset = 999, limit = 5)).isEmpty())
    }

    @Test
    fun `LIST 回退路径的分页也一致`() = runBlocking {
        val backend = listFallbackBackend()
        val all = backend.list("/")
        assertEquals(all.map { it.name }, backend.list("/", Page(offset = 0, limit = 4)).map { it.name } + backend.list("/", Page(offset = 4, limit = 100)).map { it.name })
    }

    @Test
    fun `子目录路径是树内相对路径`() = runBlocking {
        val entries = backend.list("alpha")
        assertEquals(listOf("inside.txt"), entries.map { it.name })
        assertEquals("alpha/inside.txt", entries[0].path)
    }

    @Test
    fun `list 目录不存在抛 NotFound`() {
        assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.list("/no-such-dir") }
        }
    }

    @Test
    fun `stat 文件给出精确大小`() = runBlocking {
        val entry = backend.stat("movie.ts")
        assertEquals("movie.ts", entry.name)
        assertEquals("movie.ts", entry.path)
        assertFalse(entry.isDirectory)
        assertEquals(2048L, entry.size)
        assertTrue("mtime 应该是 Unix 毫秒：" + entry.mtime, entry.mtime > 1_600_000_000_000L)
    }

    @Test
    fun `stat 目录大小为 -1`() = runBlocking {
        val entry = backend.stat("alpha")
        assertTrue(entry.isDirectory)
        assertEquals(-1L, entry.size)
    }

    @Test
    fun `stat 根目录是目录`() = runBlocking {
        assertTrue(backend.stat("/").isDirectory)
        assertTrue(backend.stat("").isDirectory)
    }

    @Test
    fun `stat 不存在抛 NotFound`() {
        assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.stat("ghost.txt") }
        }
    }

    @Test
    fun `含空格与中文的文件名可正常列出`() = runBlocking {
        assertTrue(backend.list("/").any { it.name == "含空格 的 名字.txt" })
        assertFalse(backend.stat("含空格 的 名字.txt").isDirectory)
    }

    @Test
    fun `basePath 之下的语义正确`() = runBlocking {
        val scoped = FtpStorageBackend(server.config(basePath = "/alpha"))
        try {
            assertEquals(listOf("inside.txt"), scoped.list("/").map { it.name })
            assertEquals("inside", text(readAll(scoped.openRead("inside.txt"))))
            assertThrows(StorageException.NotFound::class.java) { runBlocking { scoped.stat("Beta.txt") } }
            Unit
        } finally {
            scoped.close()
        }
    }

    @Test
    fun `空目录返回空列表`() = runBlocking {
        Files.createDirectories(root.resolve("empty"))
        assertTrue(backend.list("empty").isEmpty())
    }

    @Test
    fun `caps 按探测结果声明`() = runBlocking {
        assertTrue("还没探测时乐观认为支持 REST", backend.caps.randomAccess)
        assertEquals(server.config().maxConnections, backend.caps.maxParallelReads)
        assertTrue(backend.caps.writable)
        assertFalse(backend.caps.rangeHeader)
        assertEquals("ftp://" + server.username + "@127.0.0.1:" + server.port + "/", backend.id)
    }

    @Test
    fun `probe 后会按服务器真实能力定 caps`() = runBlocking {
        assertTrue(backend.probe().ok)
        assertTrue("本服务器支持 REST", backend.caps.randomAccess)
        assertTrue(backend.caps.resumeByRest)
    }

    @Test
    fun `关闭后操作直接失败`() {
        backend.close()
        assertThrows(StorageException.Unknown::class.java) { runBlocking { backend.list("/") } }
    }

    @Test
    fun `路径越界被拒`() {
        assertThrows(StorageException.AccessDenied::class.java) {
            runBlocking { backend.list("../etc") }
        }
    }

    @Test
    fun `目录项大小不冒充 0`() = runBlocking {
        backend.list("/").filter { it.isDirectory }.forEach { entry ->
            assertEquals(entry.name, -1L, entry.size)
        }
    }
}
