package io.github.gua123.mediagate.feature.connections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.AddressTestResult
import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.core.network.TestStage
import java.io.File

/**
 * 协议握手与结果映射的 JVM 单测（**R8** 第三段：协议握手）。
 *
 * WebDAV 的握手要真发 HTTP，这里只测**纯逻辑**部分：
 * - 基址拼接（默认端口不写出来）；
 * - 本地目录握手（用临时目录，真实文件系统，但零网络）；
 * - 核心结果 → 界面模型的映射（三段耗时、错误分类、跳过握手的提示）。
 */
class ConnectionHandshakesTest {

    @Test
    fun `基址拼接省略默认端口`() {
        assertEquals("http://192.168.1.10:8080", ConnectionHandshakes.buildBaseUrl("http", "192.168.1.10", 8080))
        assertEquals("http://example.com", ConnectionHandshakes.buildBaseUrl("http", "example.com", 80))
        assertEquals("https://example.com", ConnectionHandshakes.buildBaseUrl("HTTPS", " example.com ", 443))
        assertEquals("http://example.com", ConnectionHandshakes.buildBaseUrl("http", "example.com", 0))
    }

    @Test
    fun `本地目录握手：存在的目录通过`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "mg-conn-test-" + System.nanoTime())
        assertTrue(dir.mkdirs())
        try {
            val outcome = ConnectionHandshakes.local().handshake(
                SelectableAddress(1L, AddressLabel.LAN, "file", dir.absolutePath, 0),
            )
            assertTrue(outcome.ok)
            assertTrue(outcome.message!!.contains("可读"))
            assertTrue(outcome.elapsedMs >= 0L)
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `本地目录握手：不存在与不是目录分别归类`() = runBlocking {
        val missing = ConnectionHandshakes.local().handshake(
            SelectableAddress(1L, AddressLabel.LAN, "file", "/definitely/not/here", 0),
        )
        assertFalse(missing.ok)
        assertEquals(ConnectivityError.PATH_NOT_FOUND, missing.error)

        val file = File.createTempFile("mg-conn-file", ".txt")
        try {
            val notDir = ConnectionHandshakes.local().handshake(
                SelectableAddress(2L, AddressLabel.LAN, "file", file.absolutePath, 0),
            )
            assertFalse(notDir.ok)
            assertEquals(ConnectivityError.PROTOCOL_UNSUPPORTED, notDir.error)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `本地协议装配出握手，SFTP 与 FTP 故意没有`() {
        val local = record(ProtocolKind.LOCAL)
        assertEquals(setOf(ProtocolKind.LOCAL), ConnectionHandshakes.forConnection(local, null).keys)
        assertEquals(setOf(ProtocolKind.WEBDAV), ConnectionHandshakes.forConnection(record(ProtocolKind.WEBDAV), "pw").keys)
        assertTrue(ConnectionHandshakes.forConnection(record(ProtocolKind.SFTP), null).isEmpty())
        assertTrue(ConnectionHandshakes.forConnection(record(ProtocolKind.FTP), null).isEmpty())
    }

    @Test
    fun `核心结果映射到界面模型`() {
        val ok = AddressTestResult(
            address = SelectableAddress(7L, AddressLabel.WAN, "http", "dav.example.com", 8443),
            ok = true,
            dnsMs = 12L,
            connectMs = 34L,
            handshakeMs = 56L,
        ).toUi()
        assertEquals(7L, ok.addressId)
        assertTrue(ok.display.startsWith("公网"))
        assertEquals("正常", ok.statusText)
        assertEquals("DNS 12 ms · TCP 34 ms · 握手 56 ms", ok.timingLine)
        assertEquals(102L, ok.totalMs)

        val failed = AddressTestResult(
            address = SelectableAddress(8L, AddressLabel.LAN, "http", "192.168.1.10", 8080),
            ok = false,
            dnsMs = 1L,
            connectMs = 2L,
            handshakeMs = 0L,
            error = ConnectivityError.AUTH_FAILED,
            failedStage = TestStage.HANDSHAKE,
            message = "401 账号或密码错误",
        ).toUi()
        assertEquals("AUTH_FAILED", failed.errorCode)
        assertTrue(failed.errorText!!.contains("认证失败"))
        assertEquals(TestStage.HANDSHAKE, failed.failedStage)
        assertEquals("认证失败（账号或密码不正确）", failed.statusText)
    }

    @Test
    fun `跳过握手的映射保留中文提示`() {
        val ui = AddressTestResult(
            address = SelectableAddress(9L, AddressLabel.WAN, "sftp", "dav.example.com", 2222),
            ok = true,
            dnsMs = 3L,
            connectMs = 5L,
            notice = "SFTP 暂无协议握手实现：本次只验证到 DNS/TCP 两段，未做协议握手",
            handshakeSkipped = true,
        ).toUi()
        assertTrue(ui.handshakeSkipped)
        assertEquals("TCP 可达（未做协议握手）", ui.statusText)
        assertTrue(ui.notice!!.contains("暂无协议握手实现"))
    }

    private fun record(protocol: ProtocolKind) = ConnectionRecord(
        id = 1L,
        name = protocol.id,
        protocolId = protocol.id,
        protocol = protocol,
        basePath = "/",
        username = "u",
        hasSecret = false,
        options = ConnectionOptions.Default,
        tls = null,
        lastWorkingAddressId = null,
        lastCheckedAt = null,
        addresses = emptyList(),
        rules = emptyList(),
    )
}
