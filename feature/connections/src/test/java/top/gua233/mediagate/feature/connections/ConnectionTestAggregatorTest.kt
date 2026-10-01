package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.network.TestStage

/**
 * 「测试全部」结果聚合与排序的 JVM 单测（**R8**：多连接自测后的汇总）。
 *
 * 排序口径：失败的连接在前、其次连接 id；连接内失败的地址在前、其次地址 id。
 */
class ConnectionTestAggregatorTest {

    private fun address(id: Long, ok: Boolean, handshakeSkipped: Boolean = false) = AddressTestUi(
        addressId = id,
        display = "地址" + id,
        ok = ok,
        dnsMs = 1L,
        connectMs = 2L,
        handshakeMs = 3L,
        handshakeSkipped = handshakeSkipped,
        failedStage = if (ok) null else TestStage.TCP,
        errorCode = if (ok) null else "TIMEOUT",
        errorText = if (ok) null else "连接超时（地址不可达）",
        message = null,
        notice = if (handshakeSkipped) "SFTP 暂无协议握手实现：本次只验证到 DNS/TCP 两段，未做协议握手" else null,
    )

    private fun result(id: Long, vararg addresses: AddressTestUi) =
        ConnectionTestUi(connectionId = id, testedAtMs = 1_000L, addresses = addresses.toList())

    @Test
    fun `汇总统计与中文汇总行`() {
        val summary = ConnectionTestAggregator.summarize(
            listOf(
                result(1L, address(11L, true), address(12L, true)),
                result(2L, address(21L, false), address(22L, true)),
                result(3L, address(31L, false)),
            ),
        )
        assertEquals(3, summary.totalConnections)
        assertEquals(2, summary.okConnections)
        assertEquals(1, summary.failedConnections)
        assertEquals(5, summary.totalAddresses)
        assertEquals(3, summary.okAddresses)
        assertEquals(2, summary.failedAddresses)
        assertFalse(summary.allOk)
        assertEquals("测试 3 个连接 · 5 个地址：2 通 1 失败", summary.summaryLine)
    }

    @Test
    fun `失败的连接排在前面`() {
        val summary = ConnectionTestAggregator.summarize(
            listOf(
                result(1L, address(11L, true)),
                result(5L, address(51L, true)),
                result(3L, address(31L, false)),
            ),
        )
        assertEquals(listOf(3L, 1L, 5L), summary.ordered.map { it.connectionId })
    }

    @Test
    fun `同状态按连接 id 升序`() {
        val summary = ConnectionTestAggregator.summarize(
            listOf(result(7L, address(71L, false)), result(2L, address(21L, false)), result(5L, address(51L, false))),
        )
        assertEquals(listOf(2L, 5L, 7L), summary.ordered.map { it.connectionId })
    }

    @Test
    fun `连接内失败地址排前面`() {
        val summary = ConnectionTestAggregator.summarize(
            listOf(result(1L, address(11L, true), address(12L, false), address(13L, true), address(14L, false))),
        )
        assertEquals(listOf(12L, 14L, 11L, 13L), summary.ordered.single().addresses.map { it.addressId })
    }

    @Test
    fun `全部通过时 allOk 为真`() {
        val summary = ConnectionTestAggregator.summarize(listOf(result(1L, address(11L, true))))
        assertTrue(summary.allOk)
        assertEquals(0, summary.failedConnections)
        assertFalse(summary.ordered.single().partial)
    }

    @Test
    fun `部分可用的连接会被标为 partial`() {
        val item = result(1L, address(11L, true), address(12L, false))
        assertTrue(item.partial)
        assertTrue(item.ok)
        assertEquals(1, item.okCount)
        assertEquals(1, item.failCount)
        assertEquals("2 个地址：1 通 1 失败", item.summaryLine)
    }

    @Test
    fun `空结果给出空汇总`() {
        val summary = ConnectionTestAggregator.summarize(emptyList())
        assertEquals(0, summary.totalConnections)
        assertTrue(summary.allOk)
        assertEquals(TestAllSummary.Empty.summaryLine, summary.summaryLine)
    }

    @Test
    fun `跳过握手的地址文案如实说明只测了 TCP`() {
        val skipped = address(1L, true, handshakeSkipped = true)
        assertEquals("TCP 可达（未做协议握手）", skipped.statusText)
        assertTrue(skipped.timingLine.contains("握手未测"))
        assertTrue(skipped.notice!!.contains("暂无协议握手实现"))
        assertEquals(6L, skipped.totalMs)
    }

    @Test
    fun `失败地址的状态文案是错误分类`() {
        val failed = address(1L, false)
        assertTrue(failed.statusText.contains("连接超时"))
        assertEquals("DNS 1 ms · TCP 2 ms · 握手 3 ms", failed.timingLine)
    }
}
