package io.github.gua123.mediagate.media.proxy

/**
 * mediagate 伪 URI 的编解码（与 [io.github.gua123.mediagate.media.playback.MediaUri] **逐字兼容**）。
 *
 * 为什么这里要再写一份：模块依赖方向是 `:media:playback → :media:engine → :media:proxy`
 * （playback 已经依赖 engine，engine 的回环代理又依赖本模块），engine/proxy 都**不能**反向
 * 依赖 playback。而回环 HTTP 代理与 LibVLC 必须用同一套地址口径寻址（plan 4.6：让第二引擎
 * 复用同一数据层），所以把口径固化成本对象，KDoc 里的金标字符串与 playback 的 MediaUri
 * 输出逐字相同（引擎侧单测直接断言这些金标）。
 *
 * 形态：`mediagate://<backendId>/<path>`，两段都百分号编码：
 * - authority = 后端 id（整段编码，`:` → %3A、`/` → %2F）；
 * - path = 后端内路径（保留 `/` 作层级分隔，逐段编码，含中文/空格/emoji）。
 *
 * 纯 Kotlin（只用 JDK 的 UTF-8 编解码），JVM 可直接单测。
 */
object MediaUriCodec {

    /** 伪 URI 的 scheme。 */
    const val SCHEME = "mediagate"

    /** 前缀（SCHEME + 冒号双斜杠）。 */
    const val PREFIX = "mediagate://"

    private const val HEX = "0123456789ABCDEF"

    /**
     * 解析结果。
     *
     * @property backendId 后端 id（与 StorageBackend.id 逐字相等）。
     * @property path 后端内路径（与 StorageBackend.openRead 的 path 同口径）。
     */
    data class Parsed(val backendId: String, val path: String)

    /** 拼伪 URI；[backendId] 与 [path] 原样可逆（[parse] 能还原出完全相同的两个字符串）。 */
    fun format(backendId: String, path: String): String =
        PREFIX + encodeComponent(backendId) + "/" + encodePath(path)

    /**
     * 解析 [format] 产出的伪 URI。
     *
     * 宽松口径：前缀不对、authority 为空、字符串不完整时返回 null，**不抛异常**。
     */
    fun parse(uri: String): Parsed? {
        if (!uri.startsWith(PREFIX)) return null
        val rest = uri.substring(PREFIX.length)
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        if (authority.isEmpty()) return null
        val rawPath = if (slash < 0) "" else rest.substring(slash + 1)
        return Parsed(backendId = decode(authority), path = decodePath(rawPath))
    }

    /** 是否是本方案能识别的伪 URI。 */
    fun isMediaUri(uri: String): Boolean = parse(uri) != null

    // ------------------------------------------------------------------ 编解码

    /** 单段编码：只保留 RFC 3986 的 unreserved 字符，其余按 UTF-8 逐字节转义。 */
    private fun encodeComponent(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            val unreserved = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~'
            if (unreserved) {
                out.append(c)
            } else {
                out.append('%').append(HEX[b ushr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    /** 路径编码：逐段编码后仍用 / 连接（层级可读，段内字符不会被误解成路径分隔符）。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeComponent(it) }

    /** 路径解码：先按 / 切段再逐段解码，避免段内的 %2F 被当成分隔符。 */
    private fun decodePath(path: String): String =
        path.split('/').joinToString("/") { decode(it) }

    /** 百分号解码；非法转义（% 后不是两位十六进制）原样保留，绝不抛异常。 */
    private fun decode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** 十六进制字符值；不是十六进制返回 -1。 */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
