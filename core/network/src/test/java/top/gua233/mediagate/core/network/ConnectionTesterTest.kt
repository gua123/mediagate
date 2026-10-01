package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectionTester] 的 JVM 单测（**R8**：plan 4.5 三段计时与错误分类）。
 *
 * 重点验证三件事：
 * 1. 本地协议跳过 DNS/TCP（前两段恒 0），只有"路径检查"这一段；
 * 2. SFTP / FTP 本轮没有后端 → 只测到 TCP，结果里带 notice 与 handshakeSkipped（**界面要如实提示**）；
 * 3. 前两段失败就不做握手，失败阶段与分类准确。
 */
class ConnectionTesterTest {

    private val host = "192.168.1.10"
    private val address = address(id = 1, label = AddressLabel.LAN, host = host)

    private fun tester(
        connector: FakeConnector,
        protocol: ProtocolKind,
        handshake: ProtocolHandshake?,
        clock: FakeClock = FakeClock(),
    ): ConnectionTester {
        val tcp = TcpProbe(clock, FakeResolver(), connector, io = Dispatchers.Default)
        val map = if (handshake == null) emptyMap() else mapOf(protocol to handshake)
        return ConnectionTester(tcp = tcp, handshakes = map, io = Dispatchers.Default)
    }

    @Test
    fun `WebDAV 三段全通过`() = runBlocking {
        val okConnector = FakeConnector(mapOf(host to FakeConnector.Behaviour.ok()))
        val handshake = FakeHandshake(HandshakeOutcome(ok = true, elapsedMs = 42L))
        val result = tester(okConnector, ProtocolKind.WEBDAV, handshake).test(ProtocolKind.WEBDAV, address)
        assertTrue(result.ok)
        assertNull(result.error)
        assertNull(result.failedStage)
        assertEquals(42L, result.handshakeMs)
        assertEquals(1, handshake.calls.get())
        assertTrue(result.timingLine.contains("42 ms"))
    }

    @Test
    fun `握手 401 归为 AUTH_FAILED 且保留前两段耗时`() = runBlocking {
        val okConnector = FakeConnector(mapOf(host to FakeConnector.Behaviour.ok()))
        val handshake = FakeHandshake(
            HandshakeOutcome(ok = false, elapsedMs = 11L, error = ConnectivityError.AUTH_FAILED, message = "401 账号或密码错误"),
        )
        val result = tester(okConnector, ProtocolKind.WEBDAV, handshake).test(ProtocolKind.WEBDAV, address)
        assertFalse(result.ok)
        assertEquals(ConnectivityError.AUTH_FAILED, result.error)
        assertEquals(TestStage.HANDSHAKE, result.failedStage)
        assertEquals(11L, result.handshakeMs)
        assertTrue(result.message!!.contains("401"))
    }

    @Test
    fun `TCP 不通时不做握手`() = runBlocking {
        val badConnector = FakeConnector(mapOf(host to FakeConnector.Behaviour.refused()))
        val handshake = FakeHandshake()
        val result = tester(badConnector, ProtocolKind.WEBDAV, handshake).test(ProtocolKind.WEBDAV, address)
        assertFalse(result.ok)
        assertEquals(ConnectivityError.CONNECTION_REFUSED, result.error)
        assertEquals(TestStage.TCP, result.failedStage)
        assertEquals("TCP 失败不应再做协议握手", 0, handshake.calls.get())
        assertEquals(0L, result.handshakeMs)
    }

    @Test
    fun `本地协议跳过 DNS 与 TCP`() = runBlocking {
        val connector = FakeConnector()
        val handshake = FakeHandshake(HandshakeOutcome(ok = true, elapsedMs = 1L))
        val result = tester(connector, ProtocolKind.LOCAL, handshake).test(ProtocolKind.LOCAL, address)
        assertTrue(result.ok)
        assertEquals(0L, result.dnsMs)
        assertEquals(0L, result.connectMs)
        assertEquals("本地协议不该发起 TCP 连接", 0, connector.calls.get())
        assertEquals(1, handshake.calls.get())
    }

    @Test
    fun `本地协议没有握手实现时明确判为未接入`() = runBlocking {
        val result = tester(FakeConnector(), ProtocolKind.LOCAL, null).test(ProtocolKind.LOCAL, address)
        assertFalse(result.ok)
        assertEquals(ConnectivityError.NOT_IMPLEMENTED, result.error)
        assertTrue(result.handshakeSkipped)
        assertNotNull(result.notice)
    }

    @Test
    fun `没有握手实现时 TCP 通也算通但带如实提示`() = runBlocking {
        val okConnector = FakeConnector(mapOf(host to FakeConnector.Behaviour.ok()))
        val tester = tester(okConnector, ProtocolKind.SFTP, null)
        assertFalse(tester.supportsHandshake(ProtocolKind.SFTP))
        val result = tester.test(ProtocolKind.SFTP, address)
        assertTrue("TCP 通即视为本轮可用", result.ok)
        assertTrue(result.handshakeSkipped)
        assertEquals(0L, result.handshakeMs)
        assertTrue("必须如实说明只测了 TCP", result.notice!!.contains("暂无协议握手实现"))
        assertTrue(result.timingLine.contains("握手未测"))
    }

    @Test
    fun `SFTP 的 TCP 不通时给出网络层分类`() = runBlocking {
        val connector = FakeConnector(mapOf(host to FakeConnector.Behaviour.timeout()))
        val result = tester(connector, ProtocolKind.SFTP, null).test(ProtocolKind.SFTP, address)
        assertFalse(result.ok)
        assertEquals(ConnectivityError.TIMEOUT, result.error)
        assertEquals(TestStage.TCP, result.failedStage)
    }

    @Test
    fun `握手抛异常也算失败而不是崩`() = runBlocking {
        val okConnector = FakeConnector(mapOf(host to FakeConnector.Behaviour.ok()))
        val throwing = ProtocolHandshake { throw java.io.IOException("boom") }
        val result = tester(okConnector, ProtocolKind.WEBDAV, throwing).test(ProtocolKind.WEBDAV, address)
        assertFalse(result.ok)
        assertEquals(TestStage.HANDSHAKE, result.failedStage)
        assertTrue(result.message!!.contains("IOException"))
    }

    @Test
    fun `testAll 并行测试并保持输入顺序`() = runBlocking {
        val okConnector = FakeConnector()
        val handshake = FakeHandshake()
        val tester = tester(okConnector, ProtocolKind.WEBDAV, handshake)
        val list = listOf(
            address(id = 1, host = "127.0.0.1"),
            address(id = 2, host = "127.0.0.2", label = AddressLabel.WAN, port = 8443),
            address(id = 3, host = "127.0.0.3", label = AddressLabel.WAN, port = 443),
        )
        val results = tester.testAll(ProtocolKind.WEBDAV, list)
        assertEquals(list.map { it.id }, results.map { it.address.id })
        assertTrue(results.all { it.ok })
        assertEquals(3, handshake.calls.get())
        assertEquals(3, okConnector.calls.get())
    }

    @Test
    fun `testAll 空列表返回空`() = runBlocking {
        assertTrue(tester(FakeConnector(), ProtocolKind.WEBDAV, FakeHandshake()).testAll(ProtocolKind.WEBDAV, emptyList()).isEmpty())
    }

    @Test
    fun `协议枚举与数据库字符串一致`() {
        assertEquals("LOCAL", ProtocolKind.LOCAL.id)
        assertEquals("WEBDAV", ProtocolKind.WEBDAV.id)
        assertEquals("SFTP", ProtocolKind.SFTP.id)
        assertEquals("FTP", ProtocolKind.FTP.id)
        assertEquals(ProtocolKind.WEBDAV, ProtocolKind.fromId("webdav"))
        assertNull(ProtocolKind.fromId("SMB"))
    }
}
