package io.github.gua123.mediagate.data.storage.ftp

import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * FTPS 加密模式（**R2** 协议接入 / plan 4.9 安全）。
 *
 * - [NONE]：明文 FTP（局域网自用默认；凭据走明文，只在可信网络用）；
 * - [EXPLICIT]：显式 FTPS（AUTH TLS，控制与数据连接都升级），监听端口通常还是 21；
 * - [IMPLICIT]：隐式 FTPS（连上就 TLS），监听端口通常是 990。
 */
enum class FtpsMode {

    /** 明文 FTP。 */
    NONE,

    /** 显式 FTPS：先明文连接，再用 AUTH TLS 升级（RFC 4217）。 */
    EXPLICIT,

    /** 隐式 FTPS：连接即 TLS。 */
    IMPLICIT,
    ;

    /** 是否启用 TLS。 */
    val secure: Boolean get() = this != NONE
}

/**
 * FTP 连接配置（**R2** 协议接入 / **R7** 多地址 / **R8** 连通性测试 / **R14** 字幕写回）。
 *
 * 一个连接记录可以挂多组地址（LAN / 公网域名，R7），每组地址对应一份本配置；
 * 凭据的加密存储（Keystore）由上层负责，后端只按这里给的材料登录。
 *
 * **路径语义**：后端内所有路径都是「相对 [basePath] 的树内路径」（POSIX 风格，空串或 `/` 表示 [basePath] 本身）。
 *
 * @param host 主机名或 IP。
 * @param port 控制端口；明文与显式 FTPS 通常是 21，隐式 FTPS 通常是 990。
 * @param username 登录用户名。
 * @param password 登录密码；允许空串（少数服务器接受空密码）。
 * @param basePath 根路径：账号下的起始目录，默认 `/`。
 * @param ftpsMode 加密模式（默认明文；plan 4.9 要求明文只对用户配置的 FTP 主机放开）。
 * @param passive 是否用被动模式（plan 4.1「PASV 优先」）。
 * @param connectTimeoutMs TCP + 220 banner 超时（plan 4.2 的口径：默认 5 s）。
 * @param readTimeoutMs 数据连接读写超时（plan 4.2：默认 15 s）。
 * @param maxConnections 控制连接池大小 = 并发读上限（FTP 传输期间控制连接被占用，必须多条）。
 */
data class FtpConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val password: String? = null,
    val basePath: String = "/",
    val ftpsMode: FtpsMode = FtpsMode.NONE,
    val passive: Boolean = true,
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
) {

    init {
        require(host.isNotBlank()) { "host 不能为空" }
        require(port in MIN_PORT..MAX_PORT) { "端口必须在 " + MIN_PORT + "-" + MAX_PORT + "：" + port }
        require(username.isNotBlank()) { "username 不能为空" }
        require(connectTimeoutMs > 0L && readTimeoutMs > 0L) {
            "超时必须为正数：connect=" + connectTimeoutMs + " read=" + readTimeoutMs
        }
        require(maxConnections in 1..MAX_CONNECTIONS) {
            "控制连接池大小必须在 1-" + MAX_CONNECTIONS + "：" + maxConnections
        }
        try {
            normalizeFtpPath(basePath)
        } catch (e: StorageException.AccessDenied) {
            throw IllegalArgumentException("basePath 不合法：" + basePath, e)
        }
    }

    /** 归一化后的根路径：以 `/` 开头、无尾斜杠（根就是 `/`）。 */
    internal val rootPath: String = absoluteFtpRoot(basePath)

    /**
     * 后端唯一标识（**R2** 缓存 key）：`ftp://用户名@主机:端口/根路径`。
     *
     * 同一物理位置不管 basePath 写成 `/media` 还是 `media/` 都得到同一个 id（plan 第 7 章）。
     * 加密模式不参与 id：同一个服务换加密方式不该让缓存全部失效。
     */
    val id: String = "ftp://" + username + "@" + host + ":" + port + rootPath

    /** 刻意不打印密码（R6/plan 4.9：凭据不进日志）。 */
    override fun toString(): String =
        "FtpConfig(host=" + host + ", port=" + port + ", username=" + username +
            ", password=" + (if (password.isNullOrEmpty()) "无" else "***") +
            ", basePath=" + rootPath + ", ftps=" + ftpsMode + ", passive=" + passive +
            ", maxConnections=" + maxConnections + ")"

    companion object {
        /** FTP 默认控制端口。 */
        const val DEFAULT_PORT: Int = 21

        /** plan 4.2：连接 5 s。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 5_000L

        /** plan 4.2：读取 15 s。 */
        const val DEFAULT_READ_TIMEOUT_MS: Long = 15_000L

        /**
         * 控制连接池默认大小（plan 4.1「FTP 每次 REST+RETR」：传输期间控制连接不能复用）。
         *
         * 上限 16 防止把服务器打爆（很多 FTP 服务器限制单 IP 并发连接数）。
         */
        const val DEFAULT_MAX_CONNECTIONS: Int = 4

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val MAX_CONNECTIONS = 16
    }
}
