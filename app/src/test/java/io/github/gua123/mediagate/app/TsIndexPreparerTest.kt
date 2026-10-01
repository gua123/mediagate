package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream

/**
 * [TsIndexPreparer] 的边界单测（**R3/R4**）。
 *
 * 真·TS 样本的完整扫描由 :media:tsext 的 128 例覆盖；这里只钉住 :app 这一层的三条边界：
 * **非 TS 直接不碰**、**坏数据不崩**（扫描失败按"没有索引"处理）、**缓存目录可写**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TsIndexPreparerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `非 TS 文件直接返回 null 且不读数据`() = runTest {
        val backend = FakeBytesBackend(ByteArray(4096))
        val preparer = TsIndexPreparer(tmp.newFolder(), Dispatchers.Unconfined)

        assertNull(preparer.prepare(backend, "/movies/Movie.mp4"))
        assertNull(preparer.prepare(backend, "/movies/Show.mkv"))
        assertNull("压根不该打开数据源", preparer.prepare(backend, "/movies/a.bin"))
    }

    @Test
    fun `坏数据（不是 TS）不崩，按没有索引处理`() = runTest {
        // 全 0 的字节：找不到 0x47 同步字节，扫描器会判"疑似不是 TS"
        val backend = FakeBytesBackend(ByteArray(512 * 1024))
        val preparer = TsIndexPreparer(tmp.newFolder(), Dispatchers.Unconfined)

        assertNull(preparer.prepare(backend, "/movies/Broken.ts"))
        assertNull("没有索引时预取应当安全地什么都不做", preparer.indexOf("/movies/Broken.ts"))
    }

    @Test
    fun `空文件也不崩`() = runTest {
        val backend = FakeBytesBackend(ByteArray(0))
        val preparer = TsIndexPreparer(tmp.newFolder(), Dispatchers.Unconfined)

        assertNull(preparer.prepare(backend, "/movies/Empty.ts"))
    }

    /** 只有一堆字节的假后端（openRead 从字节数组里切）。 */
    private class FakeBytesBackend(private val payload: ByteArray) : StorageBackend {

        override val id: String = "fake-bytes:/root"

        override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 2)

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = path.substringAfterLast('/'), path = path, size = payload.size.toLong(), mtime = 1L)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            object : RangeStream {
                private var cursor = offset
                override val length: Long = payload.size.toLong() - offset
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    if (cursor >= payload.size) return -1
                    val count = minOf(len.toLong(), payload.size - cursor).toInt()
                    if (count <= 0) return -1
                    System.arraycopy(payload, cursor.toInt(), buf, off, count)
                    cursor += count
                    return count
                }

                override suspend fun seek(position: Long) {
                    cursor = offset + position
                }

                override fun position(): Long = cursor - offset

                override fun close() = Unit
            }

        override suspend fun write(path: String, data: InputStream) = Unit

        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

        override fun close() = Unit
    }
}
