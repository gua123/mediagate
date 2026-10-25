package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AddressFallbackProbe] 的 JVM 单测（**R7**：先内网后外网，首选不通自动换下一个）。
 *
 * 用假的 [HostResolver] / [SocketConnector] 控制每个地址"通 / 不通"，不需要真网络。
 */
class AddressFallbackProbeTest {

    private val lan = address(id = 1, label = AddressLabel.LAN, host = "192.168.1.10")
    private val wan = address(id = 2, label = AddressLabel.WAN, host = "203.0.113.10", port = 8443)

    private fun probeWith(vararg behaviours: Pair<String, FakeConnector.Behaviour>): Pair<AddressFallbackProbe, FakeConnector> {
        val connector = FakeConnector(behaviours.toMap())
        val tcp = TcpProbe(FakeClock(), FakeResolver(), connector, io = Dispatchers.Default)
        return AddressFallbackProbe(tcp) to connector
    }

    @Test
    fun `局域网通就用局域网，不探测公网`() = runBlocking {
        val (probe, connector) = probeWith("192.168.1.10" to FakeConnector.Behaviour.ok())
        val choice = probe.choose(listOf(lan, wan))
        assertEquals(lan, choice.address)
        assertFalse(choice.usedFallback)
        assertEquals(listOf(lan), choice.attempts.map { it.address })
        assertEquals("只探测了首个地址", 1, connector.calls.get())
    }

    @Test
    fun `局域网不通自动改用公网`() = runBlocking {
        val (probe, connector) = probeWith(
            "192.168.1.10" to FakeConnector.Behaviour.unreachable(),
            "203.0.113.10" to FakeConnector.Behaviour.ok(),
        )
        val choice = probe.choose(listOf(lan, wan))
        assertEquals(wan, choice.address)
        assertTrue(choice.usedFallback)
        assertEquals(listOf(lan, wan), choice.attempts.map { it.address })
        assertTrue(choice.attempts.first().ok.not())
        assertEquals(ConnectivityError.fromThrowable(java.net.ConnectException("Network is unreachable")), choice.attempts.first().error)
        assertEquals(2, connector.calls.get())
    }

    @Test
    fun `全都连不上时仍选首选，让浏览页给出真实错误`() = runBlocking {
        val (probe, _) = probeWith(
            "192.168.1.10" to FakeConnector.Behaviour.timeout(),
            "203.0.113.10" to FakeConnector.Behaviour.refused(),
        )
        val choice = probe.choose(listOf(lan, wan))
        assertEquals(lan, choice.address)
        assertFalse(choice.usedFallback)
        assertEquals(2, choice.attempts.size)
        assertFalse(choice.attempts.any { it.ok })
    }

    @Test
    fun `只有一个候选时不做探测`() = runBlocking {
        val (probe, connector) = probeWith()
        val choice = probe.choose(listOf(wan))
        assertEquals(wan, choice.address)
        assertFalse(choice.probed)
        assertEquals(0, connector.calls.get())
    }

    @Test
    fun `空列表返回没有地址`() = runBlocking {
        val (probe, _) = probeWith()
        val choice = probe.choose(emptyList())
        assertNull(choice.address)
        assertFalse(choice.probed)
    }

    @Test
    fun `同一种网络的多个地址也按顺序兜底`() = runBlocking {
        val second = lan.copy(id = 3, host = "192.168.1.11")
        val (probe, _) = probeWith(
            "192.168.1.10" to FakeConnector.Behaviour.refused(),
            "192.168.1.11" to FakeConnector.Behaviour.ok(),
        )
        val choice = probe.choose(listOf(lan, second))
        assertEquals(second, choice.address)
        assertTrue(choice.usedFallback)
    }
}
