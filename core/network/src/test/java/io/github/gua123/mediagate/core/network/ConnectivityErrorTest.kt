package io.github.gua123.mediagate.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * [ConnectivityError] 的 JVM 单测（**R8**：错误分类；**R7**：失败原因可解释）。
 *
 * 保证：枚举值稳定（可落库）、中文文案齐全、异常/状态码/提示串三条入口都能归类。
 */
class ConnectivityErrorTest {

    @Test
    fun `枚举 code 稳定且唯一`() {
        val codes = ConnectivityError.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals("DNS_FAILED", ConnectivityError.DNS_FAILED.code)
        assertEquals("CONNECTION_REFUSED", ConnectivityError.CONNECTION_REFUSED.code)
        assertEquals("TIMEOUT", ConnectivityError.TIMEOUT.code)
        assertEquals("AUTH_FAILED", ConnectivityError.AUTH_FAILED.code)
        assertEquals("PERMISSION_DENIED", ConnectivityError.PERMISSION_DENIED.code)
        assertEquals("PROTOCOL_UNSUPPORTED", ConnectivityError.PROTOCOL_UNSUPPORTED.code)
    }

    @Test
    fun `每个枚举都有中文文案与提示`() {
        ConnectivityError.entries.forEach { error ->
            assertTrue(error.zhText.isNotBlank())
            assertTrue(error.hint.isNotBlank())
            assertTrue("中文文案缺失：" + error.code, error.zhText.any { it.code in 0x4E00..0x9FFF })
            assertTrue("中文提示缺失：" + error.code, error.hint.any { it.code in 0x4E00..0x9FFF })
            assertTrue(error.display.contains(error.zhText))
        }
    }

    @Test
    fun `异常到分类的映射`() {
        assertEquals(ConnectivityError.DNS_FAILED, ConnectivityError.fromThrowable(UnknownHostException("x")))
        assertEquals(ConnectivityError.TIMEOUT, ConnectivityError.fromThrowable(SocketTimeoutException("connect timed out")))
        assertEquals(ConnectivityError.CONNECTION_REFUSED, ConnectivityError.fromThrowable(ConnectException("Connection refused")))
        assertEquals(ConnectivityError.NETWORK_UNREACHABLE, ConnectivityError.fromThrowable(NoRouteToHostException("no route")))
        assertEquals(ConnectivityError.NETWORK_UNREACHABLE, ConnectivityError.fromThrowable(ConnectException("Network is unreachable")))
        assertEquals(ConnectivityError.TLS_FAILED, ConnectivityError.fromThrowable(SSLHandshakeException("bad cert")))
        assertEquals(ConnectivityError.TIMEOUT, ConnectivityError.fromThrowable(IOException("Read timed out")))
        assertEquals(ConnectivityError.UNKNOWN, ConnectivityError.fromThrowable(IllegalStateException("???")))
        // 真机实测（2026-10-02 连接页截图）：targetSdk 36 未放开明文时 OkHttp 抛的就是这一句
        assertEquals(
            ConnectivityError.CLEARTEXT_BLOCKED,
            ConnectivityError.fromThrowable(
                IOException("CLEARTEXT communication to 192.168.1.10 not permitted by network security policy"),
            ),
        )
    }

    @Test
    fun `HTTP 状态码到分类的映射`() {
        assertEquals(ConnectivityError.AUTH_FAILED, ConnectivityError.fromHttpStatus(401))
        assertEquals(ConnectivityError.PERMISSION_DENIED, ConnectivityError.fromHttpStatus(403))
        assertEquals(ConnectivityError.PATH_NOT_FOUND, ConnectivityError.fromHttpStatus(404))
        assertEquals(ConnectivityError.PROTOCOL_UNSUPPORTED, ConnectivityError.fromHttpStatus(405))
        assertEquals(ConnectivityError.PROTOCOL_UNSUPPORTED, ConnectivityError.fromHttpStatus(501))
        assertEquals(ConnectivityError.TIMEOUT, ConnectivityError.fromHttpStatus(504))
        // 2xx / 无法归类的状态仍是"没有分类"（由上层决定怎么说）
        assertNull(ConnectivityError.fromHttpStatus(207))
        assertNull(ConnectivityError.fromHttpStatus(200))
        // **2026-10-03 补**：3xx（跳转）与 5xx（服务端错误）以前都落到"未知错误"，
        // 真机就是 `http://…:20005` 那次——用户只看到「未知错误 · 查看详情」，看不出下一步。
        assertEquals(ConnectivityError.REDIRECT, ConnectivityError.fromHttpStatus(301))
        assertEquals(ConnectivityError.REDIRECT, ConnectivityError.fromHttpStatus(302))
        assertEquals(ConnectivityError.REDIRECT, ConnectivityError.fromHttpStatus(307))
        assertEquals(ConnectivityError.SERVER_ERROR, ConnectivityError.fromHttpStatus(500))
        assertEquals(ConnectivityError.SERVER_ERROR, ConnectivityError.fromHttpStatus(502))
        assertEquals(ConnectivityError.SERVER_ERROR, ConnectivityError.fromHttpStatus(503))
        assertEquals(ConnectivityError.REQUEST_REJECTED, ConnectivityError.fromHttpStatus(400))
        assertEquals(ConnectivityError.REQUEST_REJECTED, ConnectivityError.fromHttpStatus(429))
    }

    @Test
    fun `5xx 的提示里要留"可能是 VPN 拦的"这条经验`() {
        // 2026-10-03：用户为更新开了 VPN，WebDAV 被代理打成 503；提示必须给出可照做的方向
        val hint = ConnectivityError.SERVER_ERROR.hint
        assertTrue("提示应提到 VPN 或代理：" + hint, hint.contains("VPN") || hint.contains("代理"))
        assertTrue("提示应给出方向：" + hint, hint.contains("只代理 GitHub"))
    }

    @Test
    fun `从中文提示猜分类（先抽状态码）`() {
        assertEquals(ConnectivityError.AUTH_FAILED, ConnectivityError.fromMessage("401 账号或密码错误"))
        assertEquals(ConnectivityError.PERMISSION_DENIED, ConnectivityError.fromMessage("403 无访问权限（账号对该目录无读权限）"))
        assertEquals(ConnectivityError.PATH_NOT_FOUND, ConnectivityError.fromMessage("404 路径不存在（检查根路径是否写对）"))
        assertEquals(ConnectivityError.PROTOCOL_UNSUPPORTED, ConnectivityError.fromMessage("405 服务器不支持 PROPFIND（可能不是 WebDAV 服务）"))
        assertEquals(ConnectivityError.DNS_FAILED, ConnectivityError.fromMessage("DNS 解析失败（dav.example.com）"))
        assertEquals(ConnectivityError.TIMEOUT, ConnectivityError.fromMessage("连接或读取超时（5000 ms / 15000 ms）"))
        assertEquals(ConnectivityError.CONNECTION_REFUSED, ConnectivityError.fromMessage("网络不可达之前先被拒绝"))
        // 明文被拦不能被"不可达"抢走分类（WebDAV probe 曾经统一加「网络不可达：」前缀）
        assertEquals(
            ConnectivityError.CLEARTEXT_BLOCKED,
            ConnectivityError.fromMessage("系统禁止明文 http：请改用 https，或在连接页打开「允许明文 http」"),
        )
        assertEquals(ConnectivityError.CLEARTEXT_BLOCKED, ConnectivityError.fromMessage("网络不可达：CLEARTEXT not permitted"))
        assertNull(ConnectivityError.fromMessage(null))
        assertNull(ConnectivityError.fromMessage("  "))
        assertEquals(ConnectivityError.UNKNOWN, ConnectivityError.fromMessage("莫名其妙的一句话"))
    }

    @Test
    fun `测试阶段枚举齐全`() {
        assertEquals(listOf("DNS", "TCP", "HANDSHAKE"), TestStage.entries.map { it.code })
        assertTrue(TestStage.HANDSHAKE.zhText.contains("握手"))
    }

    @Test
    fun `网络能力与地址标签的字符串映射容错`() {
        assertEquals(NetworkCapability.WIFI, NetworkCapability.fromId("wifi"))
        assertEquals(NetworkCapability.CELLULAR, NetworkCapability.fromId("CELLULAR"))
        assertEquals(NetworkCapability.UNKNOWN, NetworkCapability.fromId("蓝牙"))
        assertEquals(NetworkCapability.UNKNOWN, NetworkCapability.fromId(null))
        assertEquals(AddressLabel.WAN, AddressLabel.fromId("wan"))
        assertEquals(AddressLabel.LAN, AddressLabel.fromId("LAN"))
        assertEquals(AddressLabel.LAN, AddressLabel.fromId("???"))
    }

    @Test
    fun `SSID 归一化`() {
        assertEquals("Home-5G", NetworkContext.normalizeSsid("\"Home-5G\""))
        assertNull(NetworkContext.normalizeSsid("<unknown ssid>"))
        assertNull(NetworkContext.normalizeSsid("   "))
        assertNull(NetworkContext.normalizeSsid(null))
    }
}
