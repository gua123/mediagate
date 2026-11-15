package io.github.gua123.mediagate.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.feature.browser.RootModeKind

/**
 * 首页：先给“继续播放”，再给媒体库入口，最后才是存储/连接设置。
 *
 * 视觉层级：
 * 1. 顶部只保留标题与当前来源；
 * 2. 有播放记录时第一张卡是“继续播放”；
 * 3. 媒体入口改成整行列表（手机上比三张窄卡片更好点、说明也放得下）；
 * 4. 存储与连接收成一张卡；远端生效时本地目录设置默认折叠。
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
    lastPlayedPath: String? = null,
    onOpenLastPlayed: (String) -> Unit = {},
    onOpenConnections: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = root.remoteLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        lastPlayedPath?.takeIf { it.isNotBlank() }?.let { path ->
            Spacer(Modifier.height(20.dp))
            ContinueCard(path = path, onClick = { onOpenLastPlayed(path) })
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.home_section_media),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            MediaEntryRow(
                icon = Icons.Default.VideoLibrary,
                title = stringResource(R.string.home_entry_video),
                description = stringResource(R.string.home_entry_video_desc),
                onClick = { onOpenKind(MediaKind.VIDEO) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            MediaEntryRow(
                icon = Icons.Default.MusicNote,
                title = stringResource(R.string.home_entry_audio),
                description = stringResource(R.string.home_entry_audio_desc),
                onClick = { onOpenKind(MediaKind.AUDIO) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            MediaEntryRow(
                icon = Icons.Default.PhotoLibrary,
                title = stringResource(R.string.home_entry_image),
                description = stringResource(R.string.home_entry_image_desc),
                onClick = { onOpenKind(MediaKind.IMAGE) },
            )
        }

        Spacer(Modifier.height(24.dp))
        StorageCard(
            root = root,
            onPickSafDirectory = onPickSafDirectory,
            onUseAllFilesRoot = onUseAllFilesRoot,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onClearRoot = onClearRoot,
            onOpenConnections = onOpenConnections,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueCard(path: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_continue_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.home_continue_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaEntryRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageCard(
    root: HomeRootUi,
    onPickSafDirectory: () -> Unit,
    onUseAllFilesRoot: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onClearRoot: () -> Unit,
    onOpenConnections: () -> Unit,
) {
    var localExpanded by rememberSaveable { mutableStateOf(!root.remoteActive || !root.configured) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_storage_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            stringResource(
                                if (root.remoteActive) R.string.home_storage_chip_remote
                                else R.string.home_storage_chip_local,
                            ),
                        )
                    },
                )
            }

            if (root.remoteActive) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = root.remoteLabel.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.home_storage_remote_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenConnections, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_storage_manage))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { localExpanded = !localExpanded }) {
                    Text(
                        stringResource(
                            if (localExpanded) R.string.home_storage_local_hide
                            else R.string.home_storage_local_show,
                        ),
                    )
                }
            }

            if (localExpanded) {
                if (root.remoteActive) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_storage_local_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (root.configured) {
                    Text(
                        text = stringResource(R.string.home_root_mode_label_standby, modeLabel(root.mode)),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    Text(
                        text = stringResource(R.string.home_root_guide_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onPickSafDirectory, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_action_pick_saf))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = if (root.allFilesGranted) onUseAllFilesRoot else onRequestAllFilesAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (root.allFilesGranted) R.string.home_action_use_all_files
                            else R.string.home_action_grant_all_files,
                        ),
                    )
                }
                if (root.configured) {
                    TextButton(onClick = onClearRoot) {
                        Text(stringResource(R.string.home_action_clear_root))
                    }
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: RootModeKind): String = stringResource(
    when (mode) {
        RootModeKind.SAF -> R.string.home_root_mode_saf
        RootModeKind.ALL_FILES -> R.string.home_root_mode_all_files
        RootModeKind.NONE -> R.string.home_root_mode_none
    },
)
