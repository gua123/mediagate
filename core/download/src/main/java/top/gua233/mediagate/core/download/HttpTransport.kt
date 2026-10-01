package io.github.gua123.mediagate.core.download

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次 HTTP GET 请求（**R14** 模型下载 / **R20** 应用内更新共用）。
 *
 * @property url 目标地址。
 * @property rangeStart 断点续传的起点（> 0 时发 HTTP Range 头，只取这一段）。
 * @property headers 额外请求头（**R20**：私有仓库要用 `Authorization: Bearer <只读 token>` 拉清单与 APK）。
 *   凭据只在内存里传，**永远不进日志**（诊断日志只打 URL 与请求头名字，不打值）。
 */
data class HttpRequest(
    val url: String,
    val rangeStart: Long? = null,
    val headers: Map<String, String> = emptyMap(),
) {

    /** Range 头；不需要时 null。 */
    val rangeHeader: String? get() = rangeStart?.takeIf { it > 0L }?.let { "bytes=" + it + "-" }
}

/**
 * 一次 HTTP 响应（**只暴露下载需要的字段**）。
 *
 * @property code HTTP 状态码。
 * @property contentLength 本次响应的字节数；未知 -1。
 * @property contentRangeStart 206 时 Content-Range 里的起点；没有则 null。
 * @property etag ETag（诊断用）。
 */
interface HttpStream : Closeable {

    val code: Int
    val contentLength: Long
    val contentRangeStart: Long?
    val etag: String?

    /** 读一块；返回 -1 表示读完。 */
    fun read(buffer: ByteArray): Int
}

/**
 * HTTP 传输抽象（**下载逻辑能被 JVM 单测穷举的关键**）。
 *
 * 选型说明（为什么不用 OkHttp）：这里只需要「带 Range 的顺序 GET」，不需要连接池、
 * 拦截器、HTTP/2；引入 OkHttp 会给纯逻辑模块拖进 okio 与另一套版本约束。
 * java.net.HttpURLConnection 是 JDK/Android 自带的，零依赖，且这里把它关在一个单方法接口
 * 后面——单测灌一个假实现（假 HTTP）就能把断点续传、取消、校验和失败重下全跑一遍。
 *
 * 本文件由 `:media:asr` 原样搬到 `:core:download`（2026-10-02，R20 起模型下载与 APK 下载共用同一套传输）。
 */
interface HttpTransport {

    /** 发起请求并返回响应流（调用方负责 close）。失败抛 [IOException]。 */
    suspend fun open(request: HttpRequest): HttpStream
}

/**
 * 真机实现：java.net.HttpURLConnection（**唯一碰网络的地方**）。
 *
 * @param connectTimeoutMs 连接超时（默认 15 s）。
 * @param readTimeoutMs 读超时（默认 30 s；大文件一段一段读，不能设太短）。
 * @param userAgent 带项目标识，便于日后从服务端日志分辨。
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpTransport {

    override suspend fun open(request: HttpRequest): HttpStream {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            request.rangeHeader?.let { setRequestProperty("Range", it) }
            // 调用方给的头放最后：允许覆盖默认值（如自定义 Accept）
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val rangeStart = parseContentRangeStart(connection.getHeaderField("Content-Range"))
        return object : HttpStream {
            override val code: Int = code
            override val contentLength: Long = connection.getHeaderFieldLong("Content-Length", -1L)
            override val contentRangeStart: Long? = rangeStart
            override val etag: String? = connection.getHeaderField("ETag")

            override fun read(buffer: ByteArray): Int = stream?.read(buffer) ?: -1

            override fun close() {
                runCatching { stream?.close() }
                connection.disconnect()
            }
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_USER_AGENT = "mediagate-android/0.1"

        /** 解析 Content-Range 头（形如 bytes 100-999/1000）的起点（**纯函数**）。 */
        fun parseContentRangeStart(header: String?): Long? {
            val text = header?.trim().orEmpty()
            if (!text.startsWith("bytes", ignoreCase = true)) return null
            val range = text.substringAfter(' ').substringBefore('/')
            val start = range.substringBefore('-').trim()
            return start.toLongOrNull()
        }
    }
}
