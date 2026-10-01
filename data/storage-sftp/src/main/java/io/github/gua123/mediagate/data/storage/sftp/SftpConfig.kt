package io.github.gua123.mediagate.data.storage.sftp

import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * SFTP 主机密钥信任策略（**R2** 协议接入 / plan 4.9「SFTP 主机密钥 TOFU + 变更告警」）。
 *
 * 三种策略的区别只在**未知主机第一次见面**时体现；一旦指纹被记住，
 * **密钥变更在任何策略下都不会被静默接受**（见 [TofuHostKeyVerifier]）。
 */
enum class SftpHostKeyPolicy {

    /**
     * 严格：只信任 [KnownHostsStore] 里已有的主机，未知主机一律拒绝。
     *
     * 适合上层把指纹落库（Keystore / Room）后由用户手工确认的场景。
     */
    STRICT,

    /**
     * **默认**：Trust On First Use——第一次连接记住指纹并放行，
     * 之后指纹变了就拒绝连接并触发 [HostKeyChangeEvent] 告警（防中间人）。
     */
    TOFU,

    /**
     * 调试用：任何主机密钥都放行。
     *
     * **不是「静默」**：密钥变更时仍然触发 [HostKeyChangeEvent]、仍然写日志，
     * 只是不中断连接。生产/自用默认不要选它。
     */
    ACCEPT_ANY,
}

/**
 * SFTP 私钥认证材料（**R2**：密码或私钥二选一）。
 *
 * 只承载字节，不落盘：私钥文本由上层从 Keystore / 文件读出后传进来。
 * [toString] 刻意只输出名字，避免私钥被日志或异常信息带出去。
 *
 * @param privateKey 私钥文本（PEM / OpenSSH 格式）。
 * @param publicKey 公钥文本；可为 null（JSch 能从私钥推导）。
 * @param passphrase 私钥口令；无口令为 null。
 * @param name JSch 身份名（仅用于日志区分多把钥匙）。
 */
class SftpPrivateKey(
    val privateKey: ByteArray,
    val publicKey: ByteArray? = null,
    val passphrase: String? = null,
    val name: String = DEFAULT_NAME,
) {

    init {
        require(privateKey.isNotEmpty()) { "私钥内容不能为空" }
        require(name.isNotBlank()) { "私钥名字不能为空" }
    }

    /** 是否带口令。 */
    val hasPassphrase: Boolean get() = !passphrase.isNullOrEmpty()

    override fun toString(): String = "SftpPrivateKey(name=" + name + ", encrypted=" + hasPassphrase + ")"

    companion object {
        const val DEFAULT_NAME: String = "mediagate-sftp-key"
    }
}

/**
 * SFTP 连接配置（**R2** 协议接入 / **R7** 多地址 / **R8** 连通性测试 / **R14** 字幕写回）。
 *
 * 一个连接记录可以挂多组地址（LAN / 公网域名，R7），每组地址对应一份本配置；
 * 凭据的加密存储（Keystore）由上层负责，后端只按这里给的材料登录。
 *
 * **路径语义**：后端内所有路径都是「相对 [basePath] 的树内路径」（POSIX 风格，空串或 `/` 表示 [basePath] 本身）。
 * 例如 basePath = `/media` 时，`stat("电影/a.mkv")` 实际操作远端的 `/media/电影/a.mkv`。
 *
 * @param host 主机名或 IP。
 * @param port SSH 端口，默认 22。
 * @param username 登录用户名。
 * @param password 密码认证；与 [privateKey] 至少给一个（都给时 JSch 先试公钥）。
 * @param privateKey 私钥认证材料；null 表示只用密码。
 * @param basePath 根路径：账号下的起始目录，默认 `/`（服务器 chroot 根或账号家目录的上级）。
 * @param connectTimeoutMs TCP + SSH 握手超时（plan 4.2 的口径：默认 5 s）。
 * @param readTimeoutMs 单次读写超时（plan 4.2：默认 15 s）。
 * @param hostKeyPolicy 主机密钥策略（plan 4.9：默认 TOFU）。
 * @param maxChannels 通道池大小 = 并发读上限（plan 4.1「单会话复用 + 通道池」）。
 */
data class SftpConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val password: String? = null,
    val privateKey: SftpPrivateKey? = null,
    val basePath: String = "/",
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val hostKeyPolicy: SftpHostKeyPolicy = SftpHostKeyPolicy.TOFU,
    val maxChannels: Int = DEFAULT_MAX_CHANNELS,
) {

    init {
        require(host.isNotBlank()) { "host 不能为空" }
        require(port in MIN_PORT..MAX_PORT) { "端口必须在 " + MIN_PORT + "-" + MAX_PORT + "：" + port }
        require(username.isNotBlank()) { "username 不能为空" }
        require(password != null || privateKey != null) { "必须提供密码或私钥" }
        require(connectTimeoutMs > 0L && readTimeoutMs > 0L) {
            "超时必须为正数：connect=" + connectTimeoutMs + " read=" + readTimeoutMs
        }
        require(maxChannels in 1..MAX_CHANNELS) { "通道池大小必须在 1-" + MAX_CHANNELS + "：" + maxChannels }
        // basePath 的 `..` 越界在归一化里拦，这里翻译成配置错误
        try {
            normalizeSftpPath(basePath)
        } catch (e: StorageException.AccessDenied) {
            throw IllegalArgumentException("basePath 不合法：" + basePath, e)
        }
    }

    /** 归一化后的根路径：以 `/` 开头、无尾斜杠（根就是 `/`）。 */
    internal val rootPath: String = absoluteSftpRoot(basePath)

    /**
     * 后端唯一标识（**R2** 缓存 key）：`sftp://用户名@主机:端口/根路径`。
     *
     * 同一物理位置不管 basePath 写成 `/media` 还是 `media/` 都得到同一个 id，
     * TS 索引与缩略图缓存不会因为配置写法不同而各存一份（plan 第 7 章）。
     */
    val id: String = "sftp://" + username + "@" + host + ":" + port + rootPath

    /** 刻意不打印密码与私钥（R6/plan 4.9：凭据不进日志）。 */
    override fun toString(): String =
        "SftpConfig(host=" + host + ", port=" + port + ", username=" + username +
            ", password=" + (if (password.isNullOrEmpty()) "无" else "***") +
            ", privateKey=" + (privateKey?.toString() ?: "无") +
            ", basePath=" + rootPath + ", hostKeyPolicy=" + hostKeyPolicy +
            ", maxChannels=" + maxChannels + ")"

    companion object {
        /** SSH 默认端口。 */
        const val DEFAULT_PORT: Int = 22

        /** plan 4.2：连接 5 s。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 5_000L

        /** plan 4.2：读取 15 s。 */
        const val DEFAULT_READ_TIMEOUT_MS: Long = 15_000L

        /**
         * 通道池默认大小（plan 4.1「单会话复用 + 通道池」/ plan 4.4「SFTP/FTP 降到 2」）。
         *
         * 4 是「播放 + 抽帧 + 缩略图」并行的折中；上限 16 防止把服务器打爆。
         */
        const val DEFAULT_MAX_CHANNELS: Int = 4

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val MAX_CHANNELS = 16
    }
}
