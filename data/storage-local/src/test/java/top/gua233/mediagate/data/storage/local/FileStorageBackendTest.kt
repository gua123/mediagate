package io.github.gua123.mediagate.data.storage.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.api.asRandomAccessSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [FileStorageBackend] 的 JVM 单测（R4 随机读 / R12 本地模式）。
 *
 * 全部用真实文件与临时目录跑，不 mock 文件系统；SAF 后端需要设备（ContentResolver），
 * 因此这里只测 File 模式，SAF 侧靠编译期检查 + 真机验收。
 */
class FileStorageBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var backend: FileStorageBackend

    @Before
    fun setUp() {
        root = tmp.newFolder("root")
        backend = FileStorageBackend(root)
    }

    @Test
    fun `读全量直到 EOF`() = runTest {
        writeFile("a.bin", "abcdefghij")
        backend.openRead("a.bin").use { stream ->
            assertEquals(10L, stream.length)
            val buf = ByteArray(4)
            assertEquals(4, stream.read(buf, 0, 4))
            assertEquals("abcd", String(buf))
            assertEquals(4L, stream.position())
        }
        val all = backend.openRead("a.bin").use { readAll(it) }
        assertEquals("abcdefghij", String(all))
    }

    @Test
    fun `读区间 offset 与 length`() = runTest {
        writeFile("a.bin", "abcdefghij")
        backend.openRead("a.bin", offset = 2, length = 3).use { stream ->
            assertEquals(3L, stream.length)
            assertEquals(0L, stream.position())
            val buf = ByteArray(8)
            assertEquals(3, stream.read(buf, 0, 8))
            assertEquals("cde", String(buf, 0, 3))
            assertEquals(-1, stream.read(buf, 0, 8))
        }
        // offset 与 length 都用默认值、只给 offset
        backend.openRead("a.bin", offset = 7).use { stream ->
            assertEquals(3L, stream.length)
            assertEquals("hij", String(readAll(stream)))
        }
    }

    @Test
    fun `越界读返回 EOF 而不抛异常`() = runTest {
        writeFile("a.bin", "abc")
        // 起点已在文件末尾之后
        backend.openRead("a.bin", offset = 10).use { stream ->
            assertEquals(0L, stream.length)
            assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        }
        // 请求长度超过剩余：按剩余截断
        backend.openRead("a.bin", offset = 1, length = 100).use { stream ->
            assertEquals(2L, stream.length)
            val buf = ByteArray(8)
            assertEquals(2, stream.read(buf, 0, 8))
            assertEquals("bc", String(buf, 0, 2))
            assertEquals(-1, stream.read(buf, 0, 8))
        }
    }

    @Test
    fun `seek 后继续读`() = runTest {
        writeFile("a.bin", "0123456789")
        backend.openRead("a.bin").use { stream ->
            val buf = ByteArray(4)
            assertEquals(4, stream.read(buf, 0, 4))
            assertEquals("0123", String(buf))
            stream.seek(8)
            assertEquals(8L, stream.position())
            assertEquals(2, stream.read(buf, 0, 4))
            assertEquals("89", String(buf, 0, 2))
            // seek 超过流长度会被夹到末尾，读到 EOF
            stream.seek(100)
            assertEquals(10L, stream.position())
            assertEquals(-1, stream.read(buf, 0, 4))
        }
        // 带 offset 的流：position 以流起点为 0
        backend.openRead("a.bin", offset = 4).use { stream ->
            val buf = ByteArray(2)
            stream.seek(2)
            assertEquals(2L, stream.position())
            assertEquals(2, stream.read(buf, 0, 2))
            assertEquals("67", String(buf))
        }
    }

    @Test
    fun `list 目录优先排序与 isDirectory`() = runTest {
        writeFile("b.txt", "b")
        writeFile("A.txt", "a")
        writeFile("aDir/inner.txt", "x")
        File(root, "zDir").mkdirs()

        val entries = backend.list("")
        // 目录优先（aDir、zDir），再按名称大小写不敏感排序（A.txt、b.txt）
        assertEquals(listOf("aDir", "zDir", "A.txt", "b.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[1].isDirectory)
        assertFalse(entries[2].isDirectory)
        assertEquals("aDir", entries[0].path)
        assertEquals(-1L, entries[0].size)
        // 不递归：aDir 里的文件不在当前层
        assertEquals(listOf("aDir", "zDir", "A.txt", "b.txt"), backend.list("/").map { it.name })

        val sub = backend.list("aDir")
        assertEquals(listOf("inner.txt"), sub.map { it.name })
        assertEquals("aDir/inner.txt", sub.first().path)
    }

    @Test
    fun `list 分页`() = runTest {
        writeFile("1.txt", "1")
        writeFile("2.txt", "2")
        writeFile("3.txt", "3")
        assertEquals(listOf("2.txt", "3.txt"), backend.list("", Page(offset = 1, limit = 2)).map { it.name })
        assertEquals(listOf("3.txt"), backend.list("", Page(offset = 2, limit = 5)).map { it.name })
        assertTrue(backend.list("", Page(offset = 9, limit = 2)).isEmpty())
        assertEquals(3, backend.list("", Page(offset = 0, limit = 0)).size)
    }

    @Test
    fun `stat 返回 size 与 mtime`() = runTest {
        val before = System.currentTimeMillis()
        writeFile("dir/x.bin", "0123456789")
        val entry = backend.stat("dir/x.bin")
        assertEquals("x.bin", entry.name)
        assertEquals("dir/x.bin", entry.path)
        assertFalse(entry.isDirectory)
        assertEquals(10L, entry.size)
        // mtime 断言放宽：只要不早于写入前的时间戳即可（文件系统精度不一）
        assertTrue("mtime=${entry.mtime} before=$before", entry.mtime >= before)
        assertNull(entry.etag)

        val dir = backend.stat("dir")
        assertTrue(dir.isDirectory)
        assertEquals(-1L, dir.size)
    }

    @Test
    fun `write 覆盖写与父目录缺失`() = runTest {
        writeFile("a.txt", "hello world")
        backend.write("a.txt", ByteArrayInputStream("bye".toByteArray()))
        assertEquals("bye", File(root, "a.txt").readText())
        assertEquals(3L, backend.stat("a.txt").size)

        // 覆盖写：更长的内容也不残留旧字节
        backend.write("a.txt", ByteArrayInputStream("longer-content".toByteArray()))
        assertEquals("longer-content", File(root, "a.txt").readText())

        // 新文件写到哪里：父目录必须已存在
        val err = runCatching {
            backend.write("nope/a.txt", ByteArrayInputStream("x".toByteArray()))
        }.exceptionOrNull()
        assertTrue("实际异常：$err", err is StorageException.NotFound)
        assertFalse(File(root, "nope/a.txt").exists())
    }

    @Test
    fun `NotFound 与其余异常分类`() = runTest {
        assertTrue(runCatching { backend.stat("missing.bin") }.exceptionOrNull() is StorageException.NotFound)
        assertTrue(runCatching { backend.list("missingDir") }.exceptionOrNull() is StorageException.NotFound)
        assertTrue(runCatching { backend.openRead("missing.bin") }.exceptionOrNull() is StorageException.NotFound)

        writeFile("a.bin", "abc")
        File(root, "sub").mkdirs()
        // 对文件列目录 / 对目录开读流：是「不支持」而不是「不存在」
        assertTrue(runCatching { backend.list("a.bin") }.exceptionOrNull() is StorageException.NotSupported)
        assertTrue(runCatching { backend.openRead("sub") }.exceptionOrNull() is StorageException.NotSupported)
        // 越出根目录的路径直接拒绝
        assertTrue(runCatching { backend.openRead("a.bin/../a.bin") }.exceptionOrNull() is StorageException.AccessDenied)
    }

    @Test
    fun `RangeStream 可适配为 RandomAccessSource`() = runTest {
        writeFile("a.bin", "0123456789")
        backend.openRead("a.bin").asRandomAccessSource().use { source ->
            assertEquals(10L, source.size)
            val buf = ByteArray(3)
            assertEquals(3, source.readAt(4, buf, 0, 3))
            assertEquals("456", String(buf))
            // 越尾只返回能读到的部分
            assertEquals("789", String(source.readFully(7, 10)))
            assertEquals(0, source.readFully(20, 4).size)
            assertEquals(-1, source.readAt(10, buf, 0, 3))
        }
    }

    @Test
    fun `caps 与 probe`() = runTest {
        assertTrue(backend.caps.randomAccess)
        assertFalse(backend.caps.rangeHeader)
        assertFalse(backend.caps.resumeByRest)
        assertTrue(backend.caps.maxParallelReads >= 4)
        assertTrue(backend.caps.writable)
        assertTrue(backend.id.startsWith("local-file:"))

        val report = backend.probe()
        assertTrue(report.ok)
        assertEquals(0L, report.dnsMs)
        assertEquals(0L, report.connectMs)
        assertEquals(0L, report.handshakeMs)
        assertEquals(0L, report.totalMs)
        assertNull(report.message)
    }

    @Test
    fun `close 之后不可再用`() = runTest {
        writeFile("a.bin", "abc")
        backend.close()
        val err = runCatching { backend.stat("a.bin") }.exceptionOrNull()
        assertTrue("实际异常：$err", err is StorageException.Unknown)
    }

    private fun writeFile(relative: String, content: String): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private suspend fun readAll(stream: RangeStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16)
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
