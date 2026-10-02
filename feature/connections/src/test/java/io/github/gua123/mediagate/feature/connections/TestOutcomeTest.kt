package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.AddressTestResult
import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.core.network.TestStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试结果的颜色分类（**2026-10-03 真机截图**：「测试通过」被染成红色）。
 *
 * 第一个用例就是用户截图里的那串原文——界面原来用 `startsWith("测试通过")` 判断，
 * 而真实文案开头是「〔使用：已保存的密码〕」，于是永远判成失败色。
 */
class TestOutcomeTest {

    @Test
    fun `真机那串"测试通过"必须判成 PASSED`() {
        val real = "〔使用：已保存的密码〕测试通过：sftp://vpn.example.com:22000（1209 ms）"
        assertEquals(TestOutcome.PASSED, testOutcomeOf(real))
    }

    @Test
    fun `认证失败判成 FAILED`() {
        val real = "〔使用：新输入的密码〕测试失败：sftp://dav.example.com:2222 — 认证失败：账号或密码不对（TCP 15 ms）"
        assertEquals(TestOutcome.FAILED, testOutcomeOf(real))
    }

    @Test
    fun `没有地址时既不算通过也不算失败`() {
        assertEquals(TestOutcome.NOT_APPLICABLE, testOutcomeOf("没有可测试的地址：先填主机名"))
    }

    @Test
    fun `未知错误要把原始原因摊开（真机 3xx 跳转那次的教训）`() {
        val result = DraftTestSupport.summarize(
            listOf(
                AddressTestResult(
                    address = SelectableAddress(
                        id = 1L,
                        label = AddressLabel.WAN,
                        scheme = "http",
                        host = "vpn.example.com",
                        port = 20005,
                    ),
                    ok = false,
                    dnsMs = 209,
                    connectMs = 1,
                    handshakeMs = 579,
                    error = ConnectivityError.UNKNOWN,
                    failedStage = TestStage.HANDSHAKE,
                    message = "Unexpected status 302",
                ),
            ),
            PasswordSource.NEW_INPUT,
        )
        assertTrue("应带上原始原因，而不是只说查看详情：" + result, result.contains("Unexpected status 302"))
    }

    @Test
    fun `3xx 与 5xx 现在有明确分类`() {
        assertEquals(ConnectivityError.REDIRECT, ConnectivityError.fromHttpStatus(302))
        assertEquals(ConnectivityError.SERVER_ERROR, ConnectivityError.fromHttpStatus(502))
        assertEquals(ConnectivityError.REQUEST_REJECTED, ConnectivityError.fromHttpStatus(429))
    }

    @Test
    fun `技术详情把原始信息摊开（用户问「在哪里查看详情」）`() {
        val detail = DraftTestSupport.detailOf(
            listOf(
                AddressTestResult(
                    address = SelectableAddress(
                        id = 1L,
                        label = AddressLabel.WAN,
                        scheme = "http",
                        host = "vpn.example.com",
                        port = 20005,
                    ),
                    ok = false,
                    dnsMs = 209,
                    connectMs = 1,
                    handshakeMs = 579,
                    error = ConnectivityError.SERVER_ERROR,
                    failedStage = TestStage.HANDSHAKE,
                    message = "HTTP 502 Bad Gateway",
                ),
            ),
            PasswordSource.NEW_INPUT,
        )
        assertTrue("详情不该为空", detail != null)
        val text = detail!!
        assertTrue("要写清卡在哪一段：" + text, text.contains("协议握手"))
        assertTrue("要写清分类：" + text, text.contains("SERVER_ERROR"))
        assertTrue("要带原始信息：" + text, text.contains("HTTP 502 Bad Gateway"))
        assertTrue("要带耗时：" + text, text.contains("DNS 209 ms"))
        assertTrue("要标明用的是哪份凭据：" + text, text.contains("本次新输入的密码"))
    }

    @Test
    fun `没有测试结果时技术详情为空`() {
        assertEquals(null, DraftTestSupport.detailOf(emptyList(), PasswordSource.NONE))
    }

    @Test
    fun `标记符号稳定（供界面加前缀）`() {
        assertEquals("✓", TestOutcome.PASSED.mark)
        assertEquals("✗", TestOutcome.FAILED.mark)
    }
}
