package io.github.gua123.mediagate.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AddressSelector] 的 JVM 单测（**R7**：plan 4.5「判定顺序：网络能力 → 规则 → 竞速 → 协议握手」）。
 *
 * 覆盖：离线 / 无地址 / 规则命中与不命中 / 规则具体度优先级 / 偏好排序 / 竞速信号 / 稳定排序。
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
        assertFalse(result.needsRace)
        assertTrue(result.explanation.contains("没有网络"))
    }

    @Test
    fun `没有配置地址时判 NO_ADDRESS`() {
        val result = AddressSelector.select(emptyList(), homeWifi)
        assertEquals(SelectionReason.NO_ADDRESS, result.reason)
        assertNull(result.primary)
        assertFalse(result.needsRace)
    }

    @Test
    fun `无规则且多地址：给竞速信号并按人工优先级排序`() {
        val result = AddressSelector.select(listOf(lan, wan), homeWifi)
        assertEquals(SelectionReason.NEEDS_RACE, result.reason)
        assertTrue(result.needsRace)
        // wan 的 priority=10 > lan 的 0
        assertEquals(wan, result.primary)
        assertEquals(listOf(wan, lan), result.ordered)
        assertTrue(result.explanation.contains("竞速"))
    }

    @Test
    fun `无规则但只有一个地址：不需要竞速`() {
        val result = AddressSelector.select(listOf(lan), homeWifi)
        assertEquals(SelectionReason.SINGLE_ADDRESS, result.reason)
        assertFalse(result.needsRace)
        assertEquals(lan, result.primary)
    }

    @Test
    fun `命中传输类型规则：按 prefer 排序且不竞速`() {
        val rule = NetworkRule(id = 7, transport = NetworkCapability.CELLULAR, prefer = AddressLabel.WAN)
        val result = AddressSelector.select(listOf(lan, wan), cellular, listOf(rule))
        assertEquals(SelectionReason.RULE_MATCHED, result.reason)
        assertFalse(result.needsRace)
        assertEquals(wan, result.primary)
        assertEquals(rule, result.matchedRule)
        assertTrue(result.explanation.contains("命中规则"))
        assertTrue(result.explanation.contains("公网"))
    }

    @Test
    fun `传输类型不匹配的规则不生效`() {
        val rule = NetworkRule(id = 7, transport = NetworkCapability.CELLULAR, prefer = AddressLabel.WAN)
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, listOf(rule))
        assertEquals(SelectionReason.NEEDS_RACE, result.reason)
        assertNull(result.matchedRule)
    }

    @Test
    fun `SSID 通配规则命中`() {
        val rule = NetworkRule(id = 1, ssidPattern = "Home*", prefer = AddressLabel.LAN)
        val result = AddressSelector.select(listOf(wan, lan), homeWifi, listOf(rule))
        assertEquals(SelectionReason.RULE_MATCHED, result.reason)
        assertEquals(lan, result.primary)
        assertEquals(listOf(lan, wan), result.ordered)
    }

    @Test
    fun `拿不到 SSID 时不假装命中 SSID 规则`() {
        val rule = NetworkRule(id = 1, ssidPattern = "Home*", prefer = AddressLabel.LAN)
        val noSsid = homeWifi.copy(ssid = null)
        val result = AddressSelector.select(listOf(lan, wan), noSsid, listOf(rule))
        assertEquals(SelectionReason.NEEDS_RACE, result.reason)
    }

    @Test
    fun `本机网段规则命中（CIDR 写法）`() {
        val rule = NetworkRule(id = 2, localSubnet = "192.168.1.0/24", prefer = AddressLabel.LAN)
        val result = AddressSelector.select(listOf(wan, lan), homeWifi, listOf(rule))
        assertEquals(SelectionReason.RULE_MATCHED, result.reason)
        assertEquals(lan, result.primary)
    }

    @Test
    fun `网段不匹配则不命中`() {
        val rule = NetworkRule(id = 2, localSubnet = "10.0.0.0/8", prefer = AddressLabel.LAN)
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, listOf(rule))
        assertEquals(SelectionReason.NEEDS_RACE, result.reason)
    }

    @Test
    fun `多条规则命中时 SSID 比网段更具体，优先采用`() {
        val bySubnet = NetworkRule(id = 1, localSubnet = "192.168.1.0/24", prefer = AddressLabel.WAN)
        val bySsid = NetworkRule(id = 2, ssidPattern = "Home*", prefer = AddressLabel.LAN)
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, listOf(bySubnet, bySsid))
        assertEquals(bySsid, result.matchedRule)
        assertEquals(lan, result.primary)
    }

    @Test
    fun `网段比传输类型更具体`() {
        val byTransport = NetworkRule(id = 1, transport = NetworkCapability.WIFI, prefer = AddressLabel.WAN)
        val bySubnet = NetworkRule(id = 2, localSubnet = "192.168.1.0/24", prefer = AddressLabel.LAN)
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, listOf(byTransport, bySubnet))
        assertEquals(bySubnet, result.matchedRule)
    }

    @Test
    fun `同具体度的规则取先录入者`() {
        val first = NetworkRule(id = 1, transport = NetworkCapability.WIFI, prefer = AddressLabel.LAN)
        val second = NetworkRule(id = 2, transport = NetworkCapability.WIFI, prefer = AddressLabel.WAN)
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, listOf(first, second))
        assertEquals(first, result.matchedRule)
    }

    @Test
    fun `三个条件都写时必须全部满足`() {
        val rule = NetworkRule(
            id = 1,
            transport = NetworkCapability.WIFI,
            ssidPattern = "Home*",
            localSubnet = "192.168.1.0/24",
            prefer = AddressLabel.LAN,
        )
        assertEquals(SelectionReason.RULE_MATCHED, AddressSelector.select(listOf(lan, wan), homeWifi, listOf(rule)).reason)
        // 只改一个条件（网段）就不再命中
        val other = homeWifi.copy(localSubnets = setOf("10.0.0.5/8"))
        assertEquals(SelectionReason.NEEDS_RACE, AddressSelector.select(listOf(lan, wan), other, listOf(rule)).reason)
    }

    @Test
    fun `prefer 指向的标签没有地址时退化为按优先级`() {
        val rule = NetworkRule(id = 1, transport = NetworkCapability.WIFI, prefer = AddressLabel.WAN)
        // 只有 LAN 地址，却偏好 WAN：仍然要给出首选，不能返回空
        val result = AddressSelector.select(listOf(lan), homeWifi, listOf(rule))
        assertEquals(SelectionReason.RULE_MATCHED, result.reason)
        assertEquals(lan, result.primary)
    }

    @Test
    fun `排序稳定：同优先级按 id 升序`() {
        val a = address(id = 9, label = AddressLabel.LAN, priority = 5)
        val b = address(id = 3, label = AddressLabel.LAN, priority = 5)
        val c = address(id = 6, label = AddressLabel.LAN, priority = 5)
        val result = AddressSelector.select(listOf(a, b, c), homeWifi)
        assertEquals(listOf(3L, 6L, 9L), result.ordered.map { it.id })
    }

    @Test
    fun `规则为任意网络时也匹配（三条件全空）`() {
        val rule = NetworkRule(id = 1, prefer = AddressLabel.WAN)
        assertEquals(0, rule.specificity)
        val result = AddressSelector.select(listOf(lan, wan), homeWifi, listOf(rule))
        assertEquals(SelectionReason.RULE_MATCHED, result.reason)
        assertEquals(wan, result.primary)
    }

    @Test
    fun `explanation 始终是中文且非空`() {
        val cases = listOf(
            AddressSelector.select(emptyList(), homeWifi),
            AddressSelector.select(listOf(lan), NetworkContext.Offline),
            AddressSelector.select(listOf(lan), homeWifi),
            AddressSelector.select(listOf(lan, wan), homeWifi),
            AddressSelector.select(listOf(lan, wan), homeWifi, listOf(NetworkRule(id = 1, prefer = AddressLabel.LAN))),
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
