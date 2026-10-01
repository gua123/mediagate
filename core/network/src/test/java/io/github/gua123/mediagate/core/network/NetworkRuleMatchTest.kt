package io.github.gua123.mediagate.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkRuleMatch] 的 JVM 单测（**R7**：规则条件里的 SSID 通配与本机网段三种写法）。
 */
class NetworkRuleMatchTest {

    @Test
    fun `SSID 通配匹配`() {
        assertTrue(NetworkRuleMatch.matchesSsid("Home*", "Home-5G"))
        assertTrue(NetworkRuleMatch.matchesSsid("*", "任意名字"))
        assertTrue(NetworkRuleMatch.matchesSsid("home-5g", "HOME-5G"))
        assertTrue(NetworkRuleMatch.matchesSsid("*Guest*", "my-Guest-wifi"))
    }

    @Test
    fun `SSID 不匹配与空模式`() {
        assertFalse(NetworkRuleMatch.matchesSsid("Home*", "Office"))
        assertFalse(NetworkRuleMatch.matchesSsid("", "Home-5G"))
        assertFalse(NetworkRuleMatch.matchesSsid(null, "Home-5G"))
        assertFalse(NetworkRuleMatch.matchesSsid("Home*", null))
        assertFalse(NetworkRuleMatch.matchesSsid("Home*", "<unknown ssid>"))
    }

    @Test
    fun `网段 CIDR 匹配（两边都是 CIDR）`() {
        assertTrue(NetworkRuleMatch.matchesSubnet("192.168.1.0/24", setOf("192.168.1.10/24")))
        assertTrue(NetworkRuleMatch.matchesSubnet("192.168.1.0/24", setOf("192.168.1.0/24")))
        assertTrue(NetworkRuleMatch.matchesSubnet("192.168.0.0/16", setOf("192.168.1.10/24")))
        assertFalse(NetworkRuleMatch.matchesSubnet("192.168.41.0/24", setOf("192.168.1.10/24")))
    }

    @Test
    fun `网段前缀写法与通配写法`() {
        assertTrue(NetworkRuleMatch.matchesSubnet("192.168.1.", setOf("192.168.1.10/24")))
        assertTrue(NetworkRuleMatch.matchesSubnet("192.168.1.*", setOf("192.168.1.10/24")))
        assertFalse(NetworkRuleMatch.matchesSubnet("192.168.41.", setOf("192.168.1.10/24")))
    }

    @Test
    fun `网段精确 IP 写法`() {
        assertTrue(NetworkRuleMatch.matchesSubnet("192.168.1.10", setOf("192.168.1.10/24")))
        assertFalse(NetworkRuleMatch.matchesSubnet("192.168.1.7", setOf("192.168.1.10/24")))
    }

    @Test
    fun `非 IPv4 退化为字符串相等`() {
        assertTrue(NetworkRuleMatch.matchesSubnet("fd00::1/64", setOf("fd00::1/64")))
        assertFalse(NetworkRuleMatch.matchesSubnet("fd00::1/64", setOf("fd00::2/64")))
    }

    @Test
    fun `空模式与空集合都不匹配`() {
        assertFalse(NetworkRuleMatch.matchesSubnet(null, setOf("192.168.1.10/24")))
        assertFalse(NetworkRuleMatch.matchesSubnet("", setOf("192.168.1.10/24")))
        assertFalse(NetworkRuleMatch.matchesSubnet("192.168.1.0/24", emptySet()))
    }
}
