package io.github.gua123.mediagate.media.subtitle

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 内存假后端（字幕单测用）：提供 list / openRead / write，可注入写入失败。
 *
 * 只实现字幕路径需要的那点行为，够用来验证「远端字幕读得到、写回失败要抛出去」。
 */
internal class FakeSubtitleBackend(
    override val id: String = "fake:subtitle",
) : StorageBackend {

    private val files = mutableMapOf<String, ByteArray>()

    private val listings = mutableMapOf<String, List<RemoteEntry>>()

    /** 写回的内容（路径 → 字节）。 */
    val written = mutableMapOf<String, ByteArray>()

    /** 写回时要抛的异常（模拟无权限 / 不支持写入）。 */
    var writeFailure: Throwable? = null

    /** 读取时要抛的异常（模拟远端读失败）。 */
    var readFailure: Throwable? = null

    override val caps: Caps = Caps(randomAccess = true, writable = true, maxParallelReads = 2)

    /** 放一份字幕内容。 */
    fun put(path: String, text: String, charset: Charset = Charsets.UTF_8) {
        files[path] = text.toByteArray(charset)
    }

    /** 放一份原始字节（编码测试用）。 */
    fun putBytes(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    /** 设置某个目录的列举结果。 */
    fun listing(dir: String, entries: List<RemoteEntry>) {
        listings[dir] = entries
    }

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = listings[dir].orEmpty()

    override suspend fun stat(path: String): RemoteEntry =
        RemoteEntry(name = path.substringAfterLast('/'), path = path, size = files[path]?.size?.toLong() ?: 0L)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        readFailure?.let { throw it }
        val bytes = files[path] ?: throw io.github.gua123.mediagate.data.storage.api.StorageException.NotFound(path)
        return ByteArrayRangeStream(bytes, offset)
    }

    override suspend fun write(path: String, data: InputStream) {
        writeFailure?.let { throw it }
        written[path] = data.readBytes()
    }

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}

/** 字节数组上的可定位流（与真实后端的 RangeStream 语义一致）。 */
internal class ByteArrayRangeStream(
    private val bytes: ByteArray,
    private val start: Long = 0L,
) : RangeStream {

    private var cursor: Long = 0L

    override val length: Long = (bytes.size - start).coerceAtLeast(0L)

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        val absolute = start + cursor
        if (absolute >= bytes.size) return -1
        val count = minOf(len.toLong(), bytes.size - absolute).toInt()
        if (count <= 0) return -1
        System.arraycopy(bytes, absolute.toInt(), buf, off, count)
        cursor += count
        return count
    }

    override suspend fun seek(position: Long) {
        cursor = position.coerceAtLeast(0L)
    }

    override fun position(): Long = cursor

    override fun close() = Unit
}

/** 造一个目录项（默认是文件）。 */
internal fun subtitleEntry(
    path: String,
    isDirectory: Boolean = false,
    size: Long = 1024L,
): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    isDirectory = isDirectory,
    size = size,
)

/** 造一个字幕候选列表用的目录项。 */
internal fun entryOf(path: String): RemoteEntry = subtitleEntry(path)
