package io.github.gua123.mediagate.data.storage.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.InputStream

/**
 * 一次 Range GET 的响应体（[WebDavRangeStream] 内部的可丢弃窗口）。
 *
 * @param startAbs 该响应体第一个字节对应的**文件绝对偏移**：206 时来自 Content-Range，
 *   服务器忽略 Range 回 200 时是 0（整份文件），这时要靠 [skip] 本地跳过去。
 * @param totalSize 文件总大小；未知 -1。
 * @param capacity 该响应体的总字节数；未知 -1（没有 Content-Length 的分块响应）。
 */
internal class WebDavWindow(
    private val response: Response,
    val input: InputStream,
    val startAbs: Long,
    val totalSize: Long,
    private val capacity: Long,
) {

    private var consumed = 0L

    /** 该窗口还剩多少字节可读；-1 表示未知（读到 EOF 为止）。 */
    val remaining: Long
        get() = if (capacity < 0L) -1L else (capacity - consumed).coerceAtLeast(0L)

    fun consume(count: Long) {
        consumed += count
    }

    /** 丢掉 [count] 字节（服务器多回了一段时的对齐操作）；提前 EOF 返回 false。 */
    fun skip(count: Long): Boolean {
        var left = count
        val scratch = ByteArray(SKIP_BUFFER_BYTES)
        while (left > 0L) {
            val step = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (step < 0) return false
            left -= step
            consumed += step
        }
        return true
    }

    /** 关闭底层响应（同时关掉 [input]）。 */
    fun close() {
        response.close()
    }

    private companion object {
        const val SKIP_BUFFER_BYTES = 8 * 1024
    }
}

/**
 * WebDAV 的随机读流（R4 视频拖拽 / R11 抽帧取音）。
 *
 * 语义遵循 [StorageBackend.openRead]：流的 0 点就是 openRead 的 `offset`，[position] / [seek]
 * 都在这个相对坐标里；对外完全不暴露文件绝对偏移。
 *
 * 关键设计（plan 4.1 表格里 WebDAV 那一行）：
 * - **每次 seek 都打一个新的 Range 请求**：当前响应体直接丢掉，下一次 [read] 重新发
 *   `Range: bytes=<绝对偏移>-`（或到本流末尾的闭区间）。这样拖拽的粒度就是一次 HTTP 往返，
 *   不用维护长连接；
 * - **服务器忽略 Range（回 200）也能读对**：响应体从文件 0 开始，本地 skip 掉 [startOffset]，
 *   同时后端会把 `caps.randomAccess` 降级为 false（plan 4.1 的降级链由上层决定怎么用）；
 * - [length] 在第一次请求拿到 Content-Range / Content-Length 之后才是准确值，之前可能是 -1（未知）。
 *
 * 线程安全：接口约定「同一实例同一时刻只应有一个协程在用」，本实现不额外加锁。
 */
internal class WebDavRangeStream private constructor(
    private val backend: WebDavStorageBackend,
    private val path: String,
    private val startOffset: Long,
    private val requestedLength: Long,
) : RangeStream {

    private var window: WebDavWindow? = null
    private var pos = 0L
    private var closed = false

    @Volatile
    private var totalSize = -1L

    override val length: Long
        get() {
            val remaining = if (totalSize >= 0L) (totalSize - startOffset).coerceAtLeast(0L) else -1L
            return when {
                requestedLength < 0L -> remaining
                remaining < 0L -> requestedLength
                else -> minOf(requestedLength, remaining)
            }
        }

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (len < 0) throw StorageException.Unknown("len 不能为负：$len")
        checkOpen()
        return withContext(Dispatchers.IO) { readInternal(buf, off, len) }
    }

    /** [read] 的本体：跑在 Dispatchers.IO 上；窗口用完就按新偏移再开一个 Range 请求。 */
    private fun readInternal(buf: ByteArray, off: Int, len: Int): Int {
        var emptyWindows = 0
        while (true) {
            val streamLength = length
            if (streamLength >= 0L && pos >= streamLength) return -1
            val current = window
            if (current == null || current.remaining == 0L) {
                if (!openWindow(startOffset + pos)) return -1
                continue
            }
            val available = if (current.remaining < 0L) len.toLong() else minOf(len.toLong(), current.remaining)
            val cap = (if (streamLength < 0L) available else minOf(available, streamLength - pos)).toInt()
            if (cap <= 0) return -1
            val n = current.input.read(buf, off, cap)
            if (n > 0) {
                pos += n
                current.consume(n.toLong())
                return n
            }
            // 响应体提前结束：容量还没读完就是被截断了（上层据此走 R7 重试），否则按读完处理
            val capacityLeft = current.remaining
            closeWindow()
            if (capacityLeft > 0L) throw StorageException.Network("GET 响应提前结束（还差 $capacityLeft 字节）：$path")
            if (streamLength >= 0L && pos < streamLength && ++emptyWindows < MAX_EMPTY_WINDOWS) continue
            return -1
        }
    }

    override suspend fun seek(position: Long) = withContext(Dispatchers.IO) {
        checkOpen()
        val streamLength = length
        val target = if (streamLength >= 0L) position.coerceIn(0L, streamLength) else position.coerceAtLeast(0L)
        // plan 4.1：每次 seek 都丢掉当前响应体，下一次 read 重新发一个 Range 请求
        closeWindow()
        pos = target
    }

    override fun position(): Long = pos

    override fun close() {
        closed = true
        closeWindow()
    }

    /** 打开一个覆盖 [abs] 起到本流末尾的窗口；返回 false 表示已经是文件末尾（不做任何请求）。 */
    private fun openWindow(abs: Long): Boolean {
        closeWindow()
        val span = if (requestedLength < 0L) -1L else startOffset + requestedLength - abs
        if (span == 0L) return false
        val opened = backend.getRange(path, abs, span) ?: return false // 416：偏移已在文件末尾之后
        if (opened.startAbs > abs) {
            opened.close()
            throw StorageException.Unknown("Range 响应起点异常：期望 $abs，实际 ${opened.startAbs}（$path）")
        }
        if (opened.startAbs < abs && !opened.skip(abs - opened.startAbs)) {
            opened.close()
            return false
        }
        if (opened.totalSize >= 0L) totalSize = opened.totalSize
        if (opened.remaining == 0L) {
            opened.close()
            return false
        }
        window = opened
        return true
    }

    private fun closeWindow() {
        window?.close()
        window = null
    }

    private fun checkOpen() {
        if (closed) throw StorageException.Unknown("流已关闭：$path")
    }

    companion object {
        private const val MAX_EMPTY_WINDOWS = 2

        /**
         * 建流并**立刻发第一个 Range 请求**。
         *
         * 这样做有两个好处：404 / 403 /「目标是目录」在 openRead 阶段就按 [StorageException] 报出来
         * （与 FileStorageBackend 的行为一致），且拿到 Content-Range 后 [length] 立刻是准确值。
         *
         * 必须在 `Dispatchers.IO` 上调用（内部是同步 OkHttp 调用）。
         */
        internal fun open(
            backend: WebDavStorageBackend,
            path: String,
            startOffset: Long,
            requestedLength: Long,
        ): WebDavRangeStream {
            val stream = WebDavRangeStream(backend, path, startOffset, requestedLength)
            stream.openWindow(startOffset)
            return stream
        }
    }
}

/** `openRead(length = 0)` 的空流：一个请求都不发，立刻读到 EOF。 */
internal class EmptyRangeStream : RangeStream {

    override val length: Long = 0L

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = -1

    override suspend fun seek(position: Long) = Unit

    override fun position(): Long = 0L

    override fun close() = Unit
}
