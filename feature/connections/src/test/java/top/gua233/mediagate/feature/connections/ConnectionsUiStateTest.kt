package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.NetworkCapability
import io.github.gua123.mediagate.core.network.NetworkContext
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectionReason

/**
 * [ConnectionsUiState.reduce] 的 JVM 单测（**R8**：状态归约——加载、测试中、结果、删除、编辑、当前连接）。
 *
 * 归约是纯函数，所以这些断言不需要 Android 环境、不需要数据库。
 */
class ConnectionsUiStateTest {

    private val wifi = NetworkContext(capability = NetworkCapability.WIFI, ssid = "Home-5G", localSubnets = setOf("192.168.1.10/24"), revision = 3L)
    private val cellular = NetworkContext(capability = NetworkCapability.CELLULAR, localSubnets = setOf("10.1.2.3/30"), revision = 9L)

    private val record = ConnectionRecord(
        id = 1L,
        name = "家里的网盘",
        protocolId = "WEBDAV",
        protocol = ProtocolKind.WEBDAV,
        basePath = "/",
        username = "demo",
        hasSecret = true,
        options = ConnectionOptions.Default,
        tls = null,
        lastWorkingAddressId = null,
        lastCheckedAt = null,
        addresses = listOf(
            AddressRecord(1L, 1L, AddressLabel.LAN, "http", "192.168.1.10", 8080, 0),
            AddressRecord(2L, 1L, AddressLabel.WAN, "http", "dav.example.com", 8443, 0),
        ),
        rules = listOf(RuleRecord(1L, 1L, NetworkCapability.WIFI, "Home*", null, AddressLabel.LAN)),
    )

    private fun addressResult(id: Long, ok: Boolean): AddressTestUi = AddressTestUi(
        addressId = id,
        display = "局域网 · http://host" + id,
        ok = ok,
        dnsMs = 1L,
        connectMs = 2L,
        handshakeMs = 3L,
        handshakeSkipped = false,
        failedStage = if (ok) null else io.github.gua123.mediagate.core.network.TestStage.HANDSHAKE,
        errorCode = if (ok) null else "AUTH_FAILED",
        errorText = if (ok) null else "认证失败",
        message = null,
        notice = null,
    )

    @Test
    fun `加载后进入列表态并算出未测试状态`() {
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), currentConnectionId = null, network = wifi))
        assertFalse(state.loading)
        assertEquals(1, state.cards.size)
        val card = state.cards.single()
        assertEquals("家里的网盘", card.name)
        assertEquals("WebDAV", card.protocolText)
        assertEquals(2, card.addressCount)
        assertEquals(ConnectionStatus.UNTESTED, card.status)
        assertFalse(card.current)
        assertTrue(card.browsable)
    }

    @Test
    fun `卡片按当前网络算出首选地址与判定依据`() {
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), currentConnectionId = null, network = wifi))
        val card = state.cards.single()
        // Wi-Fi + SSID Home* 命中规则 → 首选局域网地址，不竞速
        assertEquals(SelectionReason.RULE_MATCHED, card.selection!!.reason)
        assertEquals(1L, card.selection!!.primary!!.id)
        assertFalse(card.selection!!.needsRace)
        assertTrue(card.selectionText!!.contains("192.168.1.10"))
    }

    @Test
    fun `换到蜂窝网络后变为无规则竞速`() {
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, cellular))
        val selection = state.cards.single().selection!!
        assertEquals(SelectionReason.NEEDS_RACE, selection.reason)
        assertTrue(selection.needsRace)
    }

    @Test
    fun `测试中状态与完成后状态`() {
        val testing = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
            .reduce(ConnectionsEvent.TestStarted(setOf(1L)))
        assertEquals(ConnectionStatus.TESTING, testing.cards.single().status)
        assertTrue(testing.testingAll == false)

        val done = testing.reduce(
            ConnectionsEvent.TestFinished(ConnectionTestUi(1L, 100L, listOf(addressResult(1L, true), addressResult(2L, true)))),
        )
        assertEquals(ConnectionStatus.OK, done.cards.single().status)
        assertTrue(done.testing.isEmpty())
    }

    @Test
    fun `部分可用与全部失败的状态归约`() {
        val partial = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
            .reduce(ConnectionsEvent.TestFinished(ConnectionTestUi(1L, 1L, listOf(addressResult(1L, true), addressResult(2L, false)))))
        assertEquals(ConnectionStatus.PARTIAL, partial.cards.single().status)

        val failed = partial.reduce(
            ConnectionsEvent.TestFinished(ConnectionTestUi(1L, 1L, listOf(addressResult(1L, false), addressResult(2L, false)))),
        )
        assertEquals(ConnectionStatus.FAILED, failed.cards.single().status)
    }

    @Test
    fun `测试全部会写汇总并按失败优先排序`() {
        val second = record.copy(id = 2L, name = "公网机房")
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record, second), null, wifi))
            .reduce(ConnectionsEvent.TestStarted(setOf(1L, 2L)))
            .reduce(
                ConnectionsEvent.TestAllFinished(
                    listOf(
                        ConnectionTestUi(1L, 1L, listOf(addressResult(1L, true))),
                        ConnectionTestUi(2L, 1L, listOf(addressResult(2L, false))),
                    ),
                ),
            )
        val summary = state.summary!!
        assertEquals(2, summary.totalConnections)
        assertEquals(1, summary.okConnections)
        assertEquals(1, summary.failedConnections)
        assertEquals("失败的连接排前面", 2L, summary.ordered.first().connectionId)
        assertFalse(summary.allOk)
        assertTrue(state.testing.isEmpty())
        assertEquals(ConnectionStatus.FAILED, state.card(2L)!!.status)
    }

    @Test
    fun `网络切换会清掉旧结果并给出提示`() {
        val tested = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
            .reduce(ConnectionsEvent.TestFinished(ConnectionTestUi(1L, 1L, listOf(addressResult(1L, true)))))
        assertEquals(ConnectionStatus.OK, tested.cards.single().status)

        val switched = tested.reduce(ConnectionsEvent.Loaded(listOf(record), null, cellular))
        assertTrue("网络换了结果必须作废", switched.results.isEmpty())
        assertEquals(ConnectionStatus.UNTESTED, switched.cards.single().status)
        assertEquals(NETWORK_SWITCH_NOTICE, switched.notice)
    }

    @Test
    fun `同一网络的 revision 自增不算切换`() {
        val tested = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
            .reduce(ConnectionsEvent.TestFinished(ConnectionTestUi(1L, 1L, listOf(addressResult(1L, true)))))
        val bumped = wifi.copy(revision = wifi.revision + 1L)
        val after = tested.reduce(ConnectionsEvent.Loaded(listOf(record), null, bumped))
        assertEquals(1, after.results.size)
        assertNull(after.notice)
    }

    @Test
    fun `删除的连接不会留下幽灵结果`() {
        val second = record.copy(id = 2L)
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record, second), null, wifi))
            .reduce(
                ConnectionsEvent.TestAllFinished(
                    listOf(
                        ConnectionTestUi(1L, 1L, listOf(addressResult(1L, true))),
                        ConnectionTestUi(2L, 1L, listOf(addressResult(2L, true))),
                    ),
                ),
            )
        assertEquals(2, state.results.size)
        val afterDelete = state.reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
        assertEquals(1, afterDelete.results.size)
        assertNull(afterDelete.results[2L])
    }

    @Test
    fun `编辑器开关与校验错误`() {
        val draft = defaultDraft(ProtocolKind.WEBDAV)
        val opened = ConnectionsUiState().reduce(ConnectionsEvent.EditorOpened(draft))
        assertEquals(draft, opened.editor)

        val invalid = opened.reduce(
            ConnectionsEvent.EditorInvalid(ConnectionFormResult(globalErrors = mapOf(FormField.NAME to "请填写连接名称"))),
        )
        assertEquals("请填写连接名称", invalid.editorErrors.errorOf(FormField.NAME))
        assertEquals(draft, invalid.editor)

        val closed = invalid.reduce(ConnectionsEvent.EditorClosed)
        assertNull(closed.editor)
        assertTrue(closed.editorErrors.valid)
    }

    @Test
    fun `保存成功会关掉编辑器并给出中文提示`() {
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.EditorOpened(defaultDraft(ProtocolKind.WEBDAV)))
            .reduce(ConnectionsEvent.Saved("家里的网盘", created = true))
        assertNull(state.editor)
        assertEquals("已保存连接：家里的网盘", state.notice)

        val updated = ConnectionsUiState().reduce(ConnectionsEvent.Saved("公网机房", created = false))
        assertEquals("已更新连接：公网机房", updated.notice)
    }

    @Test
    fun `当前连接标记与删除请求`() {
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), currentConnectionId = 1L, network = wifi))
            .reduce(ConnectionsEvent.DeletePending(1L))
        assertTrue(state.cards.single().current)
        assertEquals(1L, state.pendingDeleteId)
        assertNull(state.reduce(ConnectionsEvent.DeletePending(null)).pendingDeleteId)
        assertFalse(state.reduce(ConnectionsEvent.CurrentChanged(null)).cards.single().current)
    }

    @Test
    fun `测试失败会退出测试态并提示`() {
        val state = ConnectionsUiState()
            .reduce(ConnectionsEvent.Loaded(listOf(record), null, wifi))
            .reduce(ConnectionsEvent.TestStarted(setOf(1L)))
            .reduce(ConnectionsEvent.TestFailed(1L, "测试失败：网络不可用"))
        assertTrue(state.testing.isEmpty())
        assertEquals(ConnectionStatus.UNTESTED, state.cards.single().status)
        assertEquals("测试失败：网络不可用", state.notice)
    }

    @Test
    fun `读库失败进入错误态且不再转圈`() {
        val state = ConnectionsUiState().reduce(ConnectionsEvent.LoadFailed("数据库打不开"))
        assertFalse(state.loading)
        assertEquals("数据库打不开", state.loadError)
        assertTrue(state.cards.isEmpty())
    }

    @Test
    fun `四种协议都能浏览只有认不出的协议标识才标为不可浏览`() {
        // R2：本地 / WebDAV / SFTP / FTP 的后端都已接进 App（M5 之后 SFTP、FTP 不再是"待接入"）
        val all = ProtocolKind.entries.map { kind ->
            record.copy(id = 10L + kind.ordinal, name = kind.id, protocolId = kind.id, protocol = kind)
        }
        val cards = ConnectionsUiState().reduce(ConnectionsEvent.Loaded(all, null, wifi)).cards
        assertEquals(4, cards.size)
        assertTrue("四种协议都应可浏览", cards.all { it.browsable })

        val unknown = record.copy(id = 99L, name = "旧记录", protocolId = "SFTPX", protocol = null)
        val card = ConnectionsUiState().reduce(ConnectionsEvent.Loaded(listOf(unknown), null, wifi)).cards.single()
        assertFalse("协议标识认不出时照实标为不可浏览", card.browsable)
        assertEquals("SFTPX", card.protocolText)
    }
}
