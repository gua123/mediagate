package io.github.gua123.mediagate.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.feature.settings.R

/**
 * 设置页（**R7** 网络切换策略说明 / **R8** 当前连接入口 / **R6** 凭据口径 / **R18** 后台与保活引导）。
 *
 * 本轮只做只读展示 + 跳转入口：连接编辑在连接管理页（避免两处都能改连接导致状态不一致）。
 *
 * 保活区块（**R18**，M8-A 新增）：显示四项（通知权限 / 省电策略无限制 / 自启动 / 后台弹出界面）的
 * 当前状态并提供一键跳转与"我已开启"确认。状态判定与文案选择都在
 * [KeepAliveGuideRules.evaluate]（纯逻辑、有 JVM 单测），本页只按资源 id 显示——
 * **组合函数零 IO**：查权限、跳系统页、落盘确认都由 :app 的 [KeepAliveHost] 承担。
 *
 * @param connection 当前连接（由 :app 注入）。
 * @param notes 只读说明列表（选路顺序、缓存 60 s、凭据加密口径……）。
 * @param keepAlive 保活引导（R18；:app 把系统权限状态与用户确认合成它）。
 * @param onOpenConnections 跳到连接管理页（导航由 :app 提供）。
 * @param onKeepAliveAction 点某一项的跳转按钮（申请通知权限 / 去省电设置 / 打开应用详情页）。
 * @param onKeepAliveConfirm 勾选/取消"我已开启"（仅自启动、后台弹出界面两项有意义）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connection: SettingsConnectionUi,
    notes: List<SettingsNote>,
    onOpenConnections: () -> Unit,
    modifier: Modifier = Modifier,
    keepAlive: KeepAliveGuide = KeepAliveGuide(),
    onKeepAliveAction: (KeepAliveAction) -> Unit = {},
    onKeepAliveConfirm: (KeepAliveItemKind, Boolean) -> Unit = { _, _ -> },
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_current_connection),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (connection.configured) {
                        Text(
                            text = stringResource(
                                R.string.settings_current_value,
                                connection.name.orEmpty(),
                                connection.protocolText.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        connection.primaryAddress?.let { address ->
                            Text(
                                text = stringResource(R.string.settings_current_primary, address),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        connection.selectionReason?.let { reason ->
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!connection.browsable) {
                            Text(
                                text = stringResource(R.string.settings_current_not_browsable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_current_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenConnections, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_open_connections))
                    }
                }
            }

            // 后台与保活（R18）：四项状态 + 一键跳转 + 手动确认
            KeepAliveCard(
                guide = keepAlive,
                onAction = onKeepAliveAction,
                onConfirm = onKeepAliveConfirm,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_network_policy),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    notes.forEach { note ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(note.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text = note.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 「后台与保活」卡片（**R18**，澎湃 OS）。
 *
 * 每项一行：标题 + 状态 + 说明 + （可跳转时）按钮 + （查不到状态时）「我已开启」勾选。
 */
@Composable
private fun KeepAliveCard(
    guide: KeepAliveGuide,
    onAction: (KeepAliveAction) -> Unit,
    onConfirm: (KeepAliveItemKind, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_keep_alive),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.settings_keep_alive_summary,
                    guide.readyCount,
                    guide.totalCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.settings_keep_alive_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (guide.allReady) {
                Text(
                    text = stringResource(R.string.settings_keep_alive_all_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            guide.items.forEach { item ->
                KeepAliveRow(item = item, onAction = onAction, onConfirm = onConfirm)
            }
        }
    }
}

/** 保活引导的一行（纯展示：状态与文案都来自 [KeepAliveItem]）。 */
@Composable
private fun KeepAliveRow(
    item: KeepAliveItem,
    onAction: (KeepAliveAction) -> Unit,
    onConfirm: (KeepAliveItemKind, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(keepAliveStatusText(item.status)),
                style = MaterialTheme.typography.labelMedium,
                color = keepAliveStatusColor(item.status),
            )
        }
        Text(
            text = stringResource(item.detailRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.jump?.let { jump ->
                OutlinedButton(onClick = { onAction(jump.action) }) {
                    Text(stringResource(jump.labelRes))
                }
            }
            if (item.confirmable) {
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = item.confirmed,
                    onCheckedChange = { checked -> onConfirm(item.kind, checked) },
                )
                Text(
                    text = stringResource(R.string.keep_alive_confirm),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 状态 → 文案资源（R16：界面不拼中文）。 */
private fun keepAliveStatusText(status: KeepAliveStatus): Int = when (status) {
    KeepAliveStatus.READY -> R.string.keep_alive_status_ready
    KeepAliveStatus.ACTION_NEEDED -> R.string.keep_alive_status_action
    KeepAliveStatus.MANUAL_CHECK -> R.string.keep_alive_status_manual
}

/** 状态 → 颜色（已就绪用主题色，待开启用错误色，需手动确认用次要色）。 */
@Composable
private fun keepAliveStatusColor(status: KeepAliveStatus): Color = when (status) {
    KeepAliveStatus.READY -> MaterialTheme.colorScheme.primary
    KeepAliveStatus.ACTION_NEEDED -> MaterialTheme.colorScheme.error
    KeepAliveStatus.MANUAL_CHECK -> MaterialTheme.colorScheme.onSurfaceVariant
}
