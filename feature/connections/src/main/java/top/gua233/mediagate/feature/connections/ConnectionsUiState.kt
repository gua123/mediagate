package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.network.AddressSelector
import io.github.gua123.mediagate.core.network.NetworkContext
import io.github.gua123.mediagate.core.network.SelectionResult

/** 连接的检测状态（**R8** 列表里的状态徽标）。 */
enum class ConnectionStatus(val zhText: String) {
    /** 从未测试过。 */
    UNTESTED("未测试"),

    /** 正在测试（转圈）。 */
    TESTING("测试中"),

    /** 全部地址通过。 */
    OK("正常"),

    /** 部分地址通过。 */
    PARTIAL("部分可用"),

    /** 没有一个地址通过。 */
    FAILED("不可用"),
}

/**
 * 列表里一张连接卡片的状态（**R8**：名称 + 协议 + 地址数 + 最近检测时间 + 状态徽标；
 * **R7**：当前网络下的首选地址与判定依据）。
 */
data class ConnectionCardUi(
    val id: Long,
    val name: String,
    val protocolText: String,
    val browsable: Boolean,
    val addresses: List<AddressRecord>,
    val rules: List<RuleRecord>,
    val lastCheckedAt: Long?,
    val status: ConnectionStatus,
    val current: Boolean,
    val selection: SelectionResult?,
    val test: ConnectionTestUi?,
) {

    val addressCount: Int get() = addresses.size

    /** 选路一行中文（R7）：「首选 局域网 · http://192.168.1.10:8080（命中规则…）」。 */
    val selectionText: String?
        get() = selection?.let { result ->
            val primary = result.primary?.let { it.display } ?: return@let result.explanation
            "首选 " + primary + " · " + result.explanation
        }

    companion object {
        /** 由记录 + 运行时状态拼出卡片（纯函数，单测覆盖状态归约）。 */
        fun of(
            record: ConnectionRecord,
            currentId: Long?,
            testing: Set<Long>,
            result: ConnectionTestUi?,
            network: NetworkContext,
        ): ConnectionCardUi = ConnectionCardUi(
            id = record.id,
            name = record.name,
            protocolText = record.protocolText,
            browsable = record.browsable,
            addresses = record.addresses,
            rules = record.rules,
            lastCheckedAt = record.lastCheckedAt,
            status = statusOf(record.id, testing, result),
            current = currentId == record.id,
            selection = AddressSelector.select(record.selectableAddresses(), network, record.networkRules()),
            test = result,
        )

        /** 状态归约：测试中 > 有结果（全通/部分/全败）> 未测试。 */
        fun statusOf(connectionId: Long, testing: Set<Long>, result: ConnectionTestUi?): ConnectionStatus = when {
            connectionId in testing -> ConnectionStatus.TESTING
            result == null -> ConnectionStatus.UNTESTED
            result.addresses.isEmpty() -> ConnectionStatus.UNTESTED
            result.failCount == 0 -> ConnectionStatus.OK
            result.okCount == 0 -> ConnectionStatus.FAILED
            else -> ConnectionStatus.PARTIAL
        }
    }
}

/**
 * 连接管理页的全部状态（**R8** / **R7** / **R6**）。
 *
 * 设计口径与 :feature:browser 一致：**状态迁移是纯函数**（[reduce]），IO 全在 ViewModel 里，
 * 组合函数只读 [cards] 与 [editor]。
 */
data class ConnectionsUiState(
    val loading: Boolean = true,
    val records: List<ConnectionRecord> = emptyList(),
    val currentConnectionId: Long? = null,
    val network: NetworkContext = NetworkContext(),
    val testing: Set<Long> = emptySet(),
    val results: Map<Long, ConnectionTestUi> = emptyMap(),
    val editor: ConnectionDraft? = null,
    val editorErrors: ConnectionFormResult = ConnectionFormResult(),
    val pendingDeleteId: Long? = null,
    val notice: String? = null,
    val summary: TestAllSummary? = null,
    val loadError: String? = null,
) {

    /** 列表（按 id 升序，与数据库顺序一致）。 */
    val cards: List<ConnectionCardUi>
        get() = records.map { ConnectionCardUi.of(it, currentConnectionId, testing, results[it.id], network) }

    /** 是否正在「测试全部」。 */
    val testingAll: Boolean get() = testing.size > 1

    /** 取一张卡片。 */
    fun card(id: Long): ConnectionCardUi? = cards.firstOrNull { it.id == id }

    /** 归约（纯函数，无 IO、无副作用）。 */
    fun reduce(event: ConnectionsEvent): ConnectionsUiState = when (event) {
        is ConnectionsEvent.Loaded -> {
            // 网络真的换了（传输类型/SSID/网段/在线状态变了）→ 结果作废（plan 4.5）
            val switched = network.switchKey != event.network.switchKey
            copy(
                loading = false,
                records = event.records,
                currentConnectionId = event.currentConnectionId,
                network = event.network,
                loadError = null,
                // 已删除的连接要把测试结果一起清掉，避免留下幽灵数据
                results = if (switched) {
                    emptyMap()
                } else {
                    results.filterKeys { id -> event.records.any { it.id == id } }
                },
                summary = if (switched) null else summary,
                notice = if (switched && results.isNotEmpty()) NETWORK_SWITCH_NOTICE else notice,
            )
        }

        is ConnectionsEvent.LoadFailed -> copy(loading = false, loadError = event.message)

        is ConnectionsEvent.NetworkChanged -> copy(network = event.network)

        is ConnectionsEvent.TestStarted -> copy(testing = testing + event.ids, notice = null)

        is ConnectionsEvent.TestFinished -> copy(
            testing = testing - event.result.connectionId,
            results = results + (event.result.connectionId to event.result),
            summary = null,
        )

        is ConnectionsEvent.TestAllFinished -> copy(
            testing = emptySet(),
            results = results + event.results.associateBy { it.connectionId },
            summary = ConnectionTestAggregator.summarize(event.results),
        )

        is ConnectionsEvent.TestFailed -> copy(
            testing = testing - event.connectionId,
            notice = event.message,
        )

        is ConnectionsEvent.EditorOpened -> copy(editor = event.draft, editorErrors = ConnectionFormResult())

        is ConnectionsEvent.EditorChanged -> copy(editor = event.draft, editorErrors = ConnectionFormResult())

        is ConnectionsEvent.EditorInvalid -> copy(editorErrors = event.errors)

        ConnectionsEvent.EditorClosed -> copy(editor = null, editorErrors = ConnectionFormResult())

        is ConnectionsEvent.DeletePending -> copy(pendingDeleteId = event.id)

        is ConnectionsEvent.CurrentChanged -> copy(currentConnectionId = event.id)

        is ConnectionsEvent.Notice -> copy(notice = event.message)

        is ConnectionsEvent.Saved -> copy(
            editor = null,
            editorErrors = ConnectionFormResult(),
            notice = if (event.created) "已保存连接：" + event.name else "已更新连接：" + event.name,
        )
    }
}

/** 网络切换后的中文提示（R7：结果缓存 60 s，网络变化即失效）。 */
internal const val NETWORK_SWITCH_NOTICE: String = "网络已切换：之前的测试结果已失效，请重新测试"

/** 状态归约的事件（**R8**）。 */
sealed interface ConnectionsEvent {

    /** 数据库快照 + 当前连接 + 网络现场。 */
    data class Loaded(
        val records: List<ConnectionRecord>,
        val currentConnectionId: Long?,
        val network: NetworkContext,
    ) : ConnectionsEvent

    /** 读库失败。 */
    data class LoadFailed(val message: String) : ConnectionsEvent

    /** 网络变化（NetworkCallback；revision 变了）。 */
    data class NetworkChanged(val network: NetworkContext) : ConnectionsEvent

    /** 开始测试（单个或多个连接）。 */
    data class TestStarted(val ids: Set<Long>) : ConnectionsEvent

    /** 单个连接测完。 */
    data class TestFinished(val result: ConnectionTestUi) : ConnectionsEvent

    /** 「测试全部」测完。 */
    data class TestAllFinished(val results: List<ConnectionTestUi>) : ConnectionsEvent

    /** 单个连接测失败（异常兜底）。 */
    data class TestFailed(val connectionId: Long, val message: String) : ConnectionsEvent

    /** 打开编辑器（新建或编辑）。 */
    data class EditorOpened(val draft: ConnectionDraft) : ConnectionsEvent

    /** 编辑器内容变化。 */
    data class EditorChanged(val draft: ConnectionDraft) : ConnectionsEvent

    /** 保存时校验不通过。 */
    data class EditorInvalid(val errors: ConnectionFormResult) : ConnectionsEvent

    /** 关闭编辑器。 */
    data object EditorClosed : ConnectionsEvent

    /** 请求删除（弹确认框）。 */
    data class DeletePending(val id: Long?) : ConnectionsEvent

    /** 当前连接变了。 */
    data class CurrentChanged(val id: Long?) : ConnectionsEvent

    /** 一次性提示。 */
    data class Notice(val message: String?) : ConnectionsEvent

    /** 保存成功。 */
    data class Saved(val name: String, val created: Boolean) : ConnectionsEvent
}
