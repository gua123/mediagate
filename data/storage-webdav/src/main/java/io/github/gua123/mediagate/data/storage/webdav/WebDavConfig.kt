package io.github.gua123.mediagate.data.storage.webdav

import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * WebDAV 连接配置（R2 协议接入 / R7 多地址 / R8 连通性测试 / R14 字幕写回）。
 *
 * 一个连接记录可以挂多组地址（LAN / 公网域名，R7），每组地址对应一份本配置；凭据的加密存储
 * （Keystore）由上层负责，后端只按这里给的账号密码发请求。
 *
 * **路径语义**：后端内所有路径都是「相对 [rootPath] 的树内路径」（POSIX 风格，空串或 `/` 表示根）。
 * 请求 URL = [baseUrl] + [rootPath] + 树内路径。例如 baseUrl = `https://dav.example.com`、
 * rootPath = `/media` 时，`stat("电影/a.mkv")` 请求 `https://dav.example.com/media/电影/a.mkv`。
 *
 * @param baseUrl 服务地址，必须以 `http://` 或 `https://` 开头；尾部 `/` 会被忽略。
 * @param username Basic 认证用户名；与 [password] 同时为空表示匿名访问。
 * @param password Basic 认证密码；允许空串（少数服务器接受空密码）。
 * @param rootPath 根路径：挂在 [baseUrl] 之下的子目录，默认 `/`（即 [baseUrl] 本身）。
 * @param connectTimeoutMs TCP 连接超时（plan 4.2 的口径：默认 5 s）。
 * @param readTimeoutMs 单次读超时（plan 4.2：默认 15 s）；注意这是「两次读之间」的超时，不是整段下载时长。
 * @param writeTimeoutMs 写入超时（R14 字幕写回）。
 * @param allowInsecureHttp 是否允许明文 http。局域网自用（192.168.1.10）默认允许；只走公网域名时建议关掉，
 *   关掉后 `http://` 会在构造配置时直接报错，避免凭据走明文。
 * @param extraHeaders 附加请求头（如自建反代的 `X-Auth-Token`）。协议头（Range / Depth / Content-Type /
 *   Accept-Encoding）以后端内部为准，同名会被覆盖；若这里给了 `Authorization`，则不再附加 Basic 头
 *   （认证完全交给调用方）。
 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val rootPath: String = "/",
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    val allowInsecureHttp: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
) {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl 不能为空" }
        val scheme = baseUrl.trim().substringBefore("://", "").lowercase()
        require(scheme == "http" || scheme == "https") { "baseUrl 必须以 http:// 或 https:// 开头：$baseUrl" }
        require(scheme != "http" || allowInsecureHttp) {
            "不允许明文 http（确实要用请显式 allowInsecureHttp = true）：$baseUrl"
        }
        require(hostOf(baseUrl).isNotEmpty()) { "baseUrl 缺少主机名：$baseUrl" }
        require(connectTimeoutMs > 0L && readTimeoutMs > 0L && writeTimeoutMs > 0L) {
            "超时必须为正数：connect=$connectTimeoutMs read=$readTimeoutMs write=$writeTimeoutMs"
        }
        require(extraHeaders.keys.none { it.isBlank() }) { "extraHeaders 的键不能为空" }
        // rootPath 的越界检查（.. 段）在归一化里做，这里翻译成配置错误
        try {
            normalizeWebDavPath(rootPath)
        } catch (e: StorageException.AccessDenied) {
            throw IllegalArgumentException("rootPath 不合法：$rootPath", e)
        }
    }

    /** 请求基址：去掉尾部 `/` 的 [baseUrl]，[rootPath] 非根时拼在其后。 */
    val requestBaseUrl: String = buildRequestBaseUrl(baseUrl, rootPath)

    /** [requestBaseUrl] 的 URL 路径（已百分号解码），用于把服务器回的 href 剥成树内路径。 */
    val basePath: String = percentDecode(urlPathOf(requestBaseUrl))

    /** 主机名（不含端口与用户信息），用于 DNS 失败提示。 */
    val host: String = hostOf(baseUrl)

    /** 是否需要附加 Basic 认证头（无用户名，或调用方自带 Authorization 时为 false）。 */
    val useBasicAuth: Boolean = !username.isNullOrEmpty() && extraHeaders.keys.none { it.equals("Authorization", true) }

    companion object {
        /** plan 4.2：连接 5 s。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 5_000L

        /** plan 4.2：读取 15 s。 */
        const val DEFAULT_READ_TIMEOUT_MS: Long = 15_000L

        /** R14 字幕写回：与读超时同量级。 */
        const val DEFAULT_WRITE_TIMEOUT_MS: Long = 15_000L
    }
}
