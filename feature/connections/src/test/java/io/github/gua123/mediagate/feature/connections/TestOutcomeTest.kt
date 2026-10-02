package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
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
    fun `标记符号稳定（供界面加前缀）`() {
        assertEquals("✓", TestOutcome.PASSED.mark)
        assertEquals("✗", TestOutcome.FAILED.mark)
    }
}
