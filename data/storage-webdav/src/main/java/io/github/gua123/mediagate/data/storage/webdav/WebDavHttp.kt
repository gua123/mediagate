package io.github.gua123.mediagate.data.storage.webdav

import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * WebDAV 的 HTTP 细节纯逻辑（R2 协议接入 / R4 Range 随机读 / R8 错误分类 / R14 写回）。
 * 这里只放「字符串与状态码」层面的东西，方便脱网单测。
 */

/** PROPFIND 请求体（plan 4.1：只问我们要的 5 个属性，别让服务器回一大堆）。 */
internal const val PROPFIND_BODY: String =
    "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/><D:getcontentlength/>" +
        "<D:getlastmodified/><D:getetag/><D:getcontenttype/></D:prop></D:propfind>"

/** PROPFIND 请求的 Content-Type；带 charset 是给老服务器的。 */
internal const val XML_CONTENT_TYPE: String = "application/xml; charset=utf-8"

/** R14 字幕写回的 Content-Type（后端不猜扩展名，统一按二进制传）。 */
internal const val PUT_CONTENT_TYPE: String = "application/octet-stream"

/** 必须显式声明 identity：否则 OkHttp 会自己加 gzip 并透明解压，Range 的字节偏移就全错了。 */
internal const val ACCEPT_ENCODING_IDENTITY: String = "identity"

/** 手动跟随的重定向上限。 */
internal const val MAX_REDIRECTS: Int = 5

/** 重定向状态码（OkHttp 被我们关掉了自动跟随，见 defaultClient）。 */
internal val REDIRECT_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

/**
 * 构造 Range 请求头（R4 拖拽 seek 的最小单位）。
 *
 * @param offset 文件内的绝对起始偏移。
 * @param length 期望长度；< 0 表示「从这里读到文件末尾」（`bytes=offset-`）。
 */
internal fun buildRangeHeader(offset: Long, length: Long): String {
    require(offset >= 0L) { "offset 不能为负：$offset" }
    return if (length < 0L) "bytes=$offset-" else "bytes=$offset-${offset + length - 1}"
}

/** Content-Range 的解析结果；[total] 为 -1 表示服务器写的是 `*`（未知总长）。 */
internal data class ContentRange(val start: Long, val end: Long, val total: Long)

/**
 * 解析 `Content-Range: bytes 100-199/1234`。
 *
 * 容错：大小写不敏感、允许 `bytes=100-199/1234` 这种把等号写成空格的写法；
 * 416 响应里的「星号 + 斜杠 + 总长」写法与任何畸形值都返回 null。
 */
internal fun parseContentRange(value: String?): ContentRange? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty() || !text.startsWith("bytes", ignoreCase = true)) return null
    val rest = text.substring(5).trim().removePrefix("=").trim()
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val rangePart = rest.substring(0, slash).trim()
    val totalPart = rest.substring(slash + 1).trim()
    val dash = rangePart.indexOf('-')
    if (dash <= 0) return null
    val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
    val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
    if (start < 0L || end < start) return null
    val total = if (totalPart == "*") -1L else (totalPart.toLongOrNull() ?: -1L)
    return ContentRange(start, end, total)
}

private val HTTP_DATE_FORMATS: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.RFC_1123_DATE_TIME, // Mon, 30 Sep 2026 12:00:00 GMT（标准）
    DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US), // RFC 850（老 Apache）
    DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US), // asctime
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    DateTimeFormatter.ISO_INSTANT,
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US), // 个别 NAS 的裸 Z
)

/**
 * 解析 `getlastmodified` 的 HTTP 日期，返回 Unix 毫秒；解析不了返回 0（= 未知，不抛异常）。
 *
 * RemoteEntry.mtime 的约定就是「未知用 0」，服务器给畸形日期不该让整个列目录失败。
 */
internal fun parseHttpDate(value: String?): Long {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return 0L
    for (format in HTTP_DATE_FORMATS) {
        try {
            return Instant.from(format.parse(text)).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // 该格式不匹配，试下一个
        }
        try {
            return LocalDateTime.parse(text, format).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // 没有时区信息的格式（asctime）走这里
        }
    }
    return 0L
}

/**
 * HTTP 状态码 → 存储层语义异常（R8 错误分类的口径）。
 *
 * 401 账号密码错、403 无权限、404 路径不存在、405/501 服务器不支持该方法、5xx 归网络、
 * 其余归 [StorageException.Unknown]。
 */
internal fun statusException(code: Int, what: String, cause: Throwable? = null): StorageException = when (code) {
    401 -> StorageException.Auth("401 账号或密码错误：$what", cause)
    403 -> StorageException.AccessDenied("403 无访问权限：$what", cause)
    404, 410 -> StorageException.NotFound("404 路径不存在：$what", cause)
    405, 501 -> StorageException.NotSupported("$code 服务器不支持该操作：$what", cause)
    in 500..599 -> StorageException.Network("$code 服务器错误：$what", cause)
    else -> StorageException.Unknown("HTTP $code：$what", cause)
}

/** IO 异常 → 网络类语义（R7 选路与重试据此判断「是不是网的问题」）。 */
internal fun mapIoFailure(e: IOException, what: String): StorageException = when (e) {
    is StorageException -> e
    is UnknownHostException -> StorageException.Network("DNS 解析失败：$what", e)
    is SocketTimeoutException -> StorageException.Network("连接或读取超时：$what", e)
    is ConnectException -> StorageException.Network("TCP 连接被拒绝：$what", e)
    is NoRouteToHostException -> StorageException.Network("网络不可达：$what", e)
    is SSLException -> StorageException.Network("TLS 握手失败：$what", e)
    is InterruptedIOException -> StorageException.Network("传输中断：$what", e)
    else -> StorageException.Network("网络 I/O 失败：$what", e)
}
