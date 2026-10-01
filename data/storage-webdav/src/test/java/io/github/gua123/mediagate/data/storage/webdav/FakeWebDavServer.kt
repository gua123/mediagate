package io.github.gua123.mediagate.data.storage.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 假服务器记录下来的一个请求（断言请求头 / 请求体用）。 */
internal data class RecordedRequest(
    val method: String,
    /** 未解码的路径（含百分号转义），例如 `/dav/%E4%B8%AD%E6%96%87.txt`。 */
    val rawPath: String,
    /** 头名一律小写。 */
    val headers: Map<String, String>,
    val body: String,
) {
    val depth: String? get() = headers["depth"]
    val range: String? get() = headers["range"]
}

/** href 的形态（真实服务器三种都见过）。 */
internal enum class HrefStyle { ABSOLUTE_PATH, FULL_URL }

/**
 * 测试用的假 WebDAV 服务器（**只在 JVM 单测里用**）。
 *
 * 用 JDK 自带的 `com.sun.net.httpserver`（Android 上没有，所以只出现在 test 源集），
 * 不引任何新依赖；覆盖 PROPFIND（Depth 0/1 + multistatus XML）/ GET（Range / 忽略 Range /
 * 目录 HTML）/ PUT（201 / 403 / 409）四条路径，并把每个请求记下来供断言。
 *
 * 内部路径模型：根是空串，其余形如 `dav/sub/a.txt`（无首尾斜杠）。
 */
internal class FakeWebDavServer {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** 目录集合（一定含根 ""）。 */
    val dirs = linkedSetOf("")

    /** 文件 → 内容。 */
    val files = linkedMapOf<String, ByteArray>()

    // ---- 行为开关 ----
    /** PROPFIND 里 Accept-Ranges 的值（true → bytes，false → none）。 */
    var rangeSupport: Boolean = true

    /** true = 带 Range 的 GET 也回 200 全量（模拟不支持随机读的服务器）。 */
    var ignoresRange: Boolean = false

    /** true = PUT 一律 403。 */
    var readOnly: Boolean = false

    /** 对目录发 GET 时的状态码：200 会回 HTML 目录页，其它码回空体。 */
    var directoryGetStatus: Int = 200

    /** multistatus 的 XML 前缀（`D` / `d` / 空串=默认命名空间）。 */
    var xmlPrefix: String = "D"

    var hrefStyle: HrefStyle = HrefStyle.ABSOLUTE_PATH

    /** true 时在 200 的 propstat 之后再塞一个 404 的 propstat（测「只认 200 那份」）。 */
    var includeNotFoundPropstat: Boolean = false

    var notFoundPropstatLength: Long = 999_999L

    /** 非 null 时要求 Authorization 头完全等于该值，否则 401。 */
    var requireAuthorization: String? = null

    /** 每个响应前的固定延迟（毫秒），用于验证 probe 的握手计时真的在计时。 */
    var responseDelayMs: Long = 0L

    /** rawPath → Location：命中则回 301。 */
    val redirects = mutableMapOf<String, String>()

    /** "`METHOD rawPath`" → 状态码：命中则直接回该码（测 401/403/405/500 用）。 */
    val forcedStatus = mutableMapOf<String, Int>()

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val port: Int get() = server.address.port

    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { exchange -> handle(exchange) }
    }

    private var started = false

    fun start() {
        if (!started) {
            server.start()
            started = true
        }
    }

    fun stop() {
        if (started) {
            server.stop(0)
            started = false
        }
    }

    /** 建目录（顺带补齐所有父目录）。 */
    fun addDir(path: String) {
        var current = ""
        for (segment in path.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            current = if (current.isEmpty()) segment else "$current/$segment"
            dirs += current
        }
    }

    /** 建文件（父目录自动补齐）。 */
    fun addFile(path: String, content: String) {
        val normalized = path.trim('/')
        addDir(parentOf(normalized))
        files[normalized] = content.toByteArray(StandardCharsets.UTF_8)
    }

    /** 按 rawPath 过滤出请求（断言「发了几次」「带了什么头」）。 */
    fun requestsFor(method: String, rawPath: String): List<RecordedRequest> =
        requests.filter { it.method == method.uppercase(Locale.US) && it.rawPath == rawPath }

    fun etagOf(path: String): String = "etag:$path"

    // ---------------------------------------------------------------- 内部实现

    private fun handle(exchange: HttpExchange) {
        try {
            val rawPath = exchange.requestURI.rawPath ?: "/"
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { (k, v) ->
                k.lowercase(Locale.US) to v.firstOrNull().orEmpty()
            }
            requests += RecordedRequest(exchange.requestMethod.uppercase(Locale.US), rawPath, headers, body)

            val delay = responseDelayMs
            if (delay > 0L) Thread.sleep(delay)

            val required = requireAuthorization
            if (required != null && headers["authorization"] != required) {
                respond(exchange, 401, "text/plain", ByteArray(0))
                return
            }
            redirects[rawPath]?.let { location ->
                exchange.responseHeaders.add("Location", location)
                respond(exchange, 301, "text/plain", ByteArray(0))
                return
            }
            forcedStatus["${exchange.requestMethod.uppercase(Locale.US)} $rawPath"]?.let { code ->
                respond(exchange, code, "text/plain", ByteArray(0))
                return
            }
            when (exchange.requestMethod.uppercase(Locale.US)) {
                "PROPFIND" -> handlePropfind(exchange, rawPath, headers)
                "GET", "HEAD" -> handleGet(exchange, rawPath, headers)
                "PUT" -> handlePut(exchange, rawPath, body)
                else -> respond(exchange, 405, "text/plain", ByteArray(0))
            }
        } catch (t: Throwable) {
            runCatching { respond(exchange, 500, "text/plain", t.toString().toByteArray()) }
        } finally {
            exchange.close()
        }
    }

    private fun handlePropfind(exchange: HttpExchange, rawPath: String, headers: Map<String, String>) {
        val path = decodePath(rawPath)
        val isDirectory = path in dirs
        if (!isDirectory && path !in files) {
            respond(exchange, 404, "text/plain", ByteArray(0))
            return
        }
        val depth = headers["depth"]?.trim()?.toIntOrNull() ?: 1
        val extra = if (rangeSupport) mapOf("Accept-Ranges" to "bytes") else mapOf("Accept-Ranges" to "none")
        respond(
            exchange,
            207,
            "application/xml; charset=utf-8",
            buildMultistatus(path, depth).toByteArray(StandardCharsets.UTF_8),
            extra,
        )
    }

    private fun handleGet(exchange: HttpExchange, rawPath: String, headers: Map<String, String>) {
        val path = decodePath(rawPath)
        if (path.isEmpty() || path in dirs) {
            if (directoryGetStatus == 200) {
                respond(
                    exchange,
                    200,
                    "text/html; charset=utf-8",
                    "<html><body>listing</body></html>".toByteArray(StandardCharsets.UTF_8),
                    mapOf("Accept-Ranges" to "none"),
                )
            } else {
                respond(exchange, directoryGetStatus, "text/plain", ByteArray(0))
            }
            return
        }
        val content = files[path]
        if (content == null) {
            respond(exchange, 404, "text/plain", ByteArray(0))
            return
        }
        val rangeHeader = headers["range"]
        if (ignoresRange || rangeHeader == null) {
            respond(
                exchange,
                200,
                "application/octet-stream",
                content,
                mapOf("Accept-Ranges" to if (ignoresRange || !rangeSupport) "none" else "bytes"),
            )
            return
        }
        val parsed = parseRange(rangeHeader, content.size.toLong())
        if (parsed == null) {
            respond(exchange, 416, "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */${content.size}"))
            return
        }
        val (start, end) = parsed
        val slice = content.copyOfRange(start.toInt(), (end + 1).toInt())
        respond(
            exchange,
            206,
            "application/octet-stream",
            slice,
            mapOf(
                "Content-Range" to "bytes $start-$end/${content.size}",
                "Accept-Ranges" to "bytes",
            ),
        )
    }

    private fun handlePut(exchange: HttpExchange, rawPath: String, body: String) {
        val path = decodePath(rawPath)
        if (readOnly) {
            respond(exchange, 403, "text/plain", ByteArray(0))
            return
        }
        if (path.isEmpty() || path in dirs) {
            respond(exchange, 405, "text/plain", ByteArray(0))
            return
        }
        if (parentOf(path) !in dirs) {
            respond(exchange, 409, "text/plain", ByteArray(0))
            return
        }
        files[path] = body.toByteArray(StandardCharsets.UTF_8)
        respond(exchange, 201, "text/plain", ByteArray(0))
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        contentType: String,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        exchange.responseHeaders.add("Content-Type", contentType)
        for ((name, value) in extraHeaders) exchange.responseHeaders.add(name, value)
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(code, -1)
        } else {
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private fun buildMultistatus(path: String, depth: Int): String {
        val ns = if (xmlPrefix.isEmpty()) "" else "$xmlPrefix:"
        val declaration = if (xmlPrefix.isEmpty()) "xmlns=\"DAV:\"" else "xmlns:$xmlPrefix=\"DAV:\""
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<${ns}multistatus $declaration>")
        appendResponse(sb, ns, path, isDirectory = path in dirs)
        if (depth >= 1) {
            for (child in childrenOf(path)) {
                appendResponse(sb, ns, child, isDirectory = child in dirs)
            }
        }
        sb.append("</${ns}multistatus>")
        return sb.toString()
    }

    private fun appendResponse(sb: StringBuilder, ns: String, path: String, isDirectory: Boolean) {
        val size = files[path]?.size?.toLong() ?: -1L
        sb.append("<${ns}response>")
        sb.append("<${ns}href>").append(escapeXml(hrefFor(path, isDirectory))).append("</${ns}href>")
        sb.append("<${ns}propstat><${ns}prop>")
        sb.append("<${ns}resourcetype>")
        if (isDirectory) sb.append("<${ns}collection/>")
        sb.append("</${ns}resourcetype>")
        if (!isDirectory) sb.append("<${ns}getcontentlength>$size</${ns}getcontentlength>")
        sb.append("<${ns}getlastmodified>")
            .append(MODIFIED_AT_FORMAT.format(Instant.ofEpochMilli(MODIFIED_AT)))
            .append("</${ns}getlastmodified>")
        sb.append("<${ns}getetag>\"").append(etagOf(path)).append("\"</${ns}getetag>")
        sb.append("<${ns}getcontenttype>")
            .append(if (isDirectory) "httpd/unix-directory" else "application/octet-stream")
            .append("</${ns}getcontenttype>")
        sb.append("</${ns}prop><${ns}status>HTTP/1.1 200 OK</${ns}status></${ns}propstat>")
        if (includeNotFoundPropstat) {
            sb.append("<${ns}propstat><${ns}prop><${ns}getcontentlength>")
                .append(notFoundPropstatLength)
                .append("</${ns}getcontentlength></${ns}prop>")
                .append("<${ns}status>HTTP/1.1 404 Not Found</${ns}status></${ns}propstat>")
        }
        sb.append("</${ns}response>")
    }

    private fun hrefFor(path: String, isDirectory: Boolean): String {
        val encoded = if (path.isEmpty()) {
            "/"
        } else {
            "/" + path.split('/').joinToString("/") { encodeSegment(it) }
        }
        val withSlash = if (isDirectory && !encoded.endsWith("/")) "$encoded/" else encoded
        return when (hrefStyle) {
            HrefStyle.ABSOLUTE_PATH -> withSlash
            HrefStyle.FULL_URL -> baseUrl + withSlash
        }
    }

    private fun childrenOf(dirPath: String): List<String> =
        (dirs + files.keys).filter { it.isNotEmpty() && parentOf(it) == dirPath }.distinct().sorted()

    private fun decodePath(rawPath: String): String = URI.create(rawPath).path.trim('/')

    private fun parentOf(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index < 0) "" else path.substring(0, index)
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        val value = header.trim().removePrefix("bytes=").trim()
        val dash = value.indexOf('-')
        if (dash < 0) return null
        val startText = value.substring(0, dash).trim()
        val endText = value.substring(dash + 1).trim()
        val start = if (startText.isEmpty()) 0L else startText.toLongOrNull() ?: return null
        if (start >= size) return null
        val end = if (endText.isEmpty()) size - 1L else minOf(endText.toLongOrNull() ?: return null, size - 1L)
        if (end < start) return null
        return start to end
    }

    companion object {
        /** 假服务器给所有条目的固定 mtime（整秒，能被 RFC 1123 往返）。 */
        const val MODIFIED_AT: Long = 1_759_305_600_000L

        private val MODIFIED_AT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(java.time.ZoneOffset.UTC)
    }
}
