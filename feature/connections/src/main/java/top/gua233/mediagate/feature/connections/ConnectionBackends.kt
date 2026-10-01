package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.data.storage.ftp.FtpConfig
import io.github.gua123.mediagate.data.storage.ftp.FtpsMode
import io.github.gua123.mediagate.data.storage.sftp.SftpConfig
import io.github.gua123.mediagate.data.storage.sftp.SftpHostKeyPolicy
import io.github.gua123.mediagate.data.storage.sftp.SftpPrivateKey

/**
 * 连接记录 + 首选地址 → 各协议的后端配置（**R2** 四协议接入 App / **R7** 多地址 / **R8** 当前连接）。
 *
 * 为什么和 [StorageConnectionHandshakes] 分成两个对象：
 * - [StorageConnectionHandshakes] 造的是**一次性探测**用的后端（测完立刻 close，主机密钥只用内存 TOFU）；
 * - 这里造的是**长期使用**的配置（浏览 / 播放 / 缩略图 / 字幕写回都吃它，主机密钥由 :app 注入共享校验器），
 *   由 :app 的 `applyCurrentConnection` 调用（SFTP / FTP 与 WebDAV 走同一条「选路 → 解密 → 建后端」流程）。
 *
 * 两份装配必须共用同一套字段口径（地址、根路径、用户名、超时、FTPS 模式），改一处就得同步另一处。
 * 全部是纯函数、零 IO，所以直接 JVM 单测覆盖（见 `ConnectionBackendsTest`）。
 */
object ConnectionBackends {

    /**
     * SFTP 后端配置（**R2** / **R6**）。
     *
     * 密码由 :app 用 Keystore 解密后传入；没有密码也没有私钥时 [SftpConfig] 会抛
     * `IllegalArgumentException`（"必须提供密码或私钥"），调用方据此给用户一句能照做的提示。
     *
     * @param secret 解密后的明文密码；null = 没存过密码（此时必须给 [privateKey]，否则配置不合法）。
     * @param privateKey 私钥认证材料；当前界面还没有私钥入口（自用先走密码），留参数给后续。
     * @param hostKeyPolicy 主机密钥策略（plan 4.9：默认 TOFU；校验器由调用方另行注入）。
     */
    fun sftpConfig(
        record: ConnectionRecord,
        address: SelectableAddress,
        secret: String?,
        privateKey: SftpPrivateKey? = null,
        hostKeyPolicy: SftpHostKeyPolicy = SftpHostKeyPolicy.TOFU,
    ): SftpConfig = SftpConfig(
        host = address.host.trim(),
        port = address.port,
        username = record.username.orEmpty(),
        password = secret,
        privateKey = privateKey,
        basePath = record.basePath.ifBlank { "/" },
        connectTimeoutMs = record.options.connectTimeoutMs ?: SftpConfig.DEFAULT_CONNECT_TIMEOUT_MS,
        hostKeyPolicy = hostKeyPolicy,
    )

    /**
     * FTP / FTPS 后端配置（**R2** / **R6**）。
     *
     * 与连通性测试同一口径：`connection.tls` 认不出的值一律当明文（[FtpsMode.NONE]），
     * 不把用户配的明文连接悄悄升成 TLS；密码允许空串（少数服务器接受空密码）。
     */
    fun ftpConfig(
        record: ConnectionRecord,
        address: SelectableAddress,
        secret: String?,
    ): FtpConfig = FtpConfig(
        host = address.host.trim(),
        port = address.port,
        username = record.username.orEmpty(),
        password = secret.orEmpty(),
        basePath = record.basePath.ifBlank { "/" },
        ftpsMode = ftpsModeOf(record.tls),
        connectTimeoutMs = record.options.connectTimeoutMs ?: FtpConfig.DEFAULT_CONNECT_TIMEOUT_MS,
    )

    /** `connection.tls` 列 → [FtpsMode]（与 [StorageConnectionHandshakes.ftpsModeOf] 同一口径）。 */
    fun ftpsModeOf(tls: String?): FtpsMode = StorageConnectionHandshakes.ftpsModeOf(tls)

    /**
     * 远端根目录的展示串（浏览器顶栏 / 设置页用）：`sftp://主机:端口/根路径`。
     *
     * 刻意不带用户名与凭据（R6：界面上不回显账号；WebDAV 那条同样只显示 scheme://host:port）。
     */
    fun remoteDisplayPath(scheme: String, address: SelectableAddress, basePath: String): String {
        val root = basePath.trim().ifBlank { "/" }
        val normalized = if (root.startsWith("/")) root else "/" + root
        return scheme + "://" + address.host.trim() + ":" + address.port + normalized
    }

    /**
     * 后端重建指纹（**R7**）：同一连接换地址 / 换根路径 / 改了密码有无时才算"变了"，
     * 变了才重建后端（否则每次网络抖动都会把 SSH 会话拆掉重连）。
     *
     * @param extra 协议特有的额外因子（FTP 的 `connection.tls`），null 表示没有。
     */
    fun rebuildSignature(record: ConnectionRecord, address: SelectableAddress, extra: String? = null): String =
        record.id.toString() + "|" + address.id + "|" + record.basePath + "|" +
            record.username.orEmpty() + "|" + record.hasSecret + "|" + (extra ?: "-") + "|" +
            address.scheme + address.host + ":" + address.port
}
