package io.github.gua123.mediagate.media.playback

/**
 * mediagate 伪 URI（R4 拖拽 seek / R18 后台播放 / M2 LibVLC 与回环代理复用）。
 *
 * 形态：mediagate://<backendId>/<path>，两段都是百分号编码的，解析无歧义：
 * - **authority = 后端 id**：[io.github.gua123.mediagate.data.storage.api.StorageBackend.id] 里带 : 与 /
 *   （如 local-file:/storage/emulated/0），所以整段按 URI 组件编码（: → %3A、/ → %2F）；
 * - **path = 后端内路径**：保留 / 作为层级分隔符，逐段编码（空格、#、?、中文…），
 *   因此人眼可读：mediagate://local-file%3A%2Fstorage%2Femulated%2F0/Music/song.mp3。
 *
 * 为什么要它：Media3 的 [androidx.media3.datasource.DataSpec] 只认 android.net.Uri，
 * 而播放器需要「后端 id + 后端内路径」这对信息；把它编进 URI 之后，同一个
 * [BackendDataSource] 就能服务任意后端（本地 / WebDAV / SFTP / FTP），
 * 将来 LibVLC 的回环 HTTP 代理也能用同一套字符串寻址。
 *
 * 纯 Kotlin（只用 JDK 的 UTF-8 编解码），可直接 JVM 单测。
 */
object MediaUri {

    /** 伪 URI 的 scheme。 */
    const val SCHEME = "mediagate"

    /** 前缀（SCHEME + 冒号双斜杠）。 */
    private const val PREFIX = "mediagate://"

    private const val HEX = "0123456789ABCDEF"

    /**
     * 解析结果。
     *
     * @property backendId 后端 id（与 [io.github.gua123.mediagate.data.storage.api.StorageBackend.id] 逐字相等）。
     * @property path 后端内的路径（与 [io.github.gua123.mediagate.data.storage.api.StorageBackend.openRead] 的 path 同口径）。
     */
    data class Parsed(val backendId: String, val path: String)

    /**
     * 拼伪 URI：[backendId] 与 [path] 都原样可逆（[fromUri] 能还原出完全相同的两个字符串）。
     *
     * @param backendId 后端 id。
     * @param path 后端内路径；空串表示后端的根。
     */
    fun format(backendId: String, path: String): String =
        PREFIX + encodeComponent(backendId) + "/" + encodePath(path)

    /**
     * 解析 [format] 产出的伪 URI。
     *
     * 宽松口径：scheme 不是 mediagate、authority 为空、字符串不完整时返回 null，
     * **不抛异常**（调用方决定怎么报错）。
     */
    fun fromUri(uri: String): Parsed? {
        if (!uri.startsWith(PREFIX)) return null
        val rest = uri.substring(PREFIX.length)
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        if (authority.isEmpty()) return null
        val rawPath = if (slash < 0) "" else rest.substring(slash + 1)
        return Parsed(backendId = decode(authority), path = decodePath(rawPath))
    }

    /** 是否是本方案能识别的伪 URI。 */
    fun isMediaUri(uri: String): Boolean = fromUri(uri) != null

    // ------------------------------------------------------------------ 编解码

    /** 单段编码：只保留 unreserved 字符（RFC 3986 的 A-Za-z0-9-._~），其余按 UTF-8 逐字节转义。 */
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
