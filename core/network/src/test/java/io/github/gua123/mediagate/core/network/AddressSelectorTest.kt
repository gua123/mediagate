package io.github.gua123.mediagate.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AddressSelector] 的 JVM 单测（**R7**，2026-10-25 用户口径：「先内网后外网」）。
 *
 * 覆盖：离线 / 无地址 / 默认局域网优先 / 可切公网优先 / 同网络按优先级 / 兜底信号 / 稳定排序。
 */
class AddressSelectorTest {

    private val lan = address(id = 1, label = AddressLabel.LAN, host = "192.168.1.10", priority = 0)
    private val wan = address(id = 2, label = AddressLabel.WAN, host = "dav.example.com", port = 8443, priority = 10)

    private val homeWifi = NetworkContext(
        capability = NetworkCapability.WIFI,
        ssid = "Home-5G",
        localSubnets = setOf("192.168.1.10/24"),
    )

    private val cellular = NetworkContext(capability = NetworkCapability.CELLULAR, localSubnets = setOf("10.1.2.3/30"))

    @Test
    fun `离线时直接判 OFFLINE 不探测`() {
        val result = AddressSelector.select(listOf(lan, wan), NetworkContext.Offline)
        assertEquals(SelectionReason.OFFLINE, result.reason)
        assertNull(result.primary)
        assertTrue(result.ordered.isEmpty())
        assertFalse(result.hasFallback)
        assertTrue(result.explanation.contains("没有网络"))
    }

    @Test
    fun `没有配置地址时判 NO_ADDRESS`() {
        val result = AddressSelector.select(emptyList(), homeWifi)
        assertEquals(SelectionReason.NO_ADDRESS, result.reason)
        assertNull(result.primary)
        assertFalse(result.hasFallback)
    }

    @Test
    fun `默认先内网后外网：公网优先级再高也排在局域网之后`() {
        // wan 的 priority=10 > lan 的 0，但默认口径是网络优先，不是数字优先
        val result = AddressSelector.select(listOf(wan, lan), homeWifi)
        assertEquals(lan, result.primary)
        assertEquals(listOf(lan, wan), result.ordered)
        assertEquals(AddressLabel.LAN, result.preferred)
        assertEquals(SelectionReason.PREFERRED_FIRST, result.reason)
        assertTrue(result.hasFallback)
        assertTrue(result.explanation.contains("先内网后外网"))
    }

    @Test
    fun `在蜂窝网络下也保持先内网后外网（不再按传输类型改规则）`() {
        val result = AddressSelector.select(listOf(wan, lan), cellular)
        assertEquals(lan, result.primary)
        assertEquals(listOf(lan, wan), result.ordered)
    }

    @Test
    fun `连接可切换成先外网后内网`() {
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, prefer = AddressLabel.WAN)
        assertEquals(wan, result.primary)
        assertEquals(listOf(wan, lan), result.ordered)
        assertEquals(AddressLabel.WAN, result.preferred)
        assertTrue(result.explanation.contains("先外网后内网"))
    }

    @Test
    fun `只有一个地址：直接连接且没有兜底`() {
        val result = AddressSelector.select(listOf(wan), homeWifi)
        assertEquals(SelectionReason.SINGLE_ADDRESS, result.reason)
        assertFalse(result.hasFallback)
        assertEquals(wan, result.primary)
        assertTrue(result.explanation.contains("只有一个地址"))
    }

    @Test
    fun `只有一种网络但有多个地址：仍给出兜底顺序`() {
        val a = address(id = 9, label = AddressLabel.LAN, priority = 5)
        val b = address(id = 3, label = AddressLabel.LAN, priority = 5)
        val c = address(id = 6, label = AddressLabel.LAN, priority = 1)
        val result = AddressSelector.select(listOf(a, b, c), homeWifi)
        assertEquals(listOf(3L, 9L, 6L), result.ordered.map { it.id })
        assertTrue(result.hasFallback)
        assertTrue(result.explanation.contains("没有另一条网络的地址"))
    }

    @Test
    fun `同一网络内按优先级降序、同优先级按 id 升序`() {
        val high = address(id = 8, label = AddressLabel.LAN, priority = 10)
        val sameA = address(id = 4, label = AddressLabel.LAN, priority = 0)
        val sameB = address(id = 2, label = AddressLabel.LAN, priority = 0)
        val result = AddressSelector.select(listOf(sameA, high, sameB), homeWifi)
        assertEquals(listOf(8L, 2L, 4L), result.ordered.map { it.id })
    }

    @Test
    fun `prefer 指向的网络没有地址时仍给出首选，不能返回空`() {
        val result = AddressSelector.select(listOf(lan), homeWifi, prefer = AddressLabel.WAN)
        assertEquals(lan, result.primary)
        assertEquals(SelectionReason.SINGLE_ADDRESS, result.reason)
    }

    @Test
    fun `explanation 始终是中文且非空`() {
        val cases = listOf(
            AddressSelector.select(emptyList(), homeWifi),
            AddressSelector.select(listOf(lan), NetworkContext.Offline),
            AddressSelector.select(listOf(lan), homeWifi),
            AddressSelector.select(listOf(lan, wan), homeWifi),
            AddressSelector.select(listOf(lan, wan), homeWifi, prefer = AddressLabel.WAN),
        )
        cases.forEach { result ->
            assertTrue("解释不能为空：" + result.reason, result.explanation.isNotBlank())
            assertTrue(
                "解释应是中文：" + result.explanation,
                result.explanation.any { it.code in 0x4E00..0x9FFF },
            )
        }
    }
}
