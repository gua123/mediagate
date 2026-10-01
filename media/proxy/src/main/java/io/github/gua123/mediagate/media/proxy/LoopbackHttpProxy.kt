package io.github.gua123.mediagate.media.proxy

import kotlinx.coroutines.runBlocking
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机回环 HTTP 代理（plan 4.6 / R9 / R10 的关键件）。
 *
 * 用途：
 * 1. **让 LibVLC 复用同一数据层**——LibVLC 只会吃 URL，不会用 Media3 的 BackendDataSource；
 *    本代理把 `mediagate://<backendId>/<path>` 映射成 `http://127.0.0.1:<port>/m/...`，
 *    数据依旧从注入的 [StorageBackend] 经 `openRead(path, offset, length)` 流式吐出，
 *    于是本地 / WebDAV / SFTP / FTP 四个后端在 VLC 侧也只需要这一条路径（R2/R9）。
 * 2. **「分享播放地址」**——同一份字节可以交给外部播放器 / 浏览器，Range 语义与播放器一致。
 *
 * 安全与形态：
 * - 只绑定 `127.0.0.1`（显式 IPv4 回环，不用 getLoopbackAddress 以免落到 ::1），端口交给系统分配（0），
 *   因此不会对外网暴露，也没有固定端口冲突问题；
 * - 自己实现最小 HTTP/1.1：**不用 com.sun.net.httpserver**（Android 上没有该类）；
 * - 支持 `GET` / `HEAD`，单段 `Range`（start-end / start- / -suffix）→ 206 + Content-Range；
 *   无 Range → 200 + Content-Length + `Accept-Ranges: bytes`；越界 → 416；其它方法 → 405；
 *   路径解析不出后端条目 → 404；
 * - 每个连接一个守护线程（**并发请求真并行**，VLC 的多路 Range 不会互相排队）；
 * - 客户端断开（写失败）或 [close] 时，底层 [RangeStream] 一定被关闭，不泄漏文件/远端会话；
 * - 响应一律 `Connection: close`：回环链路建连极便宜，省掉 keep-alive 状态机与半关闭处理。
 *
 * 线程模型：本类是阻塞式 socket 服务；数据层是挂起 API，用 [runBlocking] 桥接（与
 * [io.github.gua123.mediagate.media.playback.BackendDataSource] 同一套做法），后端内部会切 Dispatchers.IO。
 *
 * @param backendProvider 现取当前后端；返回 null 表示尚未选择根目录（R12），此时代理回 503。
 */
class LoopbackHttpProxy(private val backendProvider: () -> StorageBackend?) : Closeable {

    /** 固定后端的便捷构造。 */
    constructor(backend: StorageBackend) : this({ backend })

    private val running = AtomicBoolean(false)

    private val serverSocket = ServerSocket()

    private val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    private val acceptThread: Thread

    /** 系统分配的监听端口。 */
    val port: Int

    /** 回环基址（`http://127.0.0.1:<port>`），拼播放地址用 [playUrl]。 */
    val baseUrl: String

    init {
        // 只绑 IPv4 回环：显式写死 127.0.0.1，避免某些设备上 getLoopbackAddress() 给出 ::1
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), ACCEPT_BACKLOG)
        port = serverSocket.localPort
        baseUrl = "http://$LOOPBACK_HOST:$port"
        running.set(true)
        acceptThread = Thread({ acceptLoop() }, "mg-proxy-accept").apply {
            isDaemon = true
            start()
        }
        AppLog.i(TAG, "回环代理已启动：$baseUrl")
    }

    /** 后端条目 → 可直接交给 LibVLC / 外部播放器的地址。 */
    fun playUrl(backendId: String, path: String): String = ProxyUrls.format(baseUrl, backendId, path)

    /** `mediagate://` 伪 URI → 回环播放地址；伪 URI 非法时返回 null。 */
    fun playUrlFor(mediaUri: String): String? = ProxyUrls.formatFor(baseUrl, mediaUri)

    /** 是否仍在监听。 */
    val isRunning: Boolean get() = running.get()

    /**
     * 关停：停止 accept、关闭所有在飞连接（阻塞中的写会立刻抛错并走 finally 释放 RangeStream）。
     * 幂等，可重复调用。
     */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly(serverSocket)
        for (socket in connections.toList()) closeQuietly(socket)
        connections.clear()
        try {
            acceptThread.join(CLOSE_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        AppLog.i(TAG, "回环代理已关闭：端口 $port")
    }

    // ------------------------------------------------------------------ 接受连接

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                // close() 关掉 serverSocket 会让 accept 抛错，这是正常退出路径
                if (!running.get() || serverSocket.isClosed) break
                AppLog.w(TAG, "accept 失败（继续监听）", e)
                continue
            }
            connections += socket
            // 每连接一个守护线程：并发 Range 请求真并行，且不会拖住进程退出
            Thread({ serve(socket) }, "mg-proxy-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            // 读头阶段设超时：半开连接不会把线程永久挂住（进入流式写之后不再读）
            socket.soTimeout = HEAD_READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_BYTES)
            val lines = try {
                readHeadLines(input)
            } catch (e: IOException) {
                AppLog.d(TAG, "请求头读取失败：" + e.message)
                return
            }
            val head = lines?.let { HttpRequestHead.parseLines(it) }
            if (head == null) {
                writeSimple(output, 400, "Bad Request", "无法解析的 HTTP 请求")
                return
            }
            // HEAD 的响应头与 GET 完全一致，但**不能带 body**（RFC 7231），错误响应也不例外
            val sink: OutputStream = if (head.method.equals("HEAD", ignoreCase = true)) {
                HeadOnlyResponseStream(output)
            } else {
                output
            }
            handle(head, sink)
        } catch (e: IOException) {
            // 客户端提前断开 / 写失败：属正常现象，不作为错误上报
            AppLog.d(TAG, "连接中断：" + e.message)
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "处理请求时出现异常", e)
        } finally {
            connections -= socket
            closeQuietly(socket)
        }
    }

    // ------------------------------------------------------------------ 请求处理

    private fun handle(head: HttpRequestHead, out: OutputStream) {
        val method = head.method.uppercase(Locale.ROOT)
        if (method != "GET" && method != "HEAD") {
            writeSimple(
                out, 405, "Method Not Allowed", "回环代理只支持 GET / HEAD",
                extra = listOf("Allow" to "GET, HEAD"),
            )
            return
        }
        // 路径 → (backendId, path)：百分号解码统一交给 MediaUriCodec，只解一次
        val target = ProxyUrls.parse(head.target)
        if (target == null) {
            writeSimple(out, 400, "Bad Request", "地址不是 " + ProxyUrls.PREFIX + " 前缀的 mediagate 伪 URI")
            return
        }
        val backend = backendProvider()
        if (backend == null) {
            writeSimple(out, 503, "Service Unavailable", "尚未选择媒体根目录（R12）")
            return
        }
        if (backend.id != target.backendId) {
            // 同一个代理只服务一个后端：换了根目录后旧地址必须失效，而不是读到别的文件
            writeSimple(out, 404, "Not Found", "后端不匹配：代理当前后端为 " + backend.id)
            return
        }
        val entry = try {
            runBlocking { backend.stat(target.path) }
        } catch (e: StorageException) {
            writeSimple(out, statusOf(e), "Storage Error", describe(e))
            return
        }
        if (entry.isDirectory) {
            writeSimple(out, 404, "Not Found", "目标是目录，不能作为媒体流：" + target.path)
            return
        }
        val size = entry.size
        val spec = HttpRanges.resolve(head.header("range"), size)
        if (spec is HttpRanges.RangeSpec.Unsatisfiable) {
            writeSimple(
                out, 416, "Range Not Satisfiable", "请求区间超出资源长度",
                extra = listOf("Content-Range" to "bytes */" + size, "Accept-Ranges" to "bytes"),
            )
            return
        }
        val start = if (spec is HttpRanges.RangeSpec.Partial) spec.start else 0L
        val length = if (spec is HttpRanges.RangeSpec.Partial) spec.length else -1L
        val stream = try {
            runBlocking { backend.openRead(target.path, start, length) }
        } catch (e: StorageException) {
            writeSimple(out, statusOf(e), "Storage Error", describe(e))
            return
        } catch (e: IOException) {
            writeSimple(out, 500, "Internal Error", "打开数据流失败：" + e.message)
            return
        }
        try {
            val partial = spec as? HttpRanges.RangeSpec.Partial
            val contentLength = partial?.length ?: size
            val headers = mutableListOf(
                "Accept-Ranges" to "bytes",
                "Content-Type" to HttpContentTypes.of(target.path),
            )
            if (contentLength >= 0) headers += "Content-Length" to contentLength.toString()
            if (partial != null) headers += "Content-Range" to ("bytes " + partial.start + "-" + partial.end + "/" + size)
            writeHead(out, if (partial != null) 206 else 200, if (partial != null) "Partial Content" else "OK", headers)
            if (method == "GET") pump(stream, out, contentLength)
            out.flush()
        } finally {
            // 客户端断开、close()、正常读完，三条路径都从这里释放底层流
            closeQuietly(stream)
        }
    }

    /** 把 [RangeStream] 搬进 socket；[limit] >= 0 时最多写 limit 字节（防御后端多给）。 */
    private fun pump(stream: RangeStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var written = 0L
        while (true) {
            if (limit in 0..written) return
            val want = if (limit < 0) buffer.size else minOf(buffer.size.toLong(), limit - written).toInt()
            if (want <= 0) return
            val read = runBlocking { stream.read(buffer, 0, want) }
            if (read < 0) return
            if (read == 0) continue
            out.write(buffer, 0, read)
            written += read
        }
    }

    // ------------------------------------------------------------------ HTTP 输出

    private fun writeSimple(
        out: OutputStream,
        status: Int,
        reason: String,
        message: String,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        val body = message.toByteArray(Charsets.UTF_8)
        writeHead(
            out, status, reason,
            extra + listOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
            ),
        )
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, headers: List<Pair<String, String>>) {
        val head = StringBuilder(160)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF)
        for ((name, value) in headers) head.append(name).append(": ").append(value).append(CRLF)
        head.append("Connection: close").append(CRLF).append(CRLF)
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** 读请求头行（到空行为止）；超过上限抛 [IOException]（由 [serve] 兜住）。 */
    private fun readHeadLines(input: InputStream): List<String>? {
        val lines = ArrayList<String>(12)
        var current = ByteArrayOutputStream(128)
        var total = 0
        var lineBytes = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            total++
            if (total > MAX_HEAD_BYTES) throw IOException("请求头过大（>" + MAX_HEAD_BYTES + " 字节）")
            if (b == '\n'.code) {
                val bytes = current.toByteArray()
                current = ByteArrayOutputStream(128)
                lineBytes = 0
                val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                val line = String(bytes, 0, length, Charsets.UTF_8)
                if (line.isEmpty()) break
                lines += line
                if (lines.size > HttpRequestHead.MAX_HEAD_LINES) throw IOException("请求头行数过多")
            } else {
                lineBytes++
                if (lineBytes > MAX_LINE_BYTES) throw IOException("请求头单行过长")
                current.write(b)
            }
        }
        return lines.ifEmpty { null }
    }

    /** 存储异常 → HTTP 状态码（对外只暴露语义，不泄漏协议细节）。 */
    private fun statusOf(e: StorageException): Int = when (e) {
        is StorageException.NotFound -> 404
        is StorageException.AccessDenied -> 403
        is StorageException.NotSupported -> 501
        is StorageException.Network -> 502
        is StorageException.Auth -> 502
        is StorageException.Unknown -> 500
    }

    private fun describe(e: StorageException): String = e.message ?: e.javaClass.simpleName

    /**
     * HEAD 响应的过滤流：头部照常发出，第一个空行（`\r\n\r\n`）之后的字节一律丢弃。
     *
     * 为什么不逐个分支判断方法：错误响应（400/404/405/416/503…）有好几条路径，
     * 在输出侧统一拦截可以保证**任何** HEAD 响应都不会误带 body，也不会漏掉将来的新分支。
     * 用 ISO-8859-1 做边界匹配（逐字节一一对应，不会因多字节字符错位）。
     * 依赖 [writeHead] 一次写完整块头部这一事实。
     */
    private class HeadOnlyResponseStream(private val out: OutputStream) : OutputStream() {

        private var headFinished = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (headFinished) return
            val text = String(b, off, len, Charsets.ISO_8859_1)
            val index = text.indexOf(HEAD_END)
            if (index < 0) {
                out.write(b, off, len)
                return
            }
            val end = off + index + HEAD_END.length
            out.write(b, off, end - off)
            headFinished = true
        }

        override fun flush() = out.flush()

        override fun close() = out.flush()
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
            // 关闭失败不影响其它资源释放
        }
    }

    private companion object {

        const val TAG = "proxy"

        /** 显式回环地址：绝不用 0.0.0.0，也不用可能解析成 ::1 的 getLoopbackAddress。 */
        const val LOOPBACK_HOST = "127.0.0.1"

        const val CRLF = "\r\n"

        /** 头部结束标记（HEAD 响应过滤用）。 */
        const val HEAD_END = "\r\n\r\n"

        const val ACCEPT_BACKLOG = 32

        const val STREAM_BUFFER_BYTES = 64 * 1024

        const val MAX_HEAD_BYTES = 16 * 1024

        const val MAX_LINE_BYTES = 8 * 1024

        /** 读请求头的超时；进入流式写之后不再触发（只在读时生效）。 */
        const val HEAD_READ_TIMEOUT_MS = 10_000

        const val CLOSE_JOIN_TIMEOUT_MS = 1_000L
    }
}
