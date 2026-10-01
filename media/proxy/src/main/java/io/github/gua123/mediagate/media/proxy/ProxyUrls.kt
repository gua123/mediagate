package io.github.gua123.mediagate.media.proxy

/**
 * 回环代理的地址口径（plan 4.6：LibVLC 与「分享播放地址」复用同一数据层）。
 *
 * 形态：`http://127.0.0.1:<port>/m/<backendId 段>/<path 段>`，
 * 其中 `/m/` 之后的部分与 [MediaUriCodec] 的 `mediagate://` 之后**逐字相同**：
 * 请求路径里带着伪 URI 的原始百分号编码，服务端只按 MediaUri 口径解码一次
 * （段内的 %2F 不会被当成路径分隔符，见 [MediaUriCodec]）。
 *
 * 为什么用路径而不是查询串：VLC / 外部播放器 / 浏览器对 path 的处理最保守，
 * 不会像某些客户端那样重写查询串；而路径层级仍然人眼可读。
 *
 * 纯 Kotlin，JVM 可直接单测。
 */
object ProxyUrls {

    /** 代理地址路径前缀。 */
    const val PREFIX = "/m/"

    /** 由后端与路径拼出 LibVLC 可播的回环地址。 */
    fun format(baseUrl: String, backendId: String, path: String): String =
        baseUrl.trimEnd('/') + PREFIX + MediaUriCodec.format(backendId, path).removePrefix(MediaUriCodec.PREFIX)

    /** 由 `mediagate://` 伪 URI 拼回环地址；URI 非法时返回 null。 */
    fun formatFor(baseUrl: String, mediaUri: String): String? {
        val parsed = MediaUriCodec.parse(mediaUri) ?: return null
        return format(baseUrl, parsed.backendId, parsed.path)
    }

    /**
     * 把 HTTP 请求目标（request-target）解析回 (backendId, path)。
     *
     * 只做「去掉查询串/片段 + 去掉前缀」两步，**不做百分号解码**——解码交给
     * [MediaUriCodec.parse]，保证与播放侧口径完全一致（只解码一次）。
     *
     * @return 前缀不匹配、缺少 authority 或 URI 形态非法时返回 null（调用方回 404/400）。
     */
    fun parse(requestTarget: String): MediaUriCodec.Parsed? {
        val pathOnly = requestTarget.substringBefore('?').substringBefore('#')
        if (!pathOnly.startsWith(PREFIX)) return null
        val rest = pathOnly.substring(PREFIX.length)
        if (rest.isEmpty()) return null
        return MediaUriCodec.parse(MediaUriCodec.PREFIX + rest)
    }
}
