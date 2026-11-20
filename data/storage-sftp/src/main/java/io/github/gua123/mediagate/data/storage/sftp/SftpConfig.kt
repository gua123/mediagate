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
 * @param maxChannels 整个池的通道上限 = 并发读上限（plan 4.1「单会话复用 + 通道池」）。
 *   老口径就是「一条会话上开几条通道」；[maxSessions] > 1 时它是**全池预算**，
 *   实际并发由 [channelCapacity] 表达（通道位会摊到多条会话上）。
 * @param maxSessions 池里最多几条 SSH 会话。**1 = 老的单会话行为**；
 *   > 1 时按需懒加载（见 SftpChannelPool），多连接叠加吞吐（2026-11-18 实测口径）。
 * @param channelsPerSession 每条会话上最多几条 SFTP 通道（OpenSSH MaxSessions 默认 10，
 *   第 11 条会被拒：channel is not opened）。
 * @param largeFileBytes 「大文件」门槛：≥ 这个大小按大文件开满会话（[sessionsForSize]）。
 * @param smallFileSessions 小文件只开这么多条会话（建连开销 > 收益时别硬开满）。
 * @param acquireTimeoutMs 取不到通道位 / 建不起会话时的最长等待，超时抛 StorageException.Timeout。
 * @param metadataChannels 元数据（list / stat）在**独立会话**上开的临时通道并发上限
 *   （同一会话的 MaxSessions 也是 10，别把元数据会话自己挤爆）。
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
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val channelsPerSession: Int = DEFAULT_CHANNELS_PER_SESSION,
    val largeFileBytes: Long = DEFAULT_LARGE_FILE_BYTES,
    val smallFileSessions: Int = DEFAULT_SMALL_FILE_SESSIONS,
    val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    val metadataChannels: Int = DEFAULT_METADATA_CHANNELS,
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
        require(maxSessions in 1..MAX_SESSIONS) { "会话数必须在 1-" + MAX_SESSIONS + "：" + maxSessions }
        require(maxSessions * minOf(channelsPerSession, maxChannels) <= MAX_POOL_CONCURRENCY) {
            "全池并发不能超过 " + MAX_POOL_CONCURRENCY + "（会话 × 每会话通道）：" + maxSessions + " × " + channelsPerSession
        }
        require(channelsPerSession in 1..MAX_CHANNELS_PER_SESSION) {
            "单会话通道数必须在 1-" + MAX_CHANNELS_PER_SESSION + "：" + channelsPerSession
        }
        // 不要求 smallFileSessions <= maxSessions：单会话配置（老行为）下它就是 2，由 sessionsForSize 夹到 1
        require(smallFileSessions >= 1) { "小文件会话数必须为正：" + smallFileSessions }
        require(largeFileBytes >= 0L) { "大文件门槛不能为负：" + largeFileBytes }
        require(acquireTimeoutMs > 0L) { "通道等待超时必须为正数：" + acquireTimeoutMs }
        require(metadataChannels in 1..MAX_CHANNELS_PER_SESSION) {
            "元数据通道数必须在 1-" + MAX_CHANNELS_PER_SESSION + "：" + metadataChannels
        }
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
     * 每条会话实际能开几条通道 = `min(channelsPerSession, maxChannels)`。
     *
     * [maxChannels] 是老口径的「通道池大小」：单会话时它就是全部并发（老行为原样保留）；
     * 多会话时它是**每条会话**的上限，总量见 [channelCapacity]。
     */
    val effectiveChannelsPerSession: Int = minOf(channelsPerSession, maxChannels)

    /**
     * **全池通道预算** = 同时能借出的通道数（`caps.maxParallelReads` 用它）。
     *
     * = 会话数 × 每条会话的通道数：App 默认 20 × 10 = 200（2026-11-18 实测口径）；
     * 单会话老配置下它就等于 [maxChannels]。
     */
    val channelCapacity: Int = maxSessions * effectiveChannelsPerSession

    /**
     * 按文件大小决定「该开几条会话」（2026-11-18 用户拍板：小文件 2 条、大文件开满）。
     *
     * 大小未知（null）按小文件处理：宁可少开，也不要为一个刚 stat 出来的路径先建 20 条会话。
     */
    fun sessionsForSize(size: Long?): Int {
        val small = minOf(smallFileSessions, maxSessions).coerceAtLeast(1)
        if (size == null || size < largeFileBytes) return small
        return maxSessions
    }

    /**
     * **打开多连接加速**（2026-11-18 实测口径：单连接 2.2 MB/s → 20 连接 × 10 通道 23-25 MB/s）。
     *
     * 只改池参数，不动地址/凭据/超时；目录与播放共用同一个池（元数据本身走独立临时通道）。
     * 默认值是「App 默认」：内网公网都按 20 条，小文件（<512 MB）自动只开 2 条。
     */
    fun withMultiConnectionAcceleration(
        sessions: Int = MULTI_CONNECTION_SESSIONS,
        channelsPerSession: Int = MULTI_CONNECTION_CHANNELS_PER_SESSION,
    ): SftpConfig = copy(
        maxSessions = sessions,
        channelsPerSession = channelsPerSession,
        maxChannels = sessions * channelsPerSession,
    )

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
            ", maxChannels=" + maxChannels + ", maxSessions=" + maxSessions +
            ", channelsPerSession=" + channelsPerSession +
            ", largeFileBytes=" + largeFileBytes + ", smallFileSessions=" + smallFileSessions + ")"

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
         * 4 是「播放 + 抽帧 + 缩略图」并行的折中——**单会话时代的默认值**。
         * App 走 [withMultiConnectionAcceleration] 会把它换成 200（20 × 10）。
         */
        const val DEFAULT_MAX_CHANNELS: Int = 4

        /** 默认只开一条会话（= 老行为）；多连接由 [withMultiConnectionAcceleration] 打开。 */
        const val DEFAULT_MAX_SESSIONS: Int = 1

        /** OpenSSH MaxSessions 默认 10：一条 SSH 连接上第 11 条通道会被拒。 */
        const val DEFAULT_CHANNELS_PER_SESSION: Int = 10

        /** 元数据临时通道的默认并发上限（同一个元数据会话上也别超过 10 条）。 */
        const val DEFAULT_METADATA_CHANNELS: Int = 4

        /** 小文件门槛：小于 512 MB 只开 [DEFAULT_SMALL_FILE_SESSIONS] 条会话（建连开销 > 收益）。 */
        const val DEFAULT_LARGE_FILE_BYTES: Long = 512L * 1024L * 1024L

        /** 小文件只开 2 条会话（2026-11-18 用户拍板）。 */
        const val DEFAULT_SMALL_FILE_SESSIONS: Int = 2

        /** 通道等待上限：取不到通道位的请求最多等 15 秒（超时走界面「重试」）。 */
        const val DEFAULT_ACQUIRE_TIMEOUT_MS: Long = 15_000L

        /** App 默认：20 条会话（2026-11-18 实测：20 连接 × 10 通道 ≈ 23-25 MB/s）。 */
        const val MULTI_CONNECTION_SESSIONS: Int = 20

        /** App 默认：每条会话 10 条通道（= OpenSSH MaxSessions 默认上限）。 */
        const val MULTI_CONNECTION_CHANNELS_PER_SESSION: Int = 10

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        /** 全池通道上限：200 = 20 × 10，再大只会把服务器/自己打爆。 */
        private const val MAX_CHANNELS = 200

        /** 会话数上限（32 条 TCP + 每条约 1 MB 栈，手机上已经够狠了）。 */
        private const val MAX_SESSIONS = 32

        /** 单会话通道数上限。 */
        private const val MAX_CHANNELS_PER_SESSION = 32

        /** 全池并发上限（200 = 20 × 10，实测口径；再大只会把服务器打爆）。 */
        private const val MAX_POOL_CONCURRENCY = 200
    }
}
