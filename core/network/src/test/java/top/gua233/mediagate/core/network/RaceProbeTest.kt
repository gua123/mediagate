package io.github.gua123.mediagate.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RaceProbe] 的 JVM 单测（**R7**：plan 4.5「无规则时并行竞速探测（TCP 1.5 s）」+「结果缓存 60 s」）。
 *
 * 用假连接器控制"谁先成功"（延迟用真实 Thread.sleep，毫秒级），用假时钟控制缓存过期；
 * 地址全用 IP 字面量，避免单测真的去解析域名。
 */
class RaceProbeTest {

    private val lan = address(id = 1, label = AddressLabel.LAN, host = LAN_IP)
    private val wan = address(id = 2, label = AddressLabel.WAN, host = WAN_IP, port = 8443)
    private val backup = address(id = 3, label = AddressLabel.WAN, host = BACKUP_IP, port = 443)

    private fun probeWith(connector: FakeConnector, clock: FakeClock = FakeClock()): RaceProbe {
        val tcp = TcpProbe(
            clock = clock,
            resolver = FakeResolver(),
            connector = connector,
            io = Dispatchers.Default,
        )
        return RaceProbe(tcp = tcp, clock = clock, io = Dispatchers.Default)
    }

    @Test
    fun `先成功者胜：慢地址不等`() = runBlocking {
        val connector = FakeConnector(
            mapOf(
                LAN_IP to FakeConnector.Behaviour.refused(delayMs = 80L),
                WAN_IP to FakeConnector.Behaviour.ok(delayMs = 5L),
            ),
        )
        val outcome = probeWith(connector).race(listOf(lan, wan), cacheKey = null)
        assertEquals(wan, outcome.winner)
        assertTrue(outcome.ok)
        assertFalse(outcome.allFailed)
        assertFalse(outcome.fromCache)
    }

    @Test
    fun `三地址并行：最快的成功者胜`() = runBlocking {
        val connector = FakeConnector(
            mapOf(
                LAN_IP to FakeConnector.Behaviour.timeout(delayMs = 60L),
                WAN_IP to FakeConnector.Behaviour.ok(delayMs = 25L),
                BACKUP_IP to FakeConnector.Behaviour.ok(delayMs = 5L),
            ),
        )
        val outcome = probeWith(connector).race(listOf(lan, wan, backup), cacheKey = null)
        assertEquals(backup, outcome.winner)
        assertTrue(connector.calls.get() >= 1)
    }

    @Test
    fun `全部失败：返回每条明细且顺序与输入一致`() = runBlocking {
        val connector = FakeConnector(
            mapOf(
                LAN_IP to FakeConnector.Behaviour.refused(delayMs = 30L),
                WAN_IP to FakeConnector.Behaviour.timeout(delayMs = 5L),
            ),
        )
        val outcome = probeWith(connector).race(listOf(lan, wan), cacheKey = null)
        assertNull(outcome.winner)
        assertTrue(outcome.allFailed)
        assertEquals(listOf(lan, wan), outcome.attempts.map { it.target })
        assertEquals(ConnectivityError.CONNECTION_REFUSED, outcome.attempts[0].result.error)
        assertEquals(ConnectivityError.TIMEOUT, outcome.attempts[1].result.error)
    }

    @Test
    fun `空地址列表直接返回空结论`() = runBlocking {
        val connector = FakeConnector()
        val outcome = probeWith(connector).race(emptyList())
        assertNull(outcome.winner)
        assertTrue(outcome.attempts.isEmpty())
        assertEquals(0, connector.calls.get())
    }

    @Test
    fun `60 秒内重复竞速直接命中缓存`() = runBlocking {
        val clock = FakeClock()
        val connector = FakeConnector(mapOf(LAN_IP to FakeConnector.Behaviour.ok()))
        val race = probeWith(connector, clock)
        val first = race.race(listOf(lan))
        assertFalse(first.fromCache)
        val callsAfterFirst = connector.calls.get()

        clock.advance(59_000L)
        val second = race.race(listOf(lan))
        assertTrue("59 秒时应当命中缓存", second.fromCache)
        assertEquals(lan, second.winner)
        assertEquals("命中缓存不应再探测", callsAfterFirst, connector.calls.get())
    }

    @Test
    fun `超过 60 秒后缓存过期并重新探测`() = runBlocking {
        val clock = FakeClock()
        val connector = FakeConnector(mapOf(LAN_IP to FakeConnector.Behaviour.ok()))
        val race = probeWith(connector, clock)
        race.race(listOf(lan))
        val callsAfterFirst = connector.calls.get()

        clock.advance(60_000L)
        val again = race.race(listOf(lan))
        assertFalse(again.fromCache)
        assertEquals(callsAfterFirst + 1, connector.calls.get())
    }

    @Test
    fun `强制重测时绕过缓存`() = runBlocking {
        val clock = FakeClock()
        val connector = FakeConnector(mapOf(LAN_IP to FakeConnector.Behaviour.ok()))
        val race = probeWith(connector, clock)
        race.race(listOf(lan))
        val callsAfterFirst = connector.calls.get()
        val forced = race.race(listOf(lan), useCache = false)
        assertFalse(forced.fromCache)
        assertEquals(callsAfterFirst + 1, connector.calls.get())
    }

    @Test
    fun `网络变化清缓存后立刻重测`() = runBlocking {
        val clock = FakeClock()
        val connector = FakeConnector(mapOf(LAN_IP to FakeConnector.Behaviour.ok()))
        val race = probeWith(connector, clock)
        race.race(listOf(lan))
        val callsAfterFirst = connector.calls.get()
        race.invalidateCache()
        val again = race.race(listOf(lan))
        assertFalse(again.fromCache)
        assertEquals(callsAfterFirst + 1, connector.calls.get())
    }

    @Test
    fun `不同地址集合使用不同缓存 key`() = runBlocking {
        val clock = FakeClock()
        val connector = FakeConnector(
            mapOf(
                LAN_IP to FakeConnector.Behaviour.ok(),
                WAN_IP to FakeConnector.Behaviour.ok(),
            ),
        )
        val race = probeWith(connector, clock)
        race.race(listOf(lan))
        val second = race.race(listOf(wan))
        assertFalse("换了地址集合不应命中缓存", second.fromCache)
        assertEquals(wan, second.winner)
    }

    @Test
    fun `默认缓存 key 由地址与超时决定`() {
        val a = RaceProbe.defaultCacheKey(listOf(lan, wan), 1500L)
        val b = RaceProbe.defaultCacheKey(listOf(wan, lan), 1500L)
        val c = RaceProbe.defaultCacheKey(listOf(lan, wan), 2000L)
        assertTrue(a != b)
        assertTrue(a != c)
        assertEquals(a, RaceProbe.defaultCacheKey(listOf(lan, wan), 1500L))
    }

    private companion object {
        // IP 字面量：单测不触发真实 DNS
        const val LAN_IP = "192.168.1.10"
        const val WAN_IP = "10.8.0.2"
        const val BACKUP_IP = "10.8.0.3"
    }
}
