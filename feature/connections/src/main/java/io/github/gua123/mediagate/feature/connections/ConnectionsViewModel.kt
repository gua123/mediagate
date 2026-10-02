package io.github.gua123.mediagate.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.network.ConnectionTester
import io.github.gua123.mediagate.core.network.ProbeCache
import io.github.gua123.mediagate.core.network.ProbeClock
import io.github.gua123.mediagate.core.network.ProtocolKind

/**
 * 连接管理页的 ViewModel（**R8** 多连接与连通性测试 / **R7** 选路 / **R6** 凭据加密）。
 *
 * 职责边界（与 :feature:browser 同一套路）：
 * - 所有状态迁移都走 [ConnectionsUiState.reduce]（纯函数，JVM 单测覆盖）；
 * - 所有 IO（读库 / 写库 / 加密 / 探测）都在本类里，且只跑在 [io] 上；
 * - 组合函数只读 [state]，不做任何 IO。
 *
 * 三个关键口径：
 * 1. **测试结果缓存 60 s**（plan 4.5）：同一连接 60 s 内重复点"测试"直接复用结果并在界面标注
 *    "缓存"；用户点"重新测试"（force）则绕过缓存。缓存用注入的 [clock]，
 *    网络切换（[io.github.gua123.mediagate.core.network.NetworkContext] 的传输类型/SSID/网段变化）时整个清空；
 * 2. **测试全部**：所有连接并行测（每连接内部地址也并行），最后按
 *    [ConnectionTestAggregator] 排序汇总（失败的排前面）；
 * 3. **密码只进不出**：草稿里的明文密码立刻加密进 `secretRef`；读取时只有 :app 构造后端才解密，
 *    界面永远拿不到明文（[ConnectionDraft.hasStoredSecret] 只表示"已保存过"）。
 *
 * @param environment 宿主能力（数据库 / 加密 / 当前连接 / 网络现场）。
 * @param repository 连接持久化（默认按环境里的数据库与加密器构造）。
 * @param clock 时钟：用于 60 s 缓存与"最近检测时间"（墙上时间，界面要显示给人看）。
 * @param io 调度器（单测注入）。
 */
class ConnectionsViewModel(
    private val environment: ConnectionsEnvironment,
    private val repository: ConnectionRepository = ConnectionRepository(environment.database, environment.cipher),
    private val clock: ProbeClock = ProbeClock { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionsUiState())

    /** 页面唯一状态源。 */
    val state: StateFlow<ConnectionsUiState> = _state.asStateFlow()

    /** 60 s 结果缓存（plan 4.5）；key = 连接 id + 地址指纹 + 协议。 */
    private val cache = ProbeCache<ConnectionTestUi>(clock)

    init {
        viewModelScope.launch {
            combine(
                repository.connections,
                environment.currentConnectionId,
                environment.networkContext,
            ) { records, currentId, network -> Triple(records, currentId, network) }
                .catch { t -> _state.update { it.reduce(ConnectionsEvent.LoadFailed(friendly(t))) } }
                .collect { (records, currentId, network) ->
                    if (_state.value.network.switchKey != network.switchKey) cache.invalidate()
                    _state.update { it.reduce(ConnectionsEvent.Loaded(records, currentId, network)) }
                }
        }
    }

    // ------------------------------------------------------------------ 编辑器（R8）

    /** 新建连接（默认协议 WebDAV，带一个空地址）。 */
    fun openCreate(protocol: ProtocolKind = ProtocolKind.WEBDAV) {
        _state.update { it.reduce(ConnectionsEvent.EditorOpened(defaultDraft(protocol))) }
    }

    /** 编辑已有连接（把记录转成草稿；密码字段留空 = 不修改）。 */
    fun openEdit(id: Long) {
        viewModelScope.launch {
            val record = withContext(io) { repository.byId(id) } ?: return@launch
            _state.update { it.reduce(ConnectionsEvent.EditorOpened(record.toDraft())) }
        }
    }

    /** 关闭编辑器（放弃修改）。 */
    fun closeEditor() {
        _state.update { it.reduce(ConnectionsEvent.EditorClosed) }
    }

    /** 草稿整体替换（文本框改动）。 */
    fun updateDraft(draft: ConnectionDraft) {
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft)) }
    }

    /** 切换协议（顺带把地址的方案/端口换成该协议默认值）。 */
    fun changeProtocol(protocol: ProtocolKind) {
        val draft = _state.value.editor ?: return
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft.withProtocol(protocol))) }
    }

    /** 改地址（按下标）。 */
    fun updateAddress(index: Int, address: AddressDraft) {
        val draft = _state.value.editor ?: return
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft.withAddress(index, address))) }
    }

    /** 加一个地址（R7 多地址）。 */
    fun addAddress() {
        val draft = _state.value.editor ?: return
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft.plusAddress())) }
    }

    /** 删一个地址。 */
    fun removeAddress(index: Int) {
        val draft = _state.value.editor ?: return
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft.minusAddress(index))) }
    }

    /** 加一条选路规则（R7）。 */
    fun addRule(rule: RuleDraft) {
        val draft = _state.value.editor ?: return
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft.plusRule(rule))) }
    }

    /** 删一条选路规则。 */
    fun removeRule(index: Int) {
        val draft = _state.value.editor ?: return
        _state.update { it.reduce(ConnectionsEvent.EditorChanged(draft.minusRule(index))) }
    }

    /**
     * 保存（R8/R6）。
     *
     * 先本地校验（[ConnectionFormValidator]），不通过就把红字写进状态；
     * 通过才落库——密码在这一步**立即加密**（[ConnectionRepository.save]）。
     */
    fun save() {
        val draft = _state.value.editor ?: return
        val validation = ConnectionFormValidator.validate(draft)
        if (!validation.valid) {
            _state.update { it.reduce(ConnectionsEvent.EditorInvalid(validation)) }
            return
        }
        viewModelScope.launch {
            val created = draft.id == null
            runCatching { withContext(io) { repository.save(draft) } }
                .onSuccess { _state.update { it.reduce(ConnectionsEvent.Saved(draft.name.trim(), created)) } }
                .onFailure { t -> _state.update { it.reduce(ConnectionsEvent.Notice("保存失败：" + friendly(t))) } }
        }
    }

    /**
     * 编辑器里「测试一下」（**R8**）：**用草稿直接测，不落库**。
     *
     * 为什么值得单独做：真机上出现过"填完保存 → 点开浏览 → 认证失败"，而用户无从判断
     * 到底是密码打错了、端口/协议不对，还是服务器在拒人。草稿级测试让用户**在保存之前**
     * 就拿到结论；密码框留空时沿用已保存的密文（与保存口径一致），所以"改没改密码"也测得出来。
     *
     * 校验不通过时不打网络：先把红字写回状态（与保存同一条校验）。
     */
    fun testDraft() {
        val draft = _state.value.editor ?: return
        if (_state.value.draftTesting) return
        val validation = ConnectionFormValidator.validate(draft)
        if (!validation.valid) {
            _state.update { it.reduce(ConnectionsEvent.EditorInvalid(validation)) }
            _state.update { it.reduce(ConnectionsEvent.DraftTestFinished("先补全上面的必填项再测")) }
            return
        }
        _state.update { it.reduce(ConnectionsEvent.DraftTestStarted) }
        viewModelScope.launch {
            val result = try {
                val record = DraftTestSupport.recordOf(draft)
                // 与保存同一口径：新输入的密码优先，否则用已保存的密文（没存过就是 null）
                val typed = draft.password.takeIf { it.isNotEmpty() }
                val secret = typed ?: withContext(io) { draft.id?.let { repository.revealSecret(it) } }
                val source = when {
                    typed != null -> PasswordSource.NEW_INPUT
                    secret != null -> PasswordSource.STORED
                    else -> PasswordSource.NONE
                }
                val tester = ConnectionTester(
                    handshakes = StorageConnectionHandshakes.forConnection(record, secret),
                    io = io,
                )
                val results = tester.testAll(record.protocol ?: ProtocolKind.LOCAL, record.selectableAddresses())
                // 一句话给人看 + 技术详情可复制（用户问过「在哪里查看详情」）
                DraftTestSupport.summarize(results, source) to DraftTestSupport.detailOf(results, source)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ("测试失败：" + friendly(t)) to null
            }
            _state.update { it.reduce(ConnectionsEvent.DraftTestFinished(result.first, result.second)) }
        }
    }

    // ------------------------------------------------------------------ 删除 / 当前连接（R8）

    /** 请求删除（先弹确认框，避免误删）。 */
    fun requestDelete(id: Long) {
        _state.update { it.reduce(ConnectionsEvent.DeletePending(id)) }
    }

    /** 取消删除。 */
    fun cancelDelete() {
        _state.update { it.reduce(ConnectionsEvent.DeletePending(null)) }
    }

    /** 确认删除（连带地址与规则；若是当前连接，一并清除）。 */
    fun confirmDelete() {
        val id = _state.value.pendingDeleteId ?: return
        viewModelScope.launch {
            runCatching { withContext(io) { repository.delete(id) } }
                .onSuccess {
                    cache.invalidate()
                    if (environment.currentConnectionId.value == id) environment.setCurrentConnection(null)
                    _state.update { it.reduce(ConnectionsEvent.DeletePending(null)) }
                    _state.update { it.reduce(ConnectionsEvent.Notice("已删除连接")) }
                }
                .onFailure { t -> _state.update { it.reduce(ConnectionsEvent.Notice("删除失败：" + friendly(t))) } }
        }
    }

    /**
     * 设为当前连接（**R8** 菜单/长按里的动作）。
     *
     * :app 收到后会切换浏览器/播放器拿到的后端：LOCAL → 本地根目录后端，
     * WEBDAV / SFTP / FTP → 各自的远端后端（R2 四协议都接进了 App，见 :app 的 applyCurrentConnection）。
     * 只有数据库里的协议标识认不出（[ConnectionRecord.browsable] 为 false）时才拒绝切换并如实说明。
     */
    fun setCurrent(id: Long) {
        val record = _state.value.records.firstOrNull { it.id == id } ?: return
        if (!record.browsable) {
            _state.update {
                it.reduce(
                    ConnectionsEvent.Notice(
                        record.protocolText + "：数据库里的协议标识认不出，不能设为当前连接（请编辑该连接重新选协议）",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            runCatching { environment.setCurrentConnection(id) }
                .onSuccess {
                    _state.update { it.reduce(ConnectionsEvent.CurrentChanged(id)) }
                    _state.update { it.reduce(ConnectionsEvent.Notice("已设为当前连接：" + record.name)) }
                }
                .onFailure { t -> _state.update { it.reduce(ConnectionsEvent.Notice("切换失败：" + friendly(t))) } }
        }
    }

    /**
     * 清除当前连接（**改用本地目录**）。
     *
     * 真机反馈：加了连接、设成当前之后，本地目录就"进不去"了——因为远端优先（publishRoot），
     * 而界面上原先**没有任何**"改回本地目录"的出口。这里补上，并给一句明确提示。
     */
    fun clearCurrent() {
        viewModelScope.launch {
            environment.setCurrentConnection(null)
            _state.update { it.reduce(ConnectionsEvent.CurrentChanged(null)) }
            _state.update { it.reduce(ConnectionsEvent.Notice("已改用本地目录（当前连接已取消）")) }
        }
    }

    // ------------------------------------------------------------------ 连通性测试（R8 / R7）

    /**
     * 测试单个连接的全部地址（R8：逐地址三段耗时 + 错误分类）。
     *
     * @param force true = 忽略 60 s 缓存强制重测（界面上的"重新测试"）。
     */
    fun test(id: Long, force: Boolean = false) {
        viewModelScope.launch {
            val record = _state.value.records.firstOrNull { it.id == id }
                ?: withContext(io) { repository.byId(id) }
                ?: return@launch
            _state.update { it.reduce(ConnectionsEvent.TestStarted(setOf(id))) }
            val outcome = runCatching { runTest(record, force) }
            outcome
                .onSuccess { result -> _state.update { it.reduce(ConnectionsEvent.TestFinished(result)) } }
                .onFailure { t -> _state.update { it.reduce(ConnectionsEvent.TestFailed(id, "测试失败：" + friendly(t))) } }
        }
    }

    /** 测试全部（R8）：并行跑完所有连接，再按失败优先排序汇总。 */
    fun testAll(force: Boolean = false) {
        viewModelScope.launch {
            val records = _state.value.records
            if (records.isEmpty()) {
                _state.update { it.reduce(ConnectionsEvent.Notice("还没有连接可以测试")) }
                return@launch
            }
            _state.update { it.reduce(ConnectionsEvent.TestStarted(records.map { it.id }.toSet())) }
            val results = coroutineScope {
                records.map { record ->
                    async { runCatching { runTest(record, force) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            if (results.isEmpty()) {
                _state.update { it.reduce(ConnectionsEvent.TestFailed(records.first().id, "测试全部失败：请检查网络")) }
            } else {
                _state.update { it.reduce(ConnectionsEvent.TestAllFinished(results)) }
            }
        }
    }

    /** 关掉一次性提示（Snackbar 消费完调用）。 */
    fun dismissNotice() {
        _state.update { it.reduce(ConnectionsEvent.Notice(null)) }
    }

    /**
     * 真正跑一次测试：查缓存 → 建临时握手 → 并行测所有地址 → 写缓存 → 记录"最近检测"。
     *
     * 前两段（DNS/TCP）由 [io.github.gua123.mediagate.core.network.ConnectionTester] 负责，
     * 第三段（协议握手）按协议注入：LOCAL 查目录、WEBDAV 发 PROPFIND、SFTP 走 banner+认证、FTP 走 220+登录+PASV。
     */
    private suspend fun runTest(record: ConnectionRecord, force: Boolean): ConnectionTestUi {
        val key = cacheKey(record)
        if (!force) {
            cache.get(key)?.let { return it.copy(fromCache = true) }
        }
        val secret = withContext(io) { repository.revealSecret(record.id) }
        val protocol = record.protocol ?: ProtocolKind.LOCAL
        val tester = ConnectionTester(
            // 四种协议都走真握手：SFTP / FTP 由 StorageConnectionHandshakes 补上，LOCAL/WEBDAV 语义不变
            handshakes = StorageConnectionHandshakes.forConnection(record, secret),
            io = io,
        )
        val addresses = record.selectableAddresses()
        val raw = tester.testAll(protocol, addresses)
        val ui = ConnectionTestUi(
            connectionId = record.id,
            testedAtMs = clock.nowMs(),
            addresses = raw.map { it.toUi() },
            fromCache = false,
        )
        cache.put(key, ui)
        val firstOk = raw.firstOrNull { it.ok }?.address?.id
        withContext(io) { repository.markChecked(record.id, firstOk, ui.testedAtMs) }
        return ui
    }

    /** 缓存 key：连接 id + 地址指纹 + 协议（地址改了就是另一轮）。 */
    private fun cacheKey(record: ConnectionRecord): String =
        record.id.toString() + "#" + record.protocolId + "#" +
            record.addresses.joinToString(",") { it.id.toString() + ":" + it.host + ":" + it.port }

    /** 异常 → 中文提示（R16：不把英文堆栈或库的英文 message 甩给用户，见 [ErrorText]）。 */
    private fun friendly(t: Throwable): String = ErrorText.of(t, "详情见诊断日志")
}

/** 新建草稿（R8）：按协议给默认的传输方案与端口。 */
internal fun defaultDraft(protocol: ProtocolKind): ConnectionDraft {
    val defaults = ProtocolDefaults.of(protocol)
    return ConnectionDraft(
        protocol = protocol,
        basePath = "/",
        addresses = listOf(
            AddressDraft(
                label = io.github.gua123.mediagate.core.network.AddressLabel.LAN,
                scheme = defaults.scheme,
                port = defaults.port,
            ),
        ),
    )
}

/** 记录 → 草稿（R8：编辑时密码不回显，只标记"已保存过"）。 */
internal fun ConnectionRecord.toDraft(): ConnectionDraft {
    val resolved = protocol ?: ProtocolKind.LOCAL
    val defaults = ProtocolDefaults.of(resolved)
    val addressDrafts = addresses.map {
        AddressDraft(
            id = it.id,
            label = it.label,
            scheme = it.scheme,
            host = it.host,
            port = it.port.toString(),
            priority = it.priority.toString(),
        )
    }.ifEmpty {
        listOf(AddressDraft(scheme = defaults.scheme, port = defaults.port))
    }
    return ConnectionDraft(
        id = id,
        name = name,
        protocol = resolved,
        basePath = basePath,
        username = username.orEmpty(),
        password = "",
        hasStoredSecret = hasSecret,
        allowInsecureHttp = options.allowInsecureHttp,
        addresses = addressDrafts,
        rules = rules.map {
            RuleDraft(
                id = it.id,
                transport = it.transport,
                ssidPattern = it.ssidPattern.orEmpty(),
                localSubnet = it.localSubnet.orEmpty(),
                prefer = it.prefer,
            )
        },
    )
}
