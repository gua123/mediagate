package io.github.gua123.mediagate.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.feature.browser.RootModeKind

/**
 * 首页（R1 三类媒体入口 / R12 根目录引导 / R16 全中文）。
 *
 * 三张入口卡片分别跳到浏览页并带上类型过滤（视频 / 音乐 / 图片，路由常量来自 :feature:browser）；
 * 「当前根目录」卡片展示模式 + 路径，并给出两种模式的入口与清除操作。
 *
 * **首次进入未选择根目录时，本页显示引导卡片，不是空白也不会崩**（R12）。
 *
 * 所有回调都由 :app 注入（目录选择器要 Activity 的 ActivityResultLauncher，
 * 不能放在 library 模块里）：
 *
 * @param onOpenKind 点入口卡片（带 [MediaKind] 过滤跳浏览页）。
 * @param onPickSafDirectory 拉起系统目录选择器（SAF 授权）。
 * @param onUseAllFilesRoot 已授权时切到全盘访问模式。
 * @param onRequestAllFilesAccess 未授权时跳系统「所有文件访问」设置页。
 * @param onClearRoot 清除当前根目录（回到未选择状态）。
 */
@Composable
fun HomeScreen(
    root: HomeRootUi,
    onOpenKind: (MediaKind) -> Unit,
    onPickSafDirectory: () -> Unit,
    onUseAllFilesRoot: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onClearRoot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.home_section_media),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaEntryCard(
                icon = Icons.Default.VideoLibrary,
                title = stringResource(R.string.home_entry_video),
                description = stringResource(R.string.home_entry_video_desc),
                onClick = { onOpenKind(MediaKind.VIDEO) },
                modifier = Modifier.weight(1f),
            )
            MediaEntryCard(
                icon = Icons.Default.MusicNote,
                title = stringResource(R.string.home_entry_audio),
                description = stringResource(R.string.home_entry_audio_desc),
                onClick = { onOpenKind(MediaKind.AUDIO) },
                modifier = Modifier.weight(1f),
            )
            MediaEntryCard(
                icon = Icons.Default.PhotoLibrary,
                title = stringResource(R.string.home_entry_image),
                description = stringResource(R.string.home_entry_image_desc),
                onClick = { onOpenKind(MediaKind.IMAGE) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        RootCard(
            root = root,
            onPickSafDirectory = onPickSafDirectory,
            onUseAllFilesRoot = onUseAllFilesRoot,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onClearRoot = onClearRoot,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 一张媒体入口卡片（图标 + 名称 + 一句话说明）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaEntryCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 「当前根目录」卡片（R12）。
 *
 * 未选择时它就是引导卡片（引导文案 + 两个模式入口）；已选择时展示模式与路径，并允许随时切换。
 */
@Composable
private fun RootCard(
    root: HomeRootUi,
    onPickSafDirectory: () -> Unit,
    onUseAllFilesRoot: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onClearRoot: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_root_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 远端优先（R8）：有当前连接时先说清楚"现在用的是远端"，否则用户会以为在看本地目录
            if (root.remoteActive) {
                Text(
                    text = stringResource(R.string.home_root_remote_active, root.remoteLabel.orEmpty()),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (root.localStandby) {
                            R.string.home_root_remote_local_standby
                        } else {
                            R.string.home_root_remote_no_local
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (root.configured) {
                Text(
                    text = stringResource(
                        if (root.remoteActive) R.string.home_root_mode_label_standby else R.string.home_root_mode_label,
                        modeLabel(root.mode),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_root_path_label, root.displayPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.home_root_guide_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_root_guide_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onPickSafDirectory, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_action_pick_saf))
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 两种模式都要有入口：已授权 → 直接切；未授权 → 跳系统设置页
            OutlinedButton(
                onClick = if (root.allFilesGranted) onUseAllFilesRoot else onRequestAllFilesAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (root.allFilesGranted) {
                            R.string.home_action_use_all_files
                        } else {
                            R.string.home_action_grant_all_files
                        },
                    ),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (root.allFilesGranted) {
                        R.string.home_all_files_granted
                    } else {
                        R.string.home_all_files_not_granted
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (root.configured) {
                TextButton(onClick = onClearRoot) {
                    Text(stringResource(R.string.home_action_clear_root))
                }
            }
        }
    }
}

/** 模式 → 中文名（R16：文案全在 res 里）。 */
@Composable
private fun modeLabel(mode: RootModeKind): String = stringResource(
    when (mode) {
        RootModeKind.SAF -> R.string.home_root_mode_saf
        RootModeKind.ALL_FILES -> R.string.home_root_mode_all_files
        RootModeKind.NONE -> R.string.home_root_mode_none
    },
)
