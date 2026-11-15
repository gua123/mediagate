package io.github.gua123.mediagate.media.proxy

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 回环代理的地址口径（plan 4.6：LibVLC 与「分享播放地址」复用同一数据层）。
 *
 * 形态：http://127.0.0.1:<port>/m/<backendId 段>/<path 段>，
 * 其中 /m/ 之后的部分与 MediaUriCodec 的 mediagate:// 之后逐字相同：
 * 请求路径里带着伪 URI 的原始百分号编码，服务端只按 MediaUri 口径解码一次
 * （段内的 %2F 不会被当成路径分隔符，见 MediaUriCodec）。
 *
 * 为什么用路径而不是查询串：VLC / 外部播放器 / 浏览器对 path 的处理最保守，
 * 不会像某些客户端那样重写查询串；而路径层级仍然人眼可读。
 *
 * 纯 Kotlin，JVM 可直接单测。
 */
object ProxyUrls {

    /** 代理地址路径前缀。 */
    const val PREFIX = "/m/"

    /** token 形态前缀（/m/t.<base64url>）：路径里有 #/?/% 时改用，避免客户端把 # 当片段。 */
    private const val TOKEN_PREFIX = "t."

    /** 由后端与路径拼出 LibVLC 可播的回环地址（可读 legacy 形态）。 */
    fun format(baseUrl: String, backendId: String, path: String): String =
        baseUrl.trimEnd('/') + PREFIX + MediaUriCodec.format(backendId, path).removePrefix(MediaUriCodec.PREFIX)

    /**
     * LibVLC 专用地址：路径含 # / ? / % 时改用 base64url token。
     *
     * 2026-11-15 用户反馈「本地文件能播、局域网不行」；可疑文件名带 #（如
     * #Aki秋水 小年夜迟到的老公.mp4）。虽然 legacy 地址会把 # 编码成 %23，
     * 但部分 HTTP 客户端/URL 解析器仍会把 # 当 fragment 截断路径，导致代理 stat 404。
     * token 只含 A-Z a-z 0-9 - _，不经过任何特殊字符。
     */
    fun formatSafe(baseUrl: String, backendId: String, path: String): String {
        val legacyPath = MediaUriCodec.format(backendId, path).removePrefix(MediaUriCodec.PREFIX)
        val risky = legacyPath.any { it == '%' || it == '#' || it == '?' }
        if (!risky) return baseUrl.trimEnd('/') + PREFIX + legacyPath
        val payload = backendId + "\u0000" + path
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return baseUrl.trimEnd('/') + PREFIX + TOKEN_PREFIX + token
    }

    /** 由 mediagate:// 伪 URI 拼回环地址；URI 非法时返回 null。 */
    fun formatFor(baseUrl: String, mediaUri: String): String? {
        val parsed = MediaUriCodec.parse(mediaUri) ?: return null
        return format(baseUrl, parsed.backendId, parsed.path)
    }

    /**
     * 把 HTTP 请求目标（request-target）解析回 (backendId, path)。
     *
     * 只做「去掉查询串/片段 + 去掉前缀」两步，不做百分号解码——解码交给
     * MediaUriCodec.parse，保证与播放侧口径完全一致（只解码一次）。
     * token 形态则直接 base64url 解码，不经过 URI 解析。
     *
     * @return 前缀不匹配、缺少 authority 或 URI 形态非法时返回 null（调用方回 404/400）。
     */
    fun parse(requestTarget: String): MediaUriCodec.Parsed? {
        val pathOnly = requestTarget.substringBefore('?').substringBefore('#')
        if (!pathOnly.startsWith(PREFIX)) return null
        val rest = pathOnly.substring(PREFIX.length)
        if (rest.isEmpty()) return null
        if (rest.startsWith(TOKEN_PREFIX)) {
            val token = rest.substring(TOKEN_PREFIX.length)
            if (token.isEmpty()) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull() ?: return null
            val raw = String(bytes, StandardCharsets.UTF_8)
            val separator = raw.indexOf('\u0000')
            if (separator <= 0) return null
            return MediaUriCodec.Parsed(
                backendId = raw.substring(0, separator),
                path = raw.substring(separator + 1),
            )
        }
        return MediaUriCodec.parse(MediaUriCodec.PREFIX + rest)
    }
}
