package io.github.gua123.mediagate.media.playback

import android.net.FakeUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.data.storage.local.FileStorageBackend
import java.io.File

/**
 * [BackendDataSource] 的 JVM 单测（R4 拖拽 seek / R18 后台播放的数据入口）。
 *
 * 覆盖：读全量、按长度分段读、从中间读、seek 后重新 open（多次 open）、越界 EOF、
 * 长度未知、读取请求被剩余量截断、错误映射（NotFound / AccessDenied / Network / Auth /
 * NotSupported / 非法 URI / 负偏移）、close 释放与重复 close。
 *
 * 真实后端用 [FileStorageBackend] + 临时文件（不碰 Android），
 * 需要构造特殊场景（长度未知、指定异常）时用 [FakeBackend] + [FakeRangeStream]。
 * Media3 的 DataSpec 用 [FakeUri] 造真实实例——路径链路上除了 android.net.Uri 的实现，
 * 其余全是生产代码。
 */
class BackendDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private lateinit var backend: FileStorageBackend

    /** 64 KiB 的确定性内容（模 251，便于定位错位）。 */
    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        root = tmp.newFolder("root")
        File(root, "a.bin").writeBytes(payload)
        File(root, "empty.bin").writeBytes(ByteArray(0))
        backend = FileStorageBackend(root)
    }

    // ------------------------------------------------------------ 正常读取

    @Test
    fun `读全量内容并在读完后退化为 EOF`() {
        val source = BackendDataSource(backend)

        val resolved = source.open(dataSpec(backend.id, "a.bin"))

        assertEquals("open 返回本段可读字节数", payload.size.toLong(), resolved)
        assertEquals(payload.size.toLong(), source.bytesRemaining)
        assertArrayEquals(payload, readAll(source))
        assertEquals("读完剩余量归零", 0L, source.bytesRemaining)
        assertEquals("再读就是 EOF", C.RESULT_END_OF_INPUT, source.read(ByteArray(16), 0, 16))
        source.close()
    }

    @Test
    fun `空文件读出零长度并立刻 EOF`() {
        val source = BackendDataSource(backend)

        assertEquals(0L, source.open(dataSpec(backend.id, "empty.bin")))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(16), 0, 16))
        source.close()
    }

    @Test
    fun `指定长度时只读这一段且请求被截断到剩余量`() {
        val source = BackendDataSource(backend)
        source.open(dataSpec(backend.id, "a.bin", position = 0, length = 4))

        val buffer = ByteArray(64)
        assertEquals("多余的长度必须被剩余量截断", 4, source.read(buffer, 0, buffer.size))
        assertEquals(0L, source.bytesRemaining)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertArrayEquals(payload.copyOfRange(0, 4), buffer.copyOfRange(0, 4))
        source.close()
    }

    @Test
    fun `从中间读到文件末尾`() {
        val start = 1000L
        val source = BackendDataSource(backend)

        assertEquals(payload.size - start, source.open(dataSpec(backend.id, "a.bin", position = start)))
        assertArrayEquals(payload.copyOfRange(start.toInt(), payload.size), readAll(source))
        source.close()
    }

    @Test
    fun `seek 后重新 open 是正常路径且内容正确`() {
        val source = BackendDataSource(backend)

        source.open(dataSpec(backend.id, "a.bin", position = 0, length = 8))
        assertArrayEquals(payload.copyOfRange(0, 8), readAll(source))

        // 模拟 Media3 的一次拖拽：关掉旧区间，从头开一个新的区间
        source.open(dataSpec(backend.id, "a.bin", position = 16, length = 8))
        assertArrayEquals(payload.copyOfRange(16, 24), readAll(source))

        source.open(dataSpec(backend.id, "a.bin"))
        assertArrayEquals(payload, readAll(source))
        source.close()
    }

    @Test
    fun `后端一次只给一小块时按实际读到的字节数推进`() {
        val backend = FakeBackend { _, _, length ->
            FakeRangeStream(payload, length = length, maxChunk = 7)
        }
        val source = BackendDataSource(backend)
        source.open(dataSpec(backend.id, "a.bin", position = 0, length = 20))

        val buffer = ByteArray(20)
        assertEquals(7, source.read(buffer, 0, buffer.size))
        assertEquals(13L, source.bytesRemaining)
        assertEquals(7, source.read(buffer, 7, 13))
        assertEquals(6L, source.bytesRemaining)
        assertEquals(6, source.read(buffer, 14, 6))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
        assertArrayEquals(payload.copyOfRange(0, 20), buffer)
        source.close()
    }

    // ------------------------------------------------------------ 越界与未知长度

    @Test
    fun `偏移正好在末尾时返回零长度并 EOF`() {
        val source = BackendDataSource(backend)

        val resolved = source.open(dataSpec(backend.id, "a.bin", position = payload.size.toLong()))

        assertEquals(0L, resolved)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
    }

    @Test
    fun `偏移超过末尾时同样返回 EOF 而不是异常`() {
        val source = BackendDataSource(backend)

        assertEquals(0L, source.open(dataSpec(backend.id, "a.bin", position = payload.size + 4096L)))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
    }

    @Test
    fun `长度未知时返回 LENGTH_UNSET 并一路读到 -1`() {
        val data = ByteArray(32) { it.toByte() }
        // length = -1：模拟「协议给不出长度」（如没有 Content-Length 的响应）
        val backend = FakeBackend { _, offset, _ -> FakeRangeStream(data, length = -1, start = offset.toInt()) }
        val source = BackendDataSource(backend)

        val resolved = source.open(dataSpec(backend.id, "unknown.bin"))

        assertEquals(C.LENGTH_UNSET.toLong(), resolved)
        assertEquals(C.LENGTH_UNSET.toLong(), source.bytesRemaining)
        assertArrayEquals(data, readAll(source))
        source.close()
    }

    @Test
    fun `read 的长度为 0 时直接返回 0`() {
        val source = BackendDataSource(backend)
        source.open(dataSpec(backend.id, "a.bin"))
        assertEquals(0, source.read(ByteArray(8), 0, 0))
        source.close()
    }

    // ------------------------------------------------------------ open 的入参映射

    @Test
    fun `open 把 DataSpec 的位置与长度原样透传给后端`() {
        val backend = FakeBackend { _, _, length -> FakeRangeStream(payload, length = length) }
        val source = BackendDataSource(backend)

        source.open(dataSpec(backend.id, "dir/a.bin", position = 4096, length = 2048))

        assertEquals(listOf(Triple("dir/a.bin", 4096L, 2048L)), backend.opens)
        source.close()
    }

    @Test
    fun `多次 open 会先关掉上一段流`() {
        val streams = mutableListOf<FakeRangeStream>()
        val backend = FakeBackend { _, _, length -> FakeRangeStream(payload, length = length).also { streams += it } }
        val source = BackendDataSource(backend)

        source.open(dataSpec(backend.id, "a.bin"))
        source.open(dataSpec(backend.id, "a.bin", position = 8, length = 4))

        assertEquals(2, streams.size)
        assertTrue("上一段流必须被关掉，否则远端会话/文件句柄会泄漏", streams[0].closed)
        assertFalse(streams[1].closed)
        source.close()
        assertTrue(streams[1].closed)
    }

    @Test
    @Suppress("DEPRECATION") // 断言里刻意引用 DataSourceException.POSITION_OUT_OF_RANGE 以固定 2008 这个取值
    fun `负偏移抛 POSITION_OUT_OF_RANGE`() {
        val source = BackendDataSource(backend)

        val error = assertThrows(DataSourceException::class.java) {
            source.openRange(position = -1, length = C.LENGTH_UNSET.toLong(), path = "a.bin")
        }

        assertEquals(
            "与 DataSourceException.POSITION_OUT_OF_RANGE 同为 2008",
            DataSourceException.POSITION_OUT_OF_RANGE,
            error.reason,
        )
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, error.reason)
    }

    @Test
    fun `长度小于 -1 抛 POSITION_OUT_OF_RANGE`() {
        val source = BackendDataSource(backend)

        val error = assertThrows(DataSourceException::class.java) {
            source.openRange(position = 0, length = -2, path = "a.bin")
        }

        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, error.reason)
    }

    @Test
    fun `不是伪 URI 的地址抛 DataSourceException`() {
        val source = BackendDataSource(backend)
        val spec = DataSpec(FakeUri("file:///storage/emulated/0/a.mp3"), 0, C.LENGTH_UNSET.toLong())

        val error = assertThrows(DataSourceException::class.java) { source.open(spec) }

        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
    }

    // ------------------------------------------------------------ 错误映射

    @Test
    fun `文件不存在映射成 FILE_NOT_FOUND`() {
        val source = BackendDataSource(backend)

        val error = assertThrows(DataSourceException::class.java) {
            source.open(dataSpec(backend.id, "missing.bin"))
        }

        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, error.reason)
        assertTrue("原始 StorageException 要保留在 cause 里", error.cause is StorageException.NotFound)
    }

    @Test
    fun `无权限映射成 NO_PERMISSION`() {
        val error = assertMappedError(StorageException.AccessDenied("无读权限"))
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, error.reason)
    }

    @Test
    fun `网络失败映射成 NETWORK_CONNECTION_FAILED`() {
        val error = assertMappedError(StorageException.Network("连不上"))
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, error.reason)
    }

    @Test
    fun `认证失败映射成 BAD_HTTP_STATUS`() {
        val error = assertMappedError(StorageException.Auth("密码错"))
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, error.reason)
    }

    @Test
    fun `不支持的操作映射成 IO_UNSPECIFIED`() {
        val error = assertMappedError(StorageException.NotSupported("是目录"))
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
    }

    @Test
    fun `未分类错误映射成 IO_UNSPECIFIED 且保留 cause`() {
        val error = assertMappedError(StorageException.Unknown("说不清"))
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
        assertTrue(error.cause is StorageException.Unknown)
    }

    @Test
    fun `非 StorageException 的 IO 失败也被包成 DataSourceException`() {
        val backend = FakeBackend { _, _, _ -> throw java.io.IOException("底层炸了") }
        val source = BackendDataSource(backend)

        val error = assertThrows(DataSourceException::class.java) {
            source.open(dataSpec(backend.id, "a.bin"))
        }

        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
        assertTrue(error.cause is java.io.IOException)
    }

    @Test
    fun `读取过程中出错也映射成 DataSourceException`() {
        val backend = FakeBackend { _, _, length -> FakeRangeStream(payload, length = length, failOnRead = true) }
        val source = BackendDataSource(backend)
        source.open(dataSpec(backend.id, "a.bin"))

        val error = assertThrows(DataSourceException::class.java) { source.read(ByteArray(8), 0, 8) }

        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
    }

    // ------------------------------------------------------------ 生命周期

    @Test
    fun `close 释放底层流且之后不能再读`() {
        val stream = FakeRangeStream(payload, length = -1)
        val backend = FakeBackend { _, _, _ -> stream }
        val source = BackendDataSource(backend)
        source.open(dataSpec(backend.id, "a.bin"))
        source.read(ByteArray(8), 0, 8)

        source.close()

        assertTrue("必须真的关掉底层流", stream.closed)
        val error = assertThrows(DataSourceException::class.java) { source.read(ByteArray(8), 0, 8) }
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
    }

    @Test
    fun `close 可以重复调用且未打开时也不抛`() {
        val source = BackendDataSource(backend)

        source.close()
        source.close()
    }

    @Test
    fun `close 之后仍能重新 open`() {
        val source = BackendDataSource(backend)

        source.open(dataSpec(backend.id, "a.bin", position = 0, length = 4))
        source.close()
        source.open(dataSpec(backend.id, "a.bin", position = 8, length = 4))

        assertArrayEquals(payload.copyOfRange(8, 12), readAll(source))
        source.close()
    }

    @Test
    fun `未打开就 read 抛 DataSourceException`() {
        val source = BackendDataSource(backend)

        val error = assertThrows(DataSourceException::class.java) { source.read(ByteArray(8), 0, 8) }

        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, error.reason)
    }

    // ------------------------------------------------------------ 工具

    /** 用一个「openRead 必抛 [error]」的假后端验证错误映射。 */
    private fun assertMappedError(error: Throwable): DataSourceException {
        val backend = FakeBackend { _, _, _ -> throw error }
        val source = BackendDataSource(backend)
        val thrown = assertThrows(DataSourceException::class.java) {
            source.open(dataSpec(backend.id, "a.bin"))
        }
        assertTrue("cause 必须是原始的 StorageException", thrown.cause === error)
        return thrown
    }
}
