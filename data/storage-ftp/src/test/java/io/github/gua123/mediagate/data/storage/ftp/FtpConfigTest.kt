package io.github.gua123.mediagate.data.storage.ftp

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FtpConfig] 校验、身份归一化与 FTPS 配置路径（**R2** / **R6** / plan 4.9）。
 *
 * FTPS 这里只断言「配置 → 客户端类型/隐式标志/端口/超时」这条路径；
 * 真机上的证书校验与 TLS 握手差异列在交付报告的「需真机验证」清单里。
 */
class FtpConfigTest {

    private fun config(
        host: String = "nas.local",
        port: Int = 21,
        username: String = "demo",
        password: String? = "pwd",
        basePath: String = "/media",
        ftps: FtpsMode = FtpsMode.NONE,
        passive: Boolean = true,
        connectTimeoutMs: Long = 5_000L,
        readTimeoutMs: Long = 15_000L,
        maxConnections: Int = 4,
    ) = FtpConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        basePath = basePath,
        ftpsMode = ftps,
        passive = passive,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        maxConnections = maxConnections,
    )

    @Test
    fun `默认值符合 plan 口径`() {
        val config = FtpConfig(host = "nas.local", username = "demo", password = "pwd")
        assertEquals(21, config.port)
        assertEquals("/", config.rootPath)
        assertEquals(FtpsMode.NONE, config.ftpsMode)
        assertTrue("plan 4.1 要求 PASV 优先", config.passive)
        assertEquals(FtpConfig.DEFAULT_MAX_CONNECTIONS, config.maxConnections)
        assertEquals(5_000L, config.connectTimeoutMs)
        assertEquals(15_000L, config.readTimeoutMs)
    }

    @Test
    fun `host 为空被拒`() {
        assertThrows(IllegalArgumentException::class.java) { config(host = "  ") }
    }

    @Test
    fun `端口越界被拒`() {
        assertThrows(IllegalArgumentException::class.java) { config(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(port = 70000) }
    }

    @Test
    fun `用户名不能为空`() {
        assertThrows(IllegalArgumentException::class.java) { config(username = "") }
    }

    @Test
    fun `超时必须为正数`() {
        assertThrows(IllegalArgumentException::class.java) { config(connectTimeoutMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { config(readTimeoutMs = -1L) }
    }

    @Test
    fun `连接池大小有上限`() {
        assertThrows(IllegalArgumentException::class.java) { config(maxConnections = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(maxConnections = 99) }
        assertEquals(1, config(maxConnections = 1).maxConnections)
    }

    @Test
    fun `basePath 不允许越界`() {
        assertThrows(IllegalArgumentException::class.java) { config(basePath = "../../etc") }
    }

    @Test
    fun `id 对路径写法归一化且不含加密模式`() {
        val a = config(basePath = "/media")
        val b = config(basePath = "media/")
        assertEquals(a.id, b.id)
        assertEquals("/media", a.rootPath)
        assertEquals(a.id, config(basePath = "/media", ftps = FtpsMode.EXPLICIT).id)
        assertNotEquals(a.id, config(basePath = "/other").id)
    }

    @Test
    fun `toString 不泄漏密码`() {
        val text = config(password = "SUPER-SECRET-PASSWORD").toString()
        assertFalse(text, text.contains("SUPER-SECRET-PASSWORD"))
        assertTrue(text.contains("nas.local"))
    }

    @Test
    fun `明文模式造出普通 FTPClient`() {
        val client = newFtpClient(config(ftps = FtpsMode.NONE))
        assertTrue(client is FTPClient)
        assertFalse("明文模式不应是 FTPSClient", client is FTPSClient)
    }

    @Test
    fun `显式 FTPS 造出非隐式 FTPSClient`() {
        val client = newFtpClient(config(ftps = FtpsMode.EXPLICIT))
        assertTrue(client is FTPSClient)
        assertTrue(client is MediateFtpsClient)
        assertFalse("EXPLICIT 必须是非隐式（要发 AUTH TLS）", (client as MediateFtpsClient).implicitMode)
        assertTrue(FtpsMode.EXPLICIT.secure)
    }

    @Test
    fun `隐式 FTPS 造出隐式 FTPSClient`() {
        val client = newFtpClient(config(port = 990, ftps = FtpsMode.IMPLICIT))
        assertTrue(client is FTPSClient)
        assertTrue(client is MediateFtpsClient)
        assertTrue("IMPLICIT 必须是隐式 TLS", (client as MediateFtpsClient).implicitMode)
        assertTrue(FtpsMode.IMPLICIT.secure)
    }

    @Test
    fun `明文模式标记为不加密`() {
        assertFalse(FtpsMode.NONE.secure)
    }
}
