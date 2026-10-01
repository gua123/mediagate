package io.github.gua123.mediagate.feature.connections

/**
 * 连接地址 → 请求基址（**R7** 多地址 / **R8** 测试）。
 *
 * 由 :app 在构造 WebDAV 后端时复用（:feature:connections 与 :app 用同一份拼接口径，
 * 避免"测试用的地址"和"真正请求的地址"写得不一样）。
 */
object ConnectionEndpoints {

    /**
     * 拼接 `scheme://host[:port]`。
     *
     * 端口省略规则：`<= 0`（本地路径等无端口场景）或等于该方案的默认端口（http 80 / https 443）
     * 时不写端口，保证 URL 与用户心里的写法一致。
     */
    fun baseUrl(scheme: String, host: String, port: Int): String {
        val cleanScheme = scheme.trim().lowercase()
        val cleanHost = host.trim()
        val defaultPort = when (cleanScheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        return if (port <= 0 || port == defaultPort) {
            cleanScheme + "://" + cleanHost
        } else {
            cleanScheme + "://" + cleanHost + ":" + port
        }
    }
}
