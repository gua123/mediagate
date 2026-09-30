package io.github.gua123.mediagate.data.storage.sftp

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
 * SFTP 区间读与随机读（**R4** 拖拽 seek / plan 4.1「SSH_FXP_READ + offset，直接按偏移读」）。
 *
 * 这一组是 M5 最关键的证据：**读到的字节必须等于文件里对应偏移的字节**，
 * 且换一个偏移读不能变成「从头 skip」——所以既比对内容，也比对多偏移的一致性。
 */
class SftpBackendReadTest {

    private lateinit var root: Path
    private lateinit var server: SftpTestServer
    private lateinit var backend: SftpStorageBackend

    /** 100 KB 可验证数据（第 i 字节 = (i*31+7) mod 251）。 */
    private val bigSize = 100_000
    private val big: ByteArray get() = sampleBytes(bigSize)

    @Before
    fun setUp() {
        root = newTempDir("sftp-read")
        root.writeFile("big.bin", sampleBytes(bigSize))
        root.writeTextFile("small.txt", "0123456789abcdefghij")
        root.writeFile("empty.bin", ByteArray(0))
        server = SftpTestServer(root)
        backend = SftpStorageBackend(server.config())
    }

    @After
    fun tearDown() {
        runCatching { backend.close() }
        server.close()
        root.toFile().deleteRecursively()
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
        assertFalse("两段内容相同说明服务器/实现忽略了偏移", head.contentEquals(mid))
    }

    @Test
    fun `多个随机偏移都读到正确字节（真按偏移读）`() = runBlocking {
        val offsets = listOf(0L, 1L, 7L, 63L, 251L, 1_000L, 4_096L, 9_999L, 50_000L, 90_000L, 99_900L)
        for (offset in offsets) {
            val length = minOf(64L, bigSize - offset)
            val bytes = readSlice("big.bin", offset, length)
            assertEquals("偏移 " + offset + " 的长度不对", length.toInt(), bytes.size)
            assertArrayEquals(
                "偏移 " + offset + " 的字节不对（疑似从头顺序读）",
                big.copyOfRange(offset.toInt(), (offset + length).toInt()),
                bytes,
            )
        }
    }

    @Test
    fun `深偏移单次读不会读到错误内容`() = runBlocking {
        // 先在流上顺序读一小段，再 seek 到很后面：模拟播放器拖拽
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
        val stream = backend.openRead("big.bin", bigSize.toLong(), -1L)
        stream.use {
            assertEquals(0L, it.length)
            assertEquals(-1, it.read(ByteArray(8), 0, 8))
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
            assertEquals(50, read)
            assertArrayEquals(big.copyOfRange(1_000, 1_050), first)
            // seek 是「流内相对位置」：seek(200) 对应文件 1200
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
            // 再跳回去也要对（连续拖拽 20 次的简化版）
            stream.seek(0L)
            val back = ByteArray(20)
            stream.read(back, 0, 20)
            assertArrayEquals(big.copyOfRange(1_000, 1_020), back)
        }
    }

    @Test
    fun `position 以流起点为 0 而不是文件绝对偏移`() = runBlocking {
        backend.openRead("big.bin", 50_000L, 100L).use { stream ->
            assertEquals(0L, stream.position())
            val buffer = ByteArray(10)
            stream.read(buffer, 0, 10)
            assertEquals(10L, stream.position())
            stream.seek(90L)
            assertEquals(90L, stream.position())
            assertArrayEquals(big.copyOfRange(50_090, 50_100), ByteArray(10).also { stream.read(it, 0, 10) })
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
    fun `seek 到负数是 0`() = runBlocking {
        backend.openRead("big.bin", 0L, 100L).use { stream ->
            stream.seek(-5L)
            assertEquals(0L, stream.position())
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
    fun `并发四路读同一文件互不干扰（通道池）`() = runBlocking {
        val ranges = listOf(0L to 8_192L, 20_000L to 8_192L, 50_000L to 8_192L, 80_000L to 8_192L)
        val results = withContext(Dispatchers.IO) {
            ranges.map { (offset, length) ->
                async { readSlice("big.bin", offset, length) }
            }.awaitAll()
        }
        results.forEachIndexed { index, bytes ->
            val (offset, length) = ranges[index]
            assertEquals(length.toInt(), bytes.size)
            assertArrayEquals(big.copyOfRange(offset.toInt(), (offset + length).toInt()), bytes)
        }
    }

    @Test
    fun `并发读超过通道池大小也能全部完成`() = runBlocking {
        val pool = SftpStorageBackend(server.config(maxChannels = 2))
        try {
            val results = withContext(Dispatchers.IO) {
                (0 until 6).map { index ->
                    async { pool.openRead("big.bin", index * 1_000L, 500L).use { readAll(it) } }
                }.awaitAll()
            }
            results.forEachIndexed { index, bytes ->
                assertArrayEquals(big.copyOfRange(index * 1_000, index * 1_000 + 500), bytes)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun `小文件区间读与全量一致`() = runBlocking {
        val expected = utf8("0123456789abcdefghij")
        assertArrayEquals(expected, backend.openRead("small.txt", 0L, -1L).use { readAll(it) })
        assertArrayEquals(utf8("456789"), readSlice("small.txt", 4L, 6L))
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
    fun `close 幂等且关闭后再读返回 EOF 或异常`() = runBlocking {
        val stream: RangeStream = backend.openRead("big.bin", 0L, 100L)
        stream.close()
        stream.close()
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `跨连接复用后仍能按偏移读（同一后端多次 openRead）`() = runBlocking {
        repeat(5) { index ->
            val bytes = readSlice("big.bin", index * 10_000L, 256L)
            assertArrayEquals(big.copyOfRange(index * 10_000, index * 10_000 + 256), bytes)
        }
    }
}
