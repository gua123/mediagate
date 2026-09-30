package io.github.gua123.mediagate.media.playback

import androidx.media3.datasource.DataSpec
import android.net.FakeUri
import androidx.media3.common.C
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import java.io.IOException
import java.io.InputStream

/**
 * 造一个 Media3 的 [DataSpec]（JVM 单测专用）。
 *
 * uri 用 [FakeUri] 包住 [MediaUri] 伪 URI —— 这样测的是**真实路径**：
 * DataSpec.uri → MediaUri.fromUri → 后端路径，与设备上跑的一模一样。
 */
internal fun dataSpec(
    backendId: String,
    path: String,
    position: Long = 0L,
    length: Long = C.LENGTH_UNSET.toLong(),
): DataSpec = DataSpec(FakeUri(MediaUri.format(backendId, path)), position, length)

/** 记录关闭次数与读取次数的内存 [RangeStream]；[length] 传 -1 模拟「长度未知」。 */
internal class FakeRangeStream(
    private val data: ByteArray,
    override val length: Long,
    private val start: Int = 0,
    private val maxChunk: Int = Int.MAX_VALUE,
    private val failOnRead: Boolean = false,
) : RangeStream {

    var closed = false
        private set

    var reads = 0
        private set

    private var pos = 0

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        reads++
        if (closed) throw IOException("流已关闭")
        if (failOnRead) throw IOException("模拟读取失败")
        if (len == 0) return 0
        val available = (data.size - start - pos).coerceAtLeast(0).toLong()
        val remaining = if (length < 0) available else minOf(length - pos, available)
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining, maxChunk.toLong()).toInt()
        System.arraycopy(data, start + pos, buf, off, n)
        pos += n
        return n
    }

    override suspend fun seek(position: Long) {
        pos = position.toInt()
    }

    override fun position(): Long = pos.toLong()

    override fun close() {
        closed = true
    }
}

/** 最小假后端：openRead 行为由 [onOpen] 决定，并记录每次调用的 (path, offset, length)。 */
internal class FakeBackend(
    override val id: String = "fake:backend",
    private val onOpen: suspend (path: String, offset: Long, length: Long) -> RangeStream,
) : StorageBackend {

    val opens = mutableListOf<Triple<String, Long, Long>>()

    override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 2, writable = false)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

    override suspend fun stat(path: String): RemoteEntry = RemoteEntry(name = path, path = path)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        opens += Triple(path, offset, length)
        return onOpen(path, offset, length)
    }

    override suspend fun write(path: String, data: InputStream) = Unit

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}

/** 一次性把数据源读干（读到 -1 为止）。 */
internal fun readAll(source: BackendDataSource, chunk: Int = 8 * 1024): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(chunk)
    while (true) {
        val n = source.read(buf, 0, buf.size)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
