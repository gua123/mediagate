package io.github.gua123.mediagate.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 设置页里的「应用更新」卡片（**R20**）。
 *
 * 只读状态 + 回调，不碰环境与网络（组合函数零 IO，与其他页面同一套路）。
 * 文案全部走资源（R16 简体中文单语）。
 */
@Composable
fun UpdateSection(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.update_current_version,
                    state.currentVersionName,
                    state.currentVersionCode,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.update_hint_github),
                style = MaterialTheme.typography.bodySmall,
            )
            // 2026-10-03 用户实况：为更新必须开 VPN，而 VPN 会让 WebDAV 打成 503 —— 把这条经验写在卡片上
            Text(
                text = stringResource(R.string.update_hint_vpn_split),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val status = state.status) {
                    is UpdateStatus.Available -> {
                        Button(onClick = onDownload) { Text(stringResource(R.string.update_action_download)) }
                        Text(manifestNotes(status.manifest), style = MaterialTheme.typography.bodySmall)
                    }

                    is UpdateStatus.Downloading -> {
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.update_action_cancel)) }
                    }

                    is UpdateStatus.Downloaded -> {
                        Button(onClick = onInstall) { Text(stringResource(R.string.update_action_install)) }
                    }

                    else -> {
                        Button(onClick = onCheck, enabled = !state.busy) {
                            Text(
                                stringResource(
                                    if (state.status is UpdateStatus.UpToDate) {
                                        R.string.update_action_recheck
                                    } else {
                                        R.string.update_action_check
                                    },
                                ),
                            )
                        }
                    }
                }
                TextButton(onClick = onOpenReleases) { Text(stringResource(R.string.update_action_releases)) }
            }

            if (state.status is UpdateStatus.Available) {
                Text(
                    text = (state.status as UpdateStatus.Available).manifest.notes,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun statusText(state: UpdateUiState): String = when (val status = state.status) {
    UpdateStatus.Idle -> ""
    UpdateStatus.Checking -> stringResource(R.string.update_status_checking)
    is UpdateStatus.UpToDate -> stringResource(R.string.update_status_uptodate)
    is UpdateStatus.Available -> stringResource(
        R.string.update_status_available,
        status.manifest.versionName,
        formatBytes(status.manifest.sizeBytes),
    )

    is UpdateStatus.Downloading -> stringResource(
        R.string.update_status_downloading,
        status.progress.percent,
        formatBytes(status.progress.receivedBytes).ifEmpty { "0 KB" },
        formatBytes(status.progress.totalBytes).ifEmpty { stringResource(R.string.update_size_unknown) },
        if (status.progress.isResuming) stringResource(R.string.update_status_resuming) else "",
    )

    is UpdateStatus.Downloaded -> stringResource(R.string.update_status_ready)
    is UpdateStatus.Failed -> status.message
}

/** 更新说明（清单里的 notes；空则不显示）。 */
private fun manifestNotes(manifest: UpdateManifest): String = manifest.notes
