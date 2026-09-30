package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.HandshakeOutcome
import io.github.gua123.mediagate.core.network.ProtocolHandshake
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.data.storage.webdav.WebDavConfig
import io.github.gua123.mediagate.data.storage.webdav.WebDavStorageBackend
import java.io.File

/**
 * 各协议的"第三段"握手实现（**R8**，plan 4.5：协议握手才是真正的决定权）。
 *
 * 本轮（M4）只提供两种：
 * - [ProtocolKind.LOCAL]：检查本地目录是否存在、是不是目录、能不能读；
 * - [ProtocolKind.WEBDAV]：构造一个**临时**的 [WebDavStorageBackend] 发一次 PROPFIND，期望 207/200；
 *   401 = 账号密码错、403 = 无权限、404 = 根路径不对（后端的 [io.github.gua123.mediagate.core.model.ProbeReport]
 *   已经给出中文结论，这里只把它翻译成稳定的错误枚举）。
 *
 * [ProtocolKind.SFTP] / [ProtocolKind.FTP] **故意不提供**：M5 才有对应后端，本轮只能测到
 * DNS/TCP 两段；[io.github.gua123.mediagate.core.network.ConnectionTester] 会在这两个协议上跳过握手并
 * 在结果里带"未做协议握手"的中文提示（界面如实展示，不谎报"正常"）。
 *
 * 临时后端用完即 [WebDavStorageBackend.close]，不会把连接池留在后台（测试全部时每个地址一个）。
 */
internal object ConnectionHandshakes {

    /**
     * 为一个连接装配可用的握手表。
     *
     * @param record 连接记录（含地址、根路径、用户名、选项）。
     * @param secret 解密后的明文密码；null = 匿名或解密失败。
     */
    fun forConnection(record: ConnectionRecord, secret: String?): Map<ProtocolKind, ProtocolHandshake> =
        when (record.protocol) {
            ProtocolKind.LOCAL -> mapOf(ProtocolKind.LOCAL to local())
            ProtocolKind.WEBDAV -> mapOf(ProtocolKind.WEBDAV to webDav(record, secret))
            else -> emptyMap()
        }

    /** 本地目录：三段里的第三段就是"这个目录能不能用"。 */
    fun local(): ProtocolHandshake = ProtocolHandshake { address ->
        val started = System.nanoTime()
        val path = address.host.trim()
        val file = if (path.isEmpty()) null else runCatching { File(path) }.getOrNull()
        val elapsed = elapsedMs(started)
        when {
            file == null || path.isEmpty() -> HandshakeOutcome(
                ok = false,
                elapsedMs = elapsed,
                error = ConnectivityError.PATH_NOT_FOUND,
                message = "本地目录为空，请填写绝对路径",
            )
            !file.exists() -> HandshakeOutcome(
                ok = false,
                elapsedMs = elapsed,
                error = ConnectivityError.PATH_NOT_FOUND,
                message = "本地目录不存在：" + path,
            )
            !file.isDirectory -> HandshakeOutcome(
                ok = false,
                elapsedMs = elapsed,
                error = ConnectivityError.PROTOCOL_UNSUPPORTED,
                message = "不是目录：" + path,
            )
            !file.canRead() -> HandshakeOutcome(
                ok = false,
                elapsedMs = elapsed,
                error = ConnectivityError.PERMISSION_DENIED,
                message = "没有读取权限：" + path,
            )
            else -> HandshakeOutcome(ok = true, elapsedMs = elapsed, message = "目录可读：" + path)
        }
    }

    /** WebDAV：PROPFIND（Depth: 0）期望 207 / 200。 */
    fun webDav(record: ConnectionRecord, secret: String?): ProtocolHandshake = ProtocolHandshake { address ->
        val started = System.nanoTime()
        val baseUrl = buildBaseUrl(address.scheme, address.host, address.port)
        val config = try {
            WebDavConfig(
                baseUrl = baseUrl,
                username = record.username,
                password = secret,
                rootPath = record.basePath.ifBlank { "/" },
                allowInsecureHttp = record.options.allowInsecureHttp,
                connectTimeoutMs = record.options.connectTimeoutMs ?: WebDavConfig.DEFAULT_CONNECT_TIMEOUT_MS,
            )
        } catch (e: IllegalArgumentException) {
            return@ProtocolHandshake HandshakeOutcome(
                ok = false,
                elapsedMs = elapsedMs(started),
                error = ConnectivityError.PROTOCOL_UNSUPPORTED,
                message = "配置不合法：" + (e.message ?: "地址或选项有误"),
            )
        }
        val backend = WebDavStorageBackend(config)
        try {
            val report = backend.probe()
            HandshakeOutcome(
                // 后端的 probe 自带三段计时；这里只取"握手"这一段，DNS/TCP 由 ConnectionTester 单独测
                ok = report.ok,
                elapsedMs = if (report.handshakeMs > 0L) report.handshakeMs else elapsedMs(started),
                error = report.message?.let { ConnectivityError.fromMessage(it) }
                    ?: if (report.ok) null else ConnectivityError.UNKNOWN,
                message = report.message ?: "PROPFIND 成功（服务器支持 WebDAV）",
            )
        } finally {
            runCatching { backend.close() }
        }
    }

    /** 拼接 WebDAV 基址：默认端口（http 80 / https 443）不写出来（与 :app 共用 [ConnectionEndpoints]）。 */
    fun buildBaseUrl(scheme: String, host: String, port: Int): String =
        ConnectionEndpoints.baseUrl(scheme, host, port)

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
}
