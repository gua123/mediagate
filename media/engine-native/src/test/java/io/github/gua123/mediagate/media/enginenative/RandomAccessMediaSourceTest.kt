package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.api.asRandomAccessSource
import io.github.gua123.mediagate.data.storage.local.FileStorageBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * 自研引擎"读我们数据层"这条链路的 JVM 单测（**不需要设备**）。
 *
 * 引擎走数据层（本地 / SAF / WebDAV / SFTP / FTP + 分段缓存 + 超时层）全靠
 * [RandomAccessMediaSource] 这一层桥接：native 通过 JNI 调 [RandomAccessMediaSource.read]，
 * 它再用 runBlocking 把挂起的数据层同步化。这里用真实临时文件 + 真 [FileStorageBackend]
 * 把"偏移 / 长度 / EOF / 出错"四种情形都钉住。
 */
class RandomAccessMediaSourceTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val payload = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray(StandardCharsets.UTF_8)

    private fun localSource(): RandomAccessSource {
        val root = temporary.newFolder("root")
        File(root, "sample.bin").writeBytes(payload)
        val backend = FileStorageBackend(root)
        val stream = runBlocking { backend.openRead("sample.bin") }
        return stream.asRandomAccessSource(payload.size.toLong())
    }

    private fun read(m: RandomAccessMediaSource, offset: Long, size: Int): Pair<Int, ByteArray> {
        val buffer = ByteBuffer.allocateDirect(size)
        val count = m.read(offset, buffer, size)
        val out = ByteArray(maxOf(count, 0))
        if (count > 0) {
            buffer.flip()
            buffer.get(out)
        }
        return count to out
    }

    @Test
    fun 读本地文件_偏移与长度都要对() {
        val source = RandomAccessMediaSource(localSource())
        assertEquals(payload.size.toLong(), source.length())

        val (n, bytes) = read(source, 0, 8)
        assertEquals(8, n)
        assertEquals("01234567", String(bytes))

        val (m, mid) = read(source, 10, 5)
        assertEquals(5, m)
        assertEquals("abcde", String(mid))

        // 末尾只够 4 个字节时如实返回 4（不是报错）
        val (tail, tailBytes) = read(source, payload.size - 4L, 16)
        assertEquals(4, tail)
        assertEquals("wxyz", String(tailBytes))
    }

    @Test
    fun 读到末尾返回_EOF_而不是抛异常() {
        val source = RandomAccessMediaSource(localSource())
        val (n, _) = read(source, payload.size.toLong(), 16)
        assertEquals(NativeMediaSource.EOF, n)
        val (far, _) = read(source, payload.size + 1000L, 16)
        assertEquals(NativeMediaSource.EOF, far)
    }

    @Test
    fun 反复读同一块中转数组也不能串数据() {
        // 实现复用了内部中转数组（每次读 32–64 KB，不能每次都新分配）——
        // 这里前后交错读，验证复用的数组不会把上一次的内容漏出来。
        val source = RandomAccessMediaSource(localSource())
        val (_, first) = read(source, 0, 6)
        val (_, other) = read(source, 20, 6)
        val (_, again) = read(source, 0, 6)
        assertEquals("012345", String(first))
        assertEquals("klmnop", String(other))
        assertEquals("012345", String(again))
    }

    @Test
    fun 长度未知的数据源要如实报_1() {
        val unknown = object : RandomAccessSource {
            override val size: Long = -1
            override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = -1
            override suspend fun readFully(offset: Long, len: Int): ByteArray = ByteArray(0)
            override fun close() = Unit
        }
        assertEquals(-1L, RandomAccessMediaSource(unknown).length())
    }

    @Test
    fun 数据层报错要翻成中文原因并返回_2() {
        // 库/网络抛的英文 message 不能直接进界面（单语红线）：这里必须是中文说明 + -2
        val broken = object : RandomAccessSource {
            override val size: Long = 100
            override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
                throw StorageException.Unknown("connection reset by peer")

            override suspend fun readFully(offset: Long, len: Int): ByteArray =
                throw StorageException.Unknown("connection reset by peer")

            override fun close() = Unit
        }
        val source = RandomAccessMediaSource(broken, ownsSource = false)
        val (n, _) = read(source, 0, 8)
        assertEquals(NativeMediaSource.ERROR, n)
        val reason = source.lastError()
        assertNotNull("出错必须留下原因，否则界面上只会看到「读着读着停了」", reason)
        assertTrue("原因要是中文（库英文不能直接显示）：$reason", reason!!.any { it.code > 127 })
    }

    @Test
    fun 非法偏移不许闷头去读() {
        val source = RandomAccessMediaSource(localSource())
        val (n, _) = read(source, -1, 8)
        assertEquals(NativeMediaSource.ERROR, n)
        assertNotNull(source.lastError())
    }

    @Test
    fun 自己负责关闭时可选择不接管数据源生命周期() {
        var closed = false
        val tracked = object : RandomAccessSource {
            override val size: Long = 8
            override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = -1
            override suspend fun readFully(offset: Long, len: Int): ByteArray = ByteArray(0)
            override fun close() {
                closed = true
            }
        }
        RandomAccessMediaSource(tracked).close()
        assertTrue("默认应当关掉底层数据源", closed)

        closed = false
        RandomAccessMediaSource(tracked, ownsSource = false).close()
        assertTrue("显式声明不接管时不许关（数据源可能被别处共用）", !closed)
    }
}
