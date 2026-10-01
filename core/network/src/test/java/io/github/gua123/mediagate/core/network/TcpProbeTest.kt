package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TcpProbe] 的 JVM 单测（**R8**：三段计时里的 DNS 与 TCP 两段，以及错误分类）。
 *
 * 全程用注入的时钟 / 解析器 / 连接器，不依赖真实网络。
 */
class TcpProbeTest {

    /** 时钟按连接器"消耗"的时间推进：让三段耗时可控且可断言。 */
    private class TickingClock : ProbeClock {
        var now = 0L
        override fun nowMs(): Long = now
    }

    @Test
    fun `连通时 DNS 与 TCP 分别计时`() = runBlocking {
        val clock = TickingClock()
        val resolver = HostResolver { host ->
            clock.now += 2L
            listOf(java.net.InetAddress.getByName("127.0.0.1"))
        }
        val connector = SocketConnector { _, _ -> clock.now += 15L }
        val probe = TcpProbe(clock, resolver, connector, io = kotlinx.coroutines.Dispatchers.Default)
        val result = probe.connect("example.test", 8080)
        assertTrue(result.ok)
        assertEquals(2L, result.dnsMs)
        assertEquals(15L, result.connectMs)
        assertEquals("example.test", result.host)
        assertEquals(8080, result.port)
        assertNotNull(result.resolved)
    }

    @Test
    fun `DNS 失败归为 DNS_FAILED 且只花 DNS 段`() = runBlocking {
        val clock = TickingClock()
        val probe = TcpProbe(
            clock = clock,
            resolver = FakeResolver(failures = setOf("bad.host")),
            connector = SocketConnector { _, _ -> error("不该发起连接") },
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        val result = probe.connect("bad.host", 80)
        assertFalse(result.ok)
        assertEquals(ConnectivityError.DNS_FAILED, result.error)
        assertEquals(TestStage.DNS, result.failedStage)
        assertEquals(0L, result.connectMs)
    }

    @Test
    fun `连接被拒归为 CONNECTION_REFUSED`() = runBlocking {
        val probe = TcpProbe(
            clock = FakeClock(),
            resolver = FakeResolver(),
            connector = FakeConnector(mapOf("127.0.0.1" to FakeConnector.Behaviour.refused())),
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        val result = probe.connect("127.0.0.1", 1)
        assertFalse(result.ok)
        assertEquals(ConnectivityError.CONNECTION_REFUSED, result.error)
        assertEquals(TestStage.TCP, result.failedStage)
        assertTrue(result.detail!!.contains("refused", ignoreCase = true))
    }

    @Test
    fun `超时归为 TIMEOUT`() = runBlocking {
        val probe = TcpProbe(
            clock = FakeClock(),
            resolver = FakeResolver(),
            connector = FakeConnector(mapOf("127.0.0.1" to FakeConnector.Behaviour.timeout())),
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        val result = probe.connect("127.0.0.1", 8080)
        assertEquals(ConnectivityError.TIMEOUT, result.error)
    }

    @Test
    fun `不可达归为 NETWORK_UNREACHABLE`() = runBlocking {
        val probe = TcpProbe(
            clock = FakeClock(),
            resolver = FakeResolver(),
            connector = FakeConnector(mapOf("10.0.0.9" to FakeConnector.Behaviour.unreachable())),
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        assertEquals(ConnectivityError.NETWORK_UNREACHABLE, probe.connect("10.0.0.9", 22).error)
    }

    @Test
    fun `多个解析结果依次尝试，后者成功即视为连通`() = runBlocking {
        val attempts = mutableListOf<String>()
        val probe = TcpProbe(
            clock = FakeClock(),
            resolver = FakeResolver(mapOf("multi.host" to listOf("127.0.0.2", "127.0.0.3"))),
            connector = SocketConnector { address, _ ->
                attempts += address.address.hostAddress
                if (address.address.hostAddress == "127.0.0.2") throw java.net.ConnectException("Connection refused")
            },
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        val result = probe.connect("multi.host", 8080)
        assertTrue(result.ok)
        assertEquals(listOf("127.0.0.2", "127.0.0.3"), attempts)
        assertEquals(2, result.resolved.size)
    }

    @Test
    fun `多个解析结果全失败时保留最后一次分类`() = runBlocking {
        val probe = TcpProbe(
            clock = FakeClock(),
            resolver = FakeResolver(mapOf("multi.host" to listOf("127.0.0.2", "127.0.0.3"))),
            connector = FakeConnector(
                mapOf(
                    "127.0.0.2" to FakeConnector.Behaviour.refused(),
                    "127.0.0.3" to FakeConnector.Behaviour.timeout(),
                ),
            ),
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        assertEquals(ConnectivityError.TIMEOUT, probe.connect("multi.host", 8080).error)
    }

    @Test
    fun `端口不合法与空地址直接判错，不发起连接`() = runBlocking {
        val connector = FakeConnector()
        val probe = TcpProbe(FakeClock(), FakeResolver(), connector, io = kotlinx.coroutines.Dispatchers.Default)
        val badPort = probe.connect("127.0.0.1", 70000)
        assertEquals(ConnectivityError.PORT_INVALID, badPort.error)
        assertEquals(TestStage.DNS, badPort.failedStage)
        val emptyHost = probe.connect("   ", 80)
        assertEquals(ConnectivityError.DNS_FAILED, emptyHost.error)
        assertEquals(0, connector.calls.get())
    }

    @Test
    fun `解析结果为空也算 DNS 失败`() = runBlocking {
        val probe = TcpProbe(
            FakeClock(),
            HostResolver { emptyList() },
            FakeConnector(),
            io = kotlinx.coroutines.Dispatchers.Default,
        )
        val result = probe.connect("empty.host", 80)
        assertEquals(ConnectivityError.DNS_FAILED, result.error)
        assertTrue(result.detail!!.contains("没有解析出"))
    }
}
