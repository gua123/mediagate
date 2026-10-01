package io.github.gua123.mediagate.data.storage.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.nio.file.Path

/**
 * FTP 区间读与 REST 随机读（**R4** 拖拽 seek / plan 4.1「RETR + REST」与降级链）。
 *
 * 两组服务器对照：
 * - 正常服务器：**REST + RETR**，随机偏移读到的字节必须等于文件对应偏移的字节；
 * - 禁掉 REST 的服务器：能力探测后 [FtpStorageBackend.caps] 的 randomAccess 变 false，
 *   `openRead(offset>0)` 走「从 0 顺序跳过」的降级路径（结果字节仍必须正确），
 *   而 `seek` 抛 NotSupported（plan 4.1 的分段缓存降级交给上层）。
 */
class FtpBackendReadTest {

    private lateinit var root: Path
    private lateinit var server: FtpTestServer
    private lateinit var backend: FtpStorageBackend
    private var noRestServer: FtpTestServer? = null
    private var noRestBackend: FtpStorageBackend? = null

    private val bigSize = 100_000
    private val big: ByteArray get() = sampleBytes(bigSize)

    @Before
    fun setUp() {
        root = newTempDir("ftp-read")
        root.writeFile("big.bin", sampleBytes(bigSize))
        root.writeTextFile("small.txt", "0123456789abcdefghij")
        root.writeFile("empty.bin", ByteArray(0))
        server = FtpTestServer(root)
        backend = FtpStorageBackend(server.config())
    }

    @After
    fun tearDown() {
        runCatching { backend.close() }
        runCatching { noRestBackend?.close() }
        server.close()
        noRestServer?.close()
        root.toFile().deleteRecursively()
    }

    /** 禁掉 REST 的服务器（模拟不支持断点定位的老服务器）。 */
    private fun noRestBackend(): FtpStorageBackend {
        if (noRestBackend == null) {
            noRestServer = FtpTestServer(root, disabledCommands = setOf("REST"), port = FtpTestServer.freePort())
            noRestBackend = FtpStorageBackend(noRestServer!!.config())
        }
        return noRestBackend!!
    }

    private suspend fun readSlice(path: String, offset: Long, length: Long): ByteArray =
        backend.openRead(path, offset, length).use { stream -> readExactly(stream, length.toInt()) }

    @Test
    fun `length 为 -1 时一路读到 EOF 且内容一致`() = runBlocking {
        val bytes = backend.openRead("big.bin", 0L, -1L).use { readAll(it) }
        assertEquals(bigSize, bytes.size)
        assertArrayEquals(big, bytes)
    }

    @Test
    fun `偏移 0 读 100 字节精确`() = runBlocking {
        val bytes = readSlice("big.bin", 0L, 100L)
        assertEquals(100, bytes.size)
        assertArrayEquals(big.copyOfRange(0, 100), bytes)
    }

    @Test
    fun `偏移 200 读 100 字节精确且与前一段不同`() = runBlocking {
        val head = readSlice("big.bin", 0L, 100L)
        val mid = readSlice("big.bin", 200L, 100L)
        assertEquals(100, mid.size)
        assertArrayEquals(big.copyOfRange(200, 300), mid)
        assertFalse("两段内容相同说明 REST 没生效（从头读了）", head.contentEquals(mid))
    }

    @Test
    fun `多个随机偏移都读到正确字节（REST 生效）`() = runBlocking {
        val offsets = listOf(0L, 1L, 7L, 63L, 251L, 1_000L, 4_096L, 9_999L, 50_000L, 90_000L, 99_900L)
        for (offset in offsets) {
            val length = minOf(64L, bigSize - offset)
            val bytes = readSlice("big.bin", offset, length)
            assertEquals("偏移 " + offset + " 的长度不对", length.toInt(), bytes.size)
            assertArrayEquals(
                "偏移 " + offset + " 的字节不对（疑似没走 REST）",
                big.copyOfRange(offset.toInt(), (offset + length).toInt()),
                bytes,
            )
        }
    }

    @Test
    fun `深偏移读取不会读到错误内容`() = runBlocking {
        backend.openRead("big.bin", 0L, -1L).use { stream ->
            val head = ByteArray(16)
            assertEquals(16, stream.read(head, 0, 16))
            assertArrayEquals(big.copyOfRange(0, 16), head)
            stream.seek(90_000L)
            val tail = ByteArray(32)
            var read = 0
            while (read < 32) {
                val n = stream.read(tail, read, 32 - read)
                if (n < 0) break
                read += n
            }
            assertEquals(32, read)
            assertArrayEquals(big.copyOfRange(90_000, 90_032), tail)
        }
    }

    @Test
    fun `偏移等于文件长度时直接 EOF`() = runBlocking {
        backend.openRead("big.bin", bigSize.toLong(), -1L).use { stream ->
            assertEquals(0L, stream.length)
            assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        }
    }

    @Test
    fun `偏移越界返回 EOF 不抛异常`() = runBlocking {
        backend.openRead("big.bin", bigSize + 5_000L, 100L).use { stream ->
            assertEquals(0L, stream.length)
            assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        }
    }

    @Test
    fun `length 为 0 时立刻 EOF`() = runBlocking {
        backend.openRead("big.bin", 10L, 0L).use { stream ->
            assertEquals(0L, stream.length)
            assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        }
    }

    @Test
    fun `从中间读到 EOF 的长度等于剩余字节`() = runBlocking {
        val offset = 99_000L
        backend.openRead("big.bin", offset, -1L).use { stream ->
            assertEquals(bigSize - offset, stream.length)
            val bytes = readAll(stream)
            assertEquals((bigSize - offset).toInt(), bytes.size)
            assertArrayEquals(big.copyOfRange(offset.toInt(), bigSize), bytes)
        }
    }

    @Test
    fun `流内 seek 后继续读正确`() = runBlocking {
        backend.openRead("big.bin", 1_000L, 500L).use { stream ->
            val first = ByteArray(50)
            var read = 0
            while (read < 50) {
                val n = stream.read(first, read, 50 - read)
                if (n < 0) break
                read += n
            }
            assertArrayEquals(big.copyOfRange(1_000, 1_050), first)
            stream.seek(200L)
            assertEquals(200L, stream.position())
            val second = ByteArray(50)
            var read2 = 0
            while (read2 < 50) {
                val n = stream.read(second, read2, 50 - read2)
                if (n < 0) break
                read2 += n
            }
            assertArrayEquals(big.copyOfRange(1_200, 1_250), second)
            stream.seek(0L)
            val back = ByteArray(20)
            stream.read(back, 0, 20)
            assertArrayEquals(big.copyOfRange(1_000, 1_020), back)
        }
    }

    @Test
    fun `position 以流起点为 0`() = runBlocking {
        backend.openRead("big.bin", 50_000L, 100L).use { stream ->
            assertEquals(0L, stream.position())
            val buffer = ByteArray(10)
            stream.read(buffer, 0, 10)
            assertEquals(10L, stream.position())
            stream.seek(90L)
            assertEquals(90L, stream.position())
        }
    }

    @Test
    fun `seek 超出流长度被夹到末尾`() = runBlocking {
        backend.openRead("big.bin", 0L, 100L).use { stream ->
            stream.seek(9_999L)
            assertEquals(100L, stream.position())
            assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        }
    }

    @Test
    fun `分段拼接与全量逐字节一致`() = runBlocking {
        val whole = backend.openRead("big.bin", 0L, -1L).use { readAll(it) }
        val pieces = listOf(0L to 1_000L, 1_000L to 5_000L, 6_000L to 40_000L, 46_000L to 54_000L)
            .map { (offset, length) -> readSlice("big.bin", offset, length) }
        val joined = pieces.reduce { acc, bytes -> acc + bytes }
        assertEquals(100_000, joined.size)
        assertArrayEquals(whole, joined)
    }

    @Test
    fun `顺序分块读与大块读一致`() = runBlocking {
        backend.openRead("big.bin", 0L, -1L).use { stream ->
            val collected = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val n = stream.read(buffer, 0, buffer.size)
                if (n < 0) break
                collected.write(buffer, 0, n)
            }
            assertArrayEquals(big, collected.toByteArray())
        }
    }

    @Test
    fun `并发两路读互不干扰（连接池）`() = runBlocking {
        val ranges = listOf(0L to 8_192L, 40_000L to 8_192L)
        val results = withContext(Dispatchers.IO) {
            ranges.map { (offset, length) -> async { readSlice("big.bin", offset, length) } }.awaitAll()
        }
        results.forEachIndexed { index, bytes ->
            val (offset, length) = ranges[index]
            assertEquals(length.toInt(), bytes.size)
            assertArrayEquals(big.copyOfRange(offset.toInt(), (offset + length).toInt()), bytes)
        }
    }

    @Test
    fun `小文件区间读与全量一致`() = runBlocking {
        assertArrayEquals(utf8("0123456789abcdefghij"), backend.openRead("small.txt", 0L, -1L).use { readAll(it) })
        assertArrayEquals(utf8("456789"), readSlice("small.txt", 4L, 6L))
        // 读到底就停（请求比剩余多）
        assertArrayEquals(utf8("hij"), readSlice("small.txt", 17L, 100L))
    }

    @Test
    fun `空文件读直接 EOF`() = runBlocking {
        backend.openRead("empty.bin", 0L, -1L).use { stream ->
            assertEquals(0L, stream.length)
            assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        }
    }

    @Test
    fun `读不存在的文件抛 NotFound`() {
        assertThrows(StorageException.NotFound::class.java) {
            runBlocking { backend.openRead("ghost.bin", 0L, 10L) }
        }
    }

    @Test
    fun `读目录抛 NotSupported`() {
        assertThrows(StorageException.NotSupported::class.java) {
            runBlocking { backend.openRead("", 0L, 10L) }
        }
    }

    @Test
    fun `负偏移被拒`() {
        assertThrows(StorageException.Unknown::class.java) {
            runBlocking { backend.openRead("big.bin", -1L, 10L) }
        }
    }

    @Test
    fun `close 幂等且关闭后再读返回 EOF`() = runBlocking {
        val stream: RangeStream = backend.openRead("big.bin", 0L, 100L)
        stream.close()
        stream.close()
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `probe 能探测出服务器支持 REST`() = runBlocking {
        assertTrue(backend.probe().ok)
        assertTrue(backend.caps.randomAccess)
        assertTrue(backend.caps.resumeByRest)
    }

    @Test
    fun `不支持 REST 时 caps 降级但仍能按偏移读到正确字节`() = runBlocking {
        val scoped = noRestBackend()
        assertTrue("还没探测时是乐观值", scoped.caps.randomAccess)
        val bytes = scoped.openRead("big.bin", 50_000L, 64L).use { readExactly(it, 64) }
        assertArrayEquals(big.copyOfRange(50_000, 50_064), bytes)
        assertFalse("服务器拒绝 REST 后必须降级", scoped.caps.randomAccess)
        assertFalse(scoped.caps.resumeByRest)
    }

    @Test
    fun `不支持 REST 时 probe 之后 caps 就是降级值`() = runBlocking {
        val scoped = noRestBackend()
        assertTrue(scoped.probe().ok)
        assertFalse("probe 会发 REST 0 探测能力", scoped.caps.randomAccess)
    }

    @Test
    fun `不支持 REST 时 seek 抛 NotSupported（交给上层走分段缓存）`() = runBlocking {
        val scoped = noRestBackend()
        // 先触发一次偏移读把能力降级下来
        scoped.openRead("big.bin", 10L, 10L).use { readExactly(it, 10) }
        val stream = scoped.openRead("big.bin", 0L, 1_000L)
        try {
            assertThrows(StorageException.NotSupported::class.java) {
                runBlocking { stream.seek(500L) }
            }
            Unit
        } finally {
            stream.close()
        }
    }

    @Test
    fun `不支持 REST 时全量读仍然正确`() = runBlocking {
        val scoped = noRestBackend()
        val bytes = scoped.openRead("big.bin", 0L, -1L).use { readAll(it) }
        assertArrayEquals(big, bytes)
    }
}
