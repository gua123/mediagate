package io.github.gua123.mediagate.data.storage.webdav

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * WebDAV 的路径 / URL 纯逻辑（R2 协议接入，全部与网络无关，可独立单测）。
 *
 * 约定与 :data:storage-local 的 FileStorageBackend 保持一致：树内路径不带首尾 `/`、
 * 反斜杠归一成 `/`、忽略 `.` 段、出现 `..` 段直接拒绝（不许越出根，R12 同款安全边界）。
 */

private const val HEX = "0123456789ABCDEF"

/** RFC 3986 的 pchar 中允许原样出现在路径段里的字符（`%` 不在其中，必须转义）。 */
private const val PATH_SEGMENT_SAFE = "-._~!\$&'()*+,;=:@"

/**
 * 把外部传入的路径归一化成树内相对路径：`"/media/a.mkv"` → `"media/a.mkv"`，根得到空串。
 *
 * @throws StorageException.AccessDenied 出现 `..` 段（越界访问）。
 */
internal fun normalizeWebDavPath(path: String): String {
    val segments = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) {
        throw StorageException.AccessDenied("路径不允许越出根目录：$path")
    }
    return segments.joinToString("/")
}

/** 拼接树内路径（[base] 为空表示根）。 */
internal fun joinWebDavPath(base: String, name: String): String = if (base.isEmpty()) name else "$base/$name"

/** 目录优先、再按名称（大小写不敏感）排序：与 FileStorageBackend 的浏览口径一致。 */
internal val DIRECTORY_FIRST: Comparator<RemoteEntry> =
    compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** 按 [page] 截取分页；page 为 null 或 limit <= 0 表示不限制（plan 4.10）。 */
internal fun paginateWebDav(entries: List<RemoteEntry>, page: Page?): List<RemoteEntry> {
    if (page == null) return entries
    val from = page.offset.coerceAtMost(entries.size)
    val rest = entries.subList(from, entries.size)
    return if (page.limit <= 0) rest.toList() else rest.take(page.limit)
}

/**
 * 路径段的百分号编码（UTF-8）：空格→`%20`、中文→`%E4..`、`#`→`%23`、`%`→`%25`。
 *
 * 与 [percentDecode] 成对：文件名里本来就有的 `%` 编码后仍是 `%25`，往返一致。
 */
internal fun encodePathSegment(segment: String): String {
    val bytes = segment.toByteArray(StandardCharsets.UTF_8)
    val sb = StringBuilder(bytes.size)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        val safe = (char in 'a'..'z') || (char in 'A'..'Z') || (char in '0'..'9') || char in PATH_SEGMENT_SAFE
        if (safe) {
            sb.append(char)
        } else {
            sb.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * 百分号解码（UTF-8）。
 *
 * 注意不能用 [java.net.URLDecoder]：它按表单语义把 `+` 解成空格，而 WebDAV 的 href 里 `+` 就是加号。
 * 非法序列（`%zz`、结尾单个 `%`）原样保留，不抛异常——服务器给脏数据时也要能列目录。
 */
internal fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val out = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hi = Character.digit(value[i + 1], 16)
            val lo = Character.digit(value[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                out.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        // 普通字符整段按 UTF-8 写出（一次处理完整码点，代理对不会被拆坏）
        val start = i
        while (i < value.length && value[i] != '%') i++
        if (i == start) i++ // 非法 % 序列：原样写出这个字符
        out.write(value.substring(start, i).toByteArray(StandardCharsets.UTF_8))
    }
    return String(out.toByteArray(), StandardCharsets.UTF_8)
}

/**
 * 拼出树内路径对应的请求 URL。
 *
 * @param requestBaseUrl [WebDavConfig.requestBaseUrl]（已去尾斜杠，可能自带百分号编码）。
 * @param path 树内路径；每段单独编码，因此空格 / 中文 / `#` / `%` 都安全。
 * @param collection true 时补一个尾斜杠：PROPFIND 列集合不带尾斜杠会被 Apache 之类 301 走一圈。
 */
internal fun buildFileUrl(requestBaseUrl: String, path: String, collection: Boolean = false): String {
    val normalized = normalizeWebDavPath(path)
    val sb = StringBuilder(requestBaseUrl)
    for (segment in normalized.split('/')) {
        if (segment.isEmpty()) continue
        sb.append('/').append(encodePathSegment(segment))
    }
    if (collection) sb.append('/')
    return sb.toString()
}

/** 取 URL / href 里的原始（未解码）路径部分：`https://h/dav/a%20b?x=1` → `/dav/a%20b`。 */
internal fun rawPathOf(href: String): String {
    val value = href.trim()
    val schemeEnd = value.indexOf("://")
    if (schemeEnd < 0) return stripQuery(value)
    val pathStart = value.indexOf('/', schemeEnd + 3)
    return if (pathStart < 0) "/" else stripQuery(value.substring(pathStart))
}

/** [rawPathOf] 的去查询串/锚点变体。 */
private fun stripQuery(path: String): String {
    val cut = path.indexOfFirst { it == '?' || it == '#' }
    return if (cut < 0) path else path.substring(0, cut)
}

/**
 * 把服务器回的一个 href 转成树内路径（**命名空间无关**的解析之外的另一半工作）。
 *
 * 三种形态都要吃得下：绝对 URL（`https://h/dav/a.txt`）、绝对路径（`/dav/a.txt`）、
 * 极少数服务器给的相对 href（`a.txt`）；统一先百分号解码再剥 [basePath] 前缀。
 */
internal fun hrefToPath(href: String, basePath: String): String =
    stripBasePrefix(percentDecode(rawPathOf(href)), basePath)

/** 剥掉 baseUrl 的路径前缀（[basePath] 需已解码）；剥不掉时按树内路径容错处理。 */
internal fun stripBasePrefix(decodedPath: String, basePath: String): String {
    val base = basePath.trimEnd('/') // "" 或 "/dav"
    val relative = when {
        base.isEmpty() -> decodedPath
        decodedPath == base || decodedPath.startsWith("$base/") -> decodedPath.removePrefix(base)
        else -> decodedPath // 容错：服务器没回前缀（或回的是相对路径），当成树内路径
    }
    return normalizeWebDavPath(relative)
}

/** 取 URL 的路径部分（不解码），`https://h` → `/`。 */
internal fun urlPathOf(url: String): String {
    val value = url.trim()
    val schemeEnd = value.indexOf("://")
    if (schemeEnd < 0) return "/"
    val pathStart = value.indexOf('/', schemeEnd + 3)
    if (pathStart < 0) return "/"
    val path = stripQuery(value.substring(pathStart))
    return path.ifEmpty { "/" }
}

/** 取 URL 的 authority（user@host:port），没有则返回空串。 */
internal fun authorityOf(url: String): String {
    val value = url.trim()
    val schemeEnd = value.indexOf("://")
    val start = if (schemeEnd < 0) 0 else schemeEnd + 3
    val slash = value.indexOf('/', start)
    val query = value.indexOfFirst { it == '?' || it == '#' }
    var end = value.length
    if (slash in 0 until end) end = slash
    if (query in 0 until end) end = query
    return value.substring(start, end)
}

/** 取 URL 的主机名（去掉用户信息、端口；IPv6 去掉方括号），用于 DNS 报错提示。 */
internal fun hostOf(url: String): String {
    val authority = authorityOf(url)
    val at = authority.lastIndexOf('@')
    val hostPort = if (at >= 0) authority.substring(at + 1) else authority
    if (hostPort.startsWith("[")) {
        val closing = hostPort.indexOf(']')
        return if (closing > 0) hostPort.substring(1, closing) else hostPort
    }
    return hostPort.substringBefore(':')
}

/** 请求基址 = 去尾斜杠的 baseUrl（+ 非根 rootPath）。 */
internal fun buildRequestBaseUrl(baseUrl: String, rootPath: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val root = normalizeWebDavPath(rootPath)
    return if (root.isEmpty()) base else "$base/$root"
}
