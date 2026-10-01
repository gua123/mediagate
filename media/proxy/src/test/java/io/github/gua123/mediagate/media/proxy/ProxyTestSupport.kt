package io.github.gua123.mediagate.media.proxy

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 代理单测的共用替身与原始 socket 工具（JVM）。
 *
 * 主链路用真实文件 + FileStorageBackend；需要「长度未知」「观察底层流是否被关掉」
 * 这类 FileStorageBackend 造不出的场景时用 [FakeBackend] / [RecordingRangeStream]。
 */
internal class FakeBackend(
    override val id: String = "fake:backend",
    private val entry: (String) -> RemoteEntry = { RemoteEntry(name = it, path = it, size = -1L) },
    private val onOpen: (path: String, offset: Long, length: Long) -> RangeStream,
) : StorageBackend {

    /** 每次 openRead 的入参，供断言「Range 被正确翻译成 offset/length」。 */
    val opens = mutableListOf<Triple<String, Long, Long>>()

    override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 4, writable = false)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

    override suspend fun stat(path: String): RemoteEntry = entry(path)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        opens += Triple(path, offset, length)
        return onOpen(path, offset, length)
    }

    override suspend fun write(path: String, data: InputStream) = Unit

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}

/** 可观察 close / read 次数的内存流；[length] 传 -1 模拟「长度未知」。 */
internal class RecordingRangeStream(
    private val data: ByteArray,
    override val length: Long,
    private val start: Int = 0,
) : RangeStream {

    @Volatile
    var closed = false
        private set

    @Volatile
    var reads = 0
        private set

    private var pos = 0

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        reads++
        if (closed) return -1
        val available = (data.size - start - pos).coerceAtLeast(0).toLong()
        val remaining = if (length < 0) available else minOf(length - pos, available)
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
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

/** 原始 socket 客户端：发一段请求文本，把响应字节全读回来（可控制是否只读一部分）。 */
internal fun rawRequest(port: Int, request: String, readBytes: Int = Int.MAX_VALUE): ByteArray {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 10_000
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        return readUpTo(socket.getInputStream(), readBytes)
    }
}

/** 读到 EOF 或读满 [limit] 字节为止。 */
internal fun readUpTo(input: InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = try {
            input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        } catch (e: java.io.IOException) {
            break
        }
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** 用原始 socket 发一个请求但**不读响应**，随后立刻断开（用于验证底层流被释放）。 */
internal fun requestThenAbort(port: Int, request: String, drainBytes: Int = 32 * 1024) {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 10_000
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        // 先读一点点（确保服务端已经开始写），然后不管剩下的直接关掉 socket
        readUpTo(socket.getInputStream(), drainBytes)
    }
}

/** 轮询等待条件成立（默认 5 s），避免用固定 sleep 造成偶发失败。 */
internal fun awaitTrue(timeoutMs: Long = 5_000, stepMs: Long = 10, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(stepMs)
    }
    return condition()
}

/** 把响应头（到空行为止）与 body 分开。 */
internal fun splitHead(response: ByteArray): Pair<String, ByteArray> {
    val marker = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    var index = -1
    outer@ for (i in 0..response.size - marker.size) {
        for (j in marker.indices) if (response[i + j] != marker[j]) continue@outer
        index = i
        break
    }
    if (index < 0) return String(response, Charsets.ISO_8859_1) to ByteArray(0)
    val head = String(response, 0, index, Charsets.ISO_8859_1)
    val body = response.copyOfRange(index + marker.size, response.size)
    return head to body
}

/** 从一个头字段里取值（大小写不敏感）。 */
internal fun headerValue(head: String, name: String): String? =
    head.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        .firstOrNull { it.first.equals(name, ignoreCase = true) }
        ?.second

/** 写入一段确定性内容（模 251），便于定位错位。 */
internal fun payload(size: Int, seed: Int = 0): ByteArray = ByteArray(size) { ((it + seed) % 251).toByte() }

/** 把 [ByteArray] 写进文件。 */
internal fun java.io.File.writePayload(bytes: ByteArray) {
    parentFile?.mkdirs()
    outputStream().use { it.write(bytes) }
}

/** 静默关闭（测试清理用）。 */
internal fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (e: java.io.IOException) {
        // 忽略
    }
}
