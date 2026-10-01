package io.github.gua123.mediagate.feature.asrmodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 设置页里的「语音识别模型」卡片（**R14**）。
 *
 * 只读状态 + 回调（组合函数零 IO）：档位、下载进度、删除、选中都在
 * [AsrModelViewModel] 里；下载本身由 :app 的 ModelManager/ModelDownloader 完成。
 */
@Composable
fun AsrModelSection(
    state: AsrModelUiState,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
    onMirrorToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.asr_model_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.asr_model_subtitle), style = MaterialTheme.typography.bodySmall)
            Text(
                text = stringResource(R.string.asr_model_used, state.usedMb.toString() + " MB"),
                style = MaterialTheme.typography.bodySmall,
            )

            state.items.forEach { item ->
                AsrModelRow(
                    item = item,
                    anyBusy = state.busy,
                    onDownload = { onDownload(item.id) },
                    onCancel = onCancel,
                    onDelete = { onDelete(item.id) },
                    onSelect = { onSelect(item.id) },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.mirrorEnabled, onCheckedChange = onMirrorToggle)
                Text(stringResource(R.string.asr_model_mirror), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.asr_model_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AsrModelRow(
    item: AsrModelItemUi,
    anyBusy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (item.selected) {
                Text(
                    text = stringResource(R.string.asr_model_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            text = when {
                item.busy -> stringResource(
                    R.string.asr_model_state_downloading,
                    item.percent,
                    formatMb(item.downloading!!.receivedBytes),
                    formatMb(item.downloading!!.totalBytes),
                    if (item.downloading!!.isResuming) stringResource(R.string.asr_model_resuming) else "",
                )

                item.installed -> stringResource(R.string.asr_model_state_installed)
                else -> stringResource(R.string.asr_model_state_missing)
            },
            style = MaterialTheme.typography.bodySmall,
        )

        item.failed?.let { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (item.busy) {
            LinearProgressIndicator(
                progress = { item.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                item.busy -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.asr_model_action_cancel)) }
                item.installed -> {
                    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.asr_model_action_delete)) }
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = !anyBusy,
                    ) { Text(stringResource(R.string.asr_model_action_redownload)) }
                }

                else -> OutlinedButton(
                    onClick = onDownload,
                    enabled = !anyBusy,
                ) { Text(stringResource(R.string.asr_model_action_download)) }
            }
            if (item.installed && !item.selected) {
                TextButton(onClick = onSelect) { Text(stringResource(R.string.asr_model_action_select)) }
            }
        }
    }
}

/** 字节 → 「123 MB」/「456 KB」（与更新模块同一口径，但这里只要粗粒度）。 */
private fun formatMb(bytes: Long): String =
    if (bytes >= 1024L * 1024L) (bytes / (1024L * 1024L)).toString() + " MB" else (bytes / 1024L).toString() + " KB"
