package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.data.storage.ftp.FtpConfig
import io.github.gua123.mediagate.data.storage.ftp.FtpsMode
import io.github.gua123.mediagate.data.storage.sftp.SftpConfig
import io.github.gua123.mediagate.data.storage.sftp.SftpHostKeyPolicy

/**
 * [ConnectionBackends] 的 JVM 单测（**R2** 四协议接进 App 的装配口径）。
 *
 * 这里是"点开 SFTP/FTP 连接能不能真的浏览"的第一道闸：配置字段错一个（端口、根路径、FTPS 模式、
 * 超时），真机上就是连不上或者读错目录，而单测能在这里一秒抓到。
 */
class ConnectionBackendsTest {

    private val lan = SelectableAddress(7L, AddressLabel.LAN, "sftp", "192.168.1.10", 2222)

    private fun record(
        protocol: ProtocolKind = ProtocolKind.SFTP,
        basePath: String = "/media",
        username: String? = "demo",
        tls: String? = null,
        connectTimeoutMs: Long? = null,
        hasSecret: Boolean = true,
    ) = ConnectionRecord(
        id = 3L,
        name = protocol.zhText,
        protocolId = protocol.id,
        protocol = protocol,
        basePath = basePath,
        username = username,
        hasSecret = hasSecret,
        options = ConnectionOptions(connectTimeoutMs = connectTimeoutMs),
        tls = tls,
        lastWorkingAddressId = null,
        lastCheckedAt = null,
        addresses = listOf(AddressRecord(7L, 3L, AddressLabel.LAN, "sftp", "192.168.1.10", 2222, 0)),
        rules = emptyList(),
    )

    // ------------------------------------------------------------------ SFTP

    @Test
    fun `SFTP 配置照搬地址端口用户名与根路径`() {
        val config = ConnectionBackends.sftpConfig(record(), lan, secret = "pwd")
        assertEquals("192.168.1.10", config.host)
        assertEquals(2222, config.port)
        assertEquals("demo", config.username)
        assertEquals("pwd", config.password)
        assertEquals(SftpConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.connectTimeoutMs)
        assertEquals(SftpHostKeyPolicy.TOFU, config.hostKeyPolicy)
        // rootPath 是 internal（跨模块看不到），用 id 断言根路径口径：sftp://用户名@主机:端口/根路径
        assertEquals("sftp://demo@192.168.1.10:2222/media", config.id)
    }

    @Test
    fun `SFTP 空根路径按斜杠处理`() {
        assertEquals("sftp://demo@192.168.1.10:2222/", ConnectionBackends.sftpConfig(record(basePath = ""), lan, "pwd").id)
    }

    @Test
    fun `SFTP 连接可以覆盖连接超时`() {
        val config = ConnectionBackends.sftpConfig(record(connectTimeoutMs = 8_000L), lan, "pwd")
        assertEquals(8_000L, config.connectTimeoutMs)
    }

    @Test
    fun `SFTP 没密码也没私钥时明确拒绝而不是造一个连不上的后端`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConnectionBackends.sftpConfig(record(hasSecret = false), lan, secret = null)
        }
        assertTrue("错误信息要能直接给用户看", error.message!!.contains("密码"))
    }

    // ------------------------------------------------------------------ FTP

    @Test
    fun `FTP 的 tls 列决定加密模式认不出的一律明文`() {
        assertEquals(FtpsMode.EXPLICIT, ConnectionBackends.ftpsModeOf("explicit"))
        assertEquals(FtpsMode.EXPLICIT, ConnectionBackends.ftpsModeOf("TLS"))
        assertEquals(FtpsMode.IMPLICIT, ConnectionBackends.ftpsModeOf("implicit-tls"))
        assertEquals(FtpsMode.NONE, ConnectionBackends.ftpsModeOf(null))
        assertEquals(FtpsMode.NONE, ConnectionBackends.ftpsModeOf("随便写的"))

        val explicit = ConnectionBackends.ftpConfig(record(ProtocolKind.FTP, basePath = "/pub", tls = "explicit"), lan, "pwd")
        assertEquals(FtpsMode.EXPLICIT, explicit.ftpsMode)
        assertEquals("ftp://demo@192.168.1.10:2222/pub", explicit.id)

        val plain = ConnectionBackends.ftpConfig(record(ProtocolKind.FTP, tls = null), lan, "pwd")
        assertEquals(FtpsMode.NONE, plain.ftpsMode)
    }

    @Test
    fun `FTP 密码可以留空但用户名必须有`() {
        // 空密码：少数服务器接受（匿名一般也是"用户名 anonymous + 任意口令"）
        val config = ConnectionBackends.ftpConfig(record(ProtocolKind.FTP), lan, secret = null)
        assertEquals("demo", config.username)
        assertEquals("", config.password)
        assertEquals(FtpConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.connectTimeoutMs)

        // 用户名留空：明确拒绝（:app 会先拦下来给一句中文提示），不悄悄猜成匿名登录
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConnectionBackends.ftpConfig(record(ProtocolKind.FTP, username = "  "), lan, "pw")
        }
        assertTrue("要有可读的失败原因", !error.message.isNullOrBlank())
    }

    // ------------------------------------------------------------------ 展示串与重建指纹

    @Test
    fun `远端展示串只给主机端口与根路径不回显用户名`() {
        assertEquals("sftp://192.168.1.10:2222/media", ConnectionBackends.remoteDisplayPath("sftp", lan, "/media"))
        assertEquals("ftp://192.168.1.10:2222/", ConnectionBackends.remoteDisplayPath("ftp", lan, ""))
        // 根路径没写前导斜杠也归一化
        assertEquals("sftp://192.168.1.10:2222/pub", ConnectionBackends.remoteDisplayPath("sftp", lan, "pub"))
    }

    @Test
    fun `重建指纹随地址与根路径变化但不随连接名变化`() {
        val base = ConnectionBackends.rebuildSignature(record(), lan)
        assertEquals(base, ConnectionBackends.rebuildSignature(record(), lan))
        assertNotEquals(base, ConnectionBackends.rebuildSignature(record(basePath = "/other"), lan))
        assertNotEquals(base, ConnectionBackends.rebuildSignature(record(username = "other"), lan))
        assertNotEquals(base, ConnectionBackends.rebuildSignature(record(hasSecret = false), lan))
        assertNotEquals(base, ConnectionBackends.rebuildSignature(record(), lan.copy(port = 2233)))
        // extra（FTP 的 tls）参与指纹：改加密方式必须重建后端
        assertNotEquals(
            ConnectionBackends.rebuildSignature(record(ProtocolKind.FTP), lan),
            ConnectionBackends.rebuildSignature(record(ProtocolKind.FTP), lan, extra = "explicit"),
        )
    }
}
