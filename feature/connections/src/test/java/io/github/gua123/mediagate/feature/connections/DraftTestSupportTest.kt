package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.AddressTestResult
import io.github.gua123.mediagate.core.network.ConnectivityError
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress
import io.github.gua123.mediagate.core.network.TestStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器「测试一下」的纯逻辑（**R8**）。
 *
 * 真机反馈的场景：填完密码保存 → 点开浏览 → 认证失败，用户不知道是密码错、端口错还是服务器拒人。
 * 这里钉住两件事：草稿要能变成可测试的记录（含刚输入的密码口径），以及结果要给出**能照做**的一句话。
 */
class DraftTestSupportTest {

    @Test
    fun `草稿转记录：协议用户名地址都按草稿来`() {
        val draft = ConnectionDraft(
            name = "home",
            protocol = ProtocolKind.SFTP,
            basePath = "/media",
            username = "  hml  ",
            addresses = listOf(
                AddressDraft(label = AddressLabel.LAN, scheme = "sftp", host = " nas.local ", port = "2222", priority = "1"),
                AddressDraft(label = AddressLabel.WAN, scheme = "sftp", host = "", port = "2222"),
            ),
        )

        val record = DraftTestSupport.recordOf(draft)

        assertEquals(ProtocolKind.SFTP, record.protocol)
        assertEquals("/media", record.basePath)
        assertEquals("用户名要去空格", "hml", record.username)
        assertEquals("空主机名的地址不参与测试", 1, record.addresses.size)
        assertEquals("nas.local", record.addresses[0].host)
        assertEquals(2222, record.addresses[0].port)
        assertEquals(1, record.addresses[0].priority)
        assertTrue("还没保存的草稿没有 id", record.id == 0L)
    }

    @Test
    fun `用户名留空时是 null 而不是空串`() {
        val record = DraftTestSupport.recordOf(ConnectionDraft(protocol = ProtocolKind.WEBDAV, username = "   "))
        assertNull(record.username)
    }

    @Test
    fun `结果：有成功地址就报通过并带耗时`() {
        val summary = DraftTestSupport.summarize(
            listOf(
                result(address(1, "sftp://nas.local:2222"), ok = false, error = ConnectivityError.AUTH_FAILED),
                result(address(2, "sftp://192.168.1.10:2222"), ok = true, connectMs = 15, handshakeMs = 41),
            ),
        )

        assertTrue(summary, summary.startsWith("测试通过"))
        assertTrue("要带地址：$summary", summary.contains("192.168.1.10:2222"))
        assertTrue("要带耗时：$summary", summary.contains("56 ms"))
    }

    @Test
    fun `结果：全失败时报第一条的原因与三段耗时`() {
        val summary = DraftTestSupport.summarize(
            listOf(
                result(address(1, "sftp://nas.local:2222"), ok = false, error = ConnectivityError.AUTH_FAILED, dnsMs = 2, connectMs = 15),
            ),
        )

        assertTrue(summary, summary.startsWith("测试失败"))
        assertTrue("要摊开原因：$summary", summary.contains(ConnectivityError.AUTH_FAILED.display))
        assertTrue("要给出三段耗时：$summary", summary.contains("DNS 2 ms"))
    }

    @Test
    fun `结果：一个地址都没填时如实说`() {
        assertEquals("没有可测试的地址：先填主机名", DraftTestSupport.summarize(emptyList()))
    }

    private fun address(id: Long, display: String): SelectableAddress {
        val parts = display.removePrefix("sftp://").split(":")
        return SelectableAddress(id = id, label = AddressLabel.LAN, scheme = "sftp", host = parts[0], port = parts[1].toInt())
    }

    private fun result(
        address: SelectableAddress,
        ok: Boolean,
        error: ConnectivityError? = null,
        dnsMs: Long = 0L,
        connectMs: Long = 0L,
        handshakeMs: Long = 0L,
    ) = AddressTestResult(
        address = address,
        ok = ok,
        dnsMs = dnsMs,
        connectMs = connectMs,
        handshakeMs = handshakeMs,
        error = error,
        failedStage = if (ok) null else TestStage.HANDSHAKE,
    )
}
