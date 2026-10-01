package io.github.gua123.mediagate.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.NetworkCapability
import io.github.gua123.mediagate.core.network.ProtocolKind

/**
 * 连接管理页（**R8** 多连接记录 + 连通性测试；**R7** 多地址与选路；**R6** 凭据只存密文）。
 *
 * 页面结构：
 * - 顶部：当前网络现场（R7 的判定输入）+「测试全部」；
 * - 列表：每张卡片显示 名称 / 协议 / 地址数 / 状态徽标 / 最近检测时间 / 当前网络下的首选地址与判定依据，
 *   展开后逐地址显示三段耗时与错误分类；
 * - 每张卡片：测试、重新测试、设为当前连接（也在长按菜单里）、编辑、删除；
 * - 右下角：新建连接；编辑器里协议 LOCAL/WEBDAV/SFTP/FTP 全可选，但只有 LOCAL/WEBDAV 能浏览
 *   （SFTP/FTP 会明确提示"只能测试连通性"，不谎报可用）。
 *
 * 组合函数零 IO：所有副作用都通过 [ConnectionsViewModel] 发起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(modifier: Modifier = Modifier) {
    val environment = LocalConnectionsEnvironment.current
    val viewModel: ConnectionsViewModel = viewModel { ConnectionsViewModel(environment) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    if (state.editor != null) {
        ConnectionEditorScreen(
            state = state,
            onDraftChange = viewModel::updateDraft,
            onProtocolChange = viewModel::changeProtocol,
            onAddressChange = viewModel::updateAddress,
            onAddAddress = viewModel::addAddress,
            onRemoveAddress = viewModel::removeAddress,
            onAddRule = viewModel::addRule,
            onRemoveRule = viewModel::removeRule,
            onSave = viewModel::save,
            onCancel = viewModel::closeEditor,
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                actions = {
                    IconButton(onClick = { viewModel.testAll(force = true) }, enabled = !state.testingAll) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = stringResource(R.string.connections_action_test_all))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openCreate() }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.connections_action_new))
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.testingAll) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NetworkCard(state) }
                state.summary?.let { summary ->
                    item { SummaryCard(summary) }
                }
                if (state.loadError != null) {
                    item { HintCard(state.loadError!!) }
                }
                if (state.records.isEmpty() && !state.loading) {
                    item { HintCard(stringResource(R.string.connections_empty)) }
                }
                items(state.cards, key = { it.id }) { card ->
                    ConnectionCard(
                        card = card,
                        onTest = { viewModel.test(card.id, force = false) },
                        onRetest = { viewModel.test(card.id, force = true) },
                        onSetCurrent = { viewModel.setCurrent(card.id) },
                        onEdit = { viewModel.openEdit(card.id) },
                        onDelete = { viewModel.requestDelete(card.id) },
                    )
                }
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    state.pendingDeleteId?.let { id ->
        val name = state.records.firstOrNull { it.id == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.connections_delete_title)) },
            text = { Text(stringResource(R.string.connections_delete_message, name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.connections_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.connections_cancel))
                }
            },
        )
    }
}

/** 当前网络现场卡片（R7 判定顺序的第一环：网络能力）。 */
@Composable
private fun NetworkCard(state: ConnectionsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.connections_network_title), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (state.network.online) state.network.display else stringResource(R.string.connections_network_offline),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.connections_network_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「测试全部」汇总卡片（R8）。 */
@Composable
private fun SummaryCard(summary: TestAllSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (summary.allOk) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.connections_summary_title), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(summary.summaryLine, style = MaterialTheme.typography.bodyMedium)
            if (!summary.allOk) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.connections_summary_failed_first),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 一句中文提示卡片。 */
@Composable
private fun HintCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text = text, modifier = Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 一张连接卡片（R8 列表项 + 逐地址测试结果）。 */
@Composable
private fun ConnectionCard(
    card: ConnectionCardUi,
    onTest: () -> Unit,
    onRetest: () -> Unit,
    onSetCurrent: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val now = remember(card.test) { System.currentTimeMillis() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.connections_card_meta,
                            card.protocolText,
                            card.addressCount,
                            ConnectionsFormat.badgeText(card.status, card.lastCheckedAt, now),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.current) {
                    AssistChip(onClick = onSetCurrent, label = { Text(stringResource(R.string.connections_current)) })
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.connections_action_edit))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_action_set_current)) },
                            onClick = { menuOpen = false; onSetCurrent() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_action_edit)) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_action_delete)) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            if (!card.browsable) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.connections_not_browsable, card.protocolText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            card.selectionText?.let { line ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            card.addresses.forEach { address ->
                Text(
                    text = address.display,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.addresses.isEmpty()) {
                Text(
                    text = stringResource(R.string.connections_no_address),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            card.test?.let { test ->
                Spacer(modifier = Modifier.height(8.dp))
                if (test.fromCache) {
                    Text(
                        text = stringResource(R.string.connections_result_cached),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                test.addresses.forEach { AddressResultRow(it) }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTest, enabled = card.status != ConnectionStatus.TESTING) {
                    Text(stringResource(R.string.connections_action_test))
                }
                OutlinedButton(onClick = onRetest, enabled = card.status != ConnectionStatus.TESTING) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.connections_action_retest))
                }
                IconButton(onClick = onSetCurrent) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.connections_action_set_current))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.connections_action_delete))
                }
            }
        }
    }
}

/** 一个地址的三段耗时与错误分类（R8 的核心展示）。 */
@Composable
private fun AddressResultRow(result: AddressTestUi) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (result.ok) "✓" else "✗",
                color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = result.display + " · " + result.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = result.timingLine + " · 合计 " + result.totalMs + " ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.message?.takeIf { !result.ok }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        result.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 连接编辑器（R8）：名称 / 协议 / 多地址 / 账号密码 / 根路径 / WebDAV 明文开关 / 选路规则。
 *
 * 密码框只进不出：已保存过就提示"已保存，留空表示不修改"，输入的新密码保存时立即加密（R6）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditorScreen(
    state: ConnectionsUiState,
    onDraftChange: (ConnectionDraft) -> Unit,
    onProtocolChange: (ProtocolKind) -> Unit,
    onAddressChange: (Int, AddressDraft) -> Unit,
    onAddAddress: () -> Unit,
    onRemoveAddress: (Int) -> Unit,
    onAddRule: (RuleDraft) -> Unit,
    onRemoveRule: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.editor ?: return
    val errors = state.editorErrors

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (draft.id == null) R.string.connections_editor_new else R.string.connections_editor_edit,
                        ),
                    )
                },
                actions = {
                    TextButton(onClick = onSave) { Text(stringResource(R.string.connections_save)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.connections_cancel)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.connections_field_name)) },
                isError = errors.errorOf(FormField.NAME) != null,
                supportingText = errors.errorOf(FormField.NAME)?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))
            ProtocolPicker(current = draft.protocol, onPick = onProtocolChange)

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.basePath,
                onValueChange = { onDraftChange(draft.copy(basePath = it)) },
                label = {
                    Text(
                        stringResource(
                            if (draft.protocol == ProtocolKind.LOCAL) {
                                R.string.connections_field_local_path
                            } else {
                                R.string.connections_field_base_path
                            },
                        ),
                    )
                },
                isError = errors.errorOf(FormField.BASE_PATH) != null,
                supportingText = errors.errorOf(FormField.BASE_PATH)?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.connections_section_addresses), style = MaterialTheme.typography.titleSmall)
            errors.errorOf(FormField.ADDRESSES)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            draft.addresses.forEachIndexed { index, address ->
                AddressEditor(
                    index = index,
                    protocol = draft.protocol,
                    address = address,
                    errors = errors.addressErrors.getOrNull(index).orEmpty(),
                    onChange = { onAddressChange(index, it) },
                    onRemove = { onRemoveAddress(index) },
                    removable = draft.addresses.size > 1,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onAddAddress) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.connections_action_add_address))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.connections_section_credentials), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.username,
                onValueChange = { onDraftChange(draft.copy(username = it)) },
                label = { Text(stringResource(R.string.connections_field_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.password,
                onValueChange = { onDraftChange(draft.copy(password = it)) },
                label = { Text(stringResource(R.string.connections_field_password)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (draft.hasStoredSecret) {
                                R.string.connections_password_stored
                            } else {
                                R.string.connections_password_empty
                            },
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                isError = errors.errorOf(FormField.PASSWORD) != null,
                supportingText = {
                    Text(
                        errors.errorOf(FormField.PASSWORD)
                            ?: stringResource(R.string.connections_password_hint),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.protocol == ProtocolKind.WEBDAV) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = draft.allowInsecureHttp,
                        onCheckedChange = { onDraftChange(draft.copy(allowInsecureHttp = it)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.connections_field_allow_http), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.connections_field_allow_http_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            RulesSection(
                rules = draft.rules,
                onAdd = onAddRule,
                onRemove = onRemoveRule,
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connections_save))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 协议选择（LOCAL / WEBDAV / SFTP / FTP，R2）。 */
@Composable
private fun ProtocolPicker(current: ProtocolKind, onPick: (ProtocolKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.connections_field_protocol), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current.zhText)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProtocolKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        // 四种协议（本地 / WebDAV / SFTP / FTP）的后端都已接进 App，不再标注"待接入"
                        text = { Text(kind.zhText) },
                        onClick = { expanded = false; onPick(kind) },
                    )
                }
            }
        }
    }
}

/** 一个地址的编辑块（R7 多地址）。 */
@Composable
private fun AddressEditor(
    index: Int,
    protocol: ProtocolKind,
    address: AddressDraft,
    errors: Map<FormField, String>,
    onChange: (AddressDraft) -> Unit,
    onRemove: () -> Unit,
    removable: Boolean,
) {
    var labelOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { labelOpen = true }) { Text(address.label.zhText) }
                    DropdownMenu(expanded = labelOpen, onDismissRequest = { labelOpen = false }) {
                        AddressLabel.entries.forEach { label ->
                            DropdownMenuItem(
                                text = { Text(label.zhText) },
                                onClick = { labelOpen = false; onChange(address.copy(label = label)) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (removable) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.connections_action_remove_address))
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (ProtocolDefaults.needsHostAndPort(protocol)) {
                OutlinedTextField(
                    value = address.host,
                    onValueChange = { onChange(address.copy(host = it)) },
                    label = { Text(stringResource(R.string.connections_field_host)) },
                    isError = errors[FormField.HOST] != null,
                    supportingText = errors[FormField.HOST]?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = address.scheme,
                        onValueChange = { onChange(address.copy(scheme = it)) },
                        label = { Text(stringResource(R.string.connections_field_scheme)) },
                        isError = errors[FormField.SCHEME] != null,
                        supportingText = errors[FormField.SCHEME]?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = address.port,
                        onValueChange = { onChange(address.copy(port = it)) },
                        label = { Text(stringResource(R.string.connections_field_port)) },
                        isError = errors[FormField.PORT] != null,
                        supportingText = errors[FormField.PORT]?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                OutlinedTextField(
                    value = address.host,
                    onValueChange = { onChange(address.copy(host = it)) },
                    label = { Text(stringResource(R.string.connections_field_address_path)) },
                    isError = errors[FormField.HOST] != null,
                    supportingText = errors[FormField.HOST]?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = address.priority,
                onValueChange = { onChange(address.copy(priority = it)) },
                label = { Text(stringResource(R.string.connections_field_priority)) },
                supportingText = { Text(stringResource(R.string.connections_field_priority_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.connections_address_index, index + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 选路规则区（R7）：列出已有规则 + 通过对话框新增。 */
@Composable
private fun RulesSection(
    rules: List<RuleDraft>,
    onAdd: (RuleDraft) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    Text(stringResource(R.string.connections_section_rules), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.connections_rules_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    rules.forEachIndexed { index, rule ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rule.toNetworkRule().display,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.connections_action_remove_rule))
            }
        }
    }
    OutlinedButton(onClick = { dialogOpen = true }) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.connections_action_add_rule))
    }
    if (dialogOpen) {
        RuleDialog(
            onDismiss = { dialogOpen = false },
            onConfirm = { rule ->
                dialogOpen = false
                onAdd(rule)
            },
        )
    }
}

/** 新增选路规则的对话框（R7：传输类型 / SSID / 本机网段 + 偏好 LAN 或 WAN）。 */
@Composable
private fun RuleDialog(onDismiss: () -> Unit, onConfirm: (RuleDraft) -> Unit) {
    var preferLan by remember { mutableStateOf(true) }
    var transport by remember { mutableStateOf<NetworkCapability?>(null) }
    var ssid by remember { mutableStateOf("") }
    var subnet by remember { mutableStateOf("") }
    var transportOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_rule_dialog_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = preferLan, onCheckedChange = { preferLan = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (preferLan) R.string.connections_rule_prefer_lan else R.string.connections_rule_prefer_wan,
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { transportOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(transport?.zhText ?: stringResource(R.string.connections_rule_any_transport))
                    }
                    DropdownMenu(expanded = transportOpen, onDismissRequest = { transportOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_rule_any_transport)) },
                            onClick = { transportOpen = false; transport = null },
                        )
                        NetworkCapability.entries.forEach { capability ->
                            DropdownMenuItem(
                                text = { Text(capability.zhText) },
                                onClick = { transportOpen = false; transport = capability },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text(stringResource(R.string.connections_rule_ssid)) },
                    supportingText = { Text(stringResource(R.string.connections_rule_ssid_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = subnet,
                    onValueChange = { subnet = it },
                    label = { Text(stringResource(R.string.connections_rule_subnet)) },
                    supportingText = { Text(stringResource(R.string.connections_rule_subnet_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        RuleDraft(
                            transport = transport,
                            ssidPattern = ssid.trim(),
                            localSubnet = subnet.trim(),
                            prefer = if (preferLan) AddressLabel.LAN else AddressLabel.WAN,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.connections_rule_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.connections_cancel)) }
        },
    )
}
