package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.HandshakeOutcome
import io.github.gua123.mediagate.core.network.ProtocolHandshake
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.data.storage.ftp.FtpConfig
import io.github.gua123.mediagate.data.storage.ftp.FtpStorageBackend
import io.github.gua123.mediagate.data.storage.ftp.FtpsMode
import io.github.gua123.mediagate.data.storage.sftp.SftpConfig
import io.github.gua123.mediagate.data.storage.sftp.SftpHostKeyPolicy
import io.github.gua123.mediagate.data.storage.sftp.SftpStorageBackend

/**
 * **M5 纯加法**：在 M4 的 [ConnectionHandshakes] 之上补上 SFTP / FTP 的第三段协议握手（**R8**，plan 4.5）。
 *
 * 为什么单独一个对象而不是直接改 [ConnectionHandshakes]：
 * M4 的 `forConnection` 是「本轮只实现 LOCAL 与 WEBDAV」这一**既有行为语义**的落点，
 * `:feature:connections` 的既有用例把它钉住了。M5 要的是「测试连通性从 TCP 可连升级为真握手」，
 * 所以这里做**纯加法**：新增装配函数、新增用例，既有函数与既有用例一行不动；
 * 调用方（[ConnectionsViewModel]）改用本函数即可拿到真握手。
 *
 * 握手指令与 plan 4.5 一致：
 * - SFTP：SSH banner + 认证 + 列目录（主机密钥变更会在这里被判为「证书不受信任」，plan 4.9）；
 * - FTP：220 banner + 登录 + PASV。
 *
 * 两个后端都自带三段计时，所以 [HandshakeOutcome.elapsedMs] 取其中的「握手」段，
 * DNS/TCP 两段由 [io.github.gua123.mediagate.core.network.ConnectionTester] 单独测。
 * 临时后端用完即 close，不把 SSH 会话 / FTP 控制连接留在后台。
 */
internal object StorageConnectionHandshakes {

    /**
     * 为一个连接装配可用的握手表。
     *
     * @param record 连接记录（含地址、根路径、用户名、选项）。
     * @param secret 解密后的明文密码；null = 匿名或解密失败。
     */
    fun forConnection(record: ConnectionRecord, secret: String?): Map<ProtocolKind, ProtocolHandshake> =
        when (record.protocol) {
            ProtocolKind.SFTP -> mapOf(ProtocolKind.SFTP to sftp(record, secret))
            ProtocolKind.FTP -> mapOf(ProtocolKind.FTP to ftp(record, secret))
            // LOCAL / WEBDAV 以及未知协议仍走 M4 的实现，语义完全不变
            else -> ConnectionHandshakes.forConnection(record, secret)
        }

    /** SFTP：banner + 认证 + 列目录（plan 4.5 的第三段）。 */
    fun sftp(record: ConnectionRecord, secret: String?): ProtocolHandshake = ProtocolHandshake { address ->
        val started = System.nanoTime()
        val config = try {
            SftpConfig(
                host = address.host.trim(),
                port = address.port,
                username = record.username.orEmpty(),
                password = secret,
                basePath = record.basePath.ifBlank { "/" },
                connectTimeoutMs = record.options.connectTimeoutMs ?: SftpConfig.DEFAULT_CONNECT_TIMEOUT_MS,
                // 自测只做一次探测，用内存 TOFU：不落库、不改用户的已知主机记录（plan 4.9 的落库由上层做）
                hostKeyPolicy = SftpHostKeyPolicy.TOFU,
            )
        } catch (e: IllegalArgumentException) {
            return@ProtocolHandshake invalidConfig(started, e)
        }
        val backend = SftpStorageBackend(config)
        try {
            val report = backend.probe()
            HandshakeOutcome(
                ok = report.ok,
                elapsedMs = if (report.handshakeMs > 0L) report.handshakeMs else elapsedMs(started),
                error = report.message?.let { ConnectivityError.fromMessage(it) }
                    ?: if (report.ok) null else ConnectivityError.UNKNOWN,
                message = report.message ?: "SFTP 握手成功（banner + 认证 + 列目录）",
            )
        } finally {
            runCatching { backend.close() }
        }
    }

    /** FTP：220 banner + 登录 + PASV（plan 4.5 的第三段）。 */
    fun ftp(record: ConnectionRecord, secret: String?): ProtocolHandshake = ProtocolHandshake { address ->
        val started = System.nanoTime()
        val config = try {
            FtpConfig(
                host = address.host.trim(),
                port = address.port,
                username = record.username.orEmpty(),
                password = secret.orEmpty(),
                basePath = record.basePath.ifBlank { "/" },
                ftpsMode = ftpsModeOf(record.tls),
                connectTimeoutMs = record.options.connectTimeoutMs ?: FtpConfig.DEFAULT_CONNECT_TIMEOUT_MS,
            )
        } catch (e: IllegalArgumentException) {
            return@ProtocolHandshake invalidConfig(started, e)
        }
        val backend = FtpStorageBackend(config)
        try {
            val report = backend.probe()
            HandshakeOutcome(
                ok = report.ok,
                elapsedMs = if (report.handshakeMs > 0L) report.handshakeMs else elapsedMs(started),
                error = report.message?.let { ConnectivityError.fromMessage(it) }
                    ?: if (report.ok) null else ConnectivityError.UNKNOWN,
                message = report.message ?: "FTP 握手成功（220 + 登录 + PASV）",
            )
        } finally {
            runCatching { backend.close() }
        }
    }

    /**
     * `connection.tls` 列 → [FtpsMode]（表结构里已有这一列，M5 只是把它用起来）。
     *
     * 认不出的一律 [FtpsMode.NONE]（明文）——不猜，避免把明文连接悄悄升成 TLS 而失败。
     */
    fun ftpsModeOf(tls: String?): FtpsMode = when (tls?.trim()?.lowercase()) {
        "implicit", "implicit-tls", "ftps-implicit" -> FtpsMode.IMPLICIT
        "explicit", "explicit-tls", "ftps", "ftps-explicit", "tls" -> FtpsMode.EXPLICIT
        else -> FtpsMode.NONE
    }

    private fun invalidConfig(started: Long, e: IllegalArgumentException) = HandshakeOutcome(
        ok = false,
        elapsedMs = elapsedMs(started),
        error = ConnectivityError.PROTOCOL_UNSUPPORTED,
        message = "配置不合法：" + (e.message ?: "地址或选项有误"),
    )

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
}
