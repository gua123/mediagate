package io.github.gua123.mediagate.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.AsrItemState

/**
 * 批量字幕任务中心（**M7-B / R19**）。
 *
 * 页面结构（自上而下）：
 * 1. **来源**：当前连接/目录 + 「选择来源」+「上一级」；
 * 2. **选择**：多选文件 / 整个文件夹、含子文件夹、跳过已有字幕、全选/清空、逐项勾选；
 * 3. **队列**：总进度条 + 汇总 + 当前文件 + 暂停/继续/取消全部/一键重试全部失败项；
 * 4. **逐项**：每条的进度条、状态、失败原因，以及上移/下移/重试/取消。
 *
 * 组合函数零 IO：所有副作用都通过 [TasksViewModel] 发起；状态只有一个来源（state）。
 *
 * @param onOpenSource 去「选择来源」（:app 负责导航到浏览页或连接页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onOpenSource: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val environment = LocalTasksEnvironment.current
    val viewModel: TasksViewModel = viewModel { TasksViewModel(environment) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        val notice = state.notice
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.tasks_source_up))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SourceCard(state = state, onOpenSource = onOpenSource, onUp = { viewModel.goUp() }) }
            item { ErrorCard(message = state.errorMessage) }
            item {
                PickerCard(
                    state = state,
                    onKind = { viewModel.setSourceKind(it) },
                    onRecursive = { viewModel.setRecursive(it) },
                    onSkipExisting = { viewModel.setSkipExisting(it) },
                    onSelectAll = { viewModel.selectAll() },
                    onClear = { viewModel.clearSelection() },
                    onEnqueue = { viewModel.enqueue() },
                )
            }

            if (state.loading) {
                item {
                    Text(
                        text = stringResource(R.string.tasks_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tasks_entries_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.entries, key = { it.path }) { entry ->
                    EntryRow(
                        entry = entry,
                        kind = state.sourceKind,
                        onToggle = { viewModel.toggle(entry.path) },
                        onOpen = { if (entry.isDirectory) viewModel.enter(entry.path, entry.name) },
                    )
                }
            }

            item { QueueHeaderCard(state = state, viewModel = viewModel) }

            if (state.queue.items.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tasks_queue_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.queue.items, key = { it.id }) { item ->
                    QueueRow(item = item, viewModel = viewModel, canReorder = state.canReorder)
                }
            }

            if (state.yielding) {
                item {
                    Text(
                        text = stringResource(R.string.tasks_queue_yielding),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 来源卡片：当前连接/目录 + 选择来源 + 上一级。 */
@Composable
private fun SourceCard(state: TasksUiState, onOpenSource: () -> Unit, onUp: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.tasks_source_title), style = MaterialTheme.typography.titleMedium)
            val label = state.dirLabel.ifBlank { state.rootLabel }
            Text(
                text = if (label.isBlank()) stringResource(R.string.tasks_source_empty) else label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.currentDir.ifEmpty { "/" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSource) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.tasks_source_change))
                }
                OutlinedButton(onClick = onUp, enabled = state.currentDir.isNotEmpty()) {
                    Text(stringResource(R.string.tasks_source_up))
                }
            }
        }
    }
}

/** 选择区：模式、递归、跳过已有字幕、全选/清空、入队。 */
@Composable
private fun PickerCard(
    state: TasksUiState,
    onKind: (TaskSourceKind) -> Unit,
    onRecursive: (Boolean) -> Unit,
    onSkipExisting: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onEnqueue: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = stringResource(R.string.tasks_mode_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskSourceKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.sourceKind == kind,
                        onClick = { onKind(kind) },
                        label = {
                            Text(
                                when (kind) {
                                    TaskSourceKind.FILES -> stringResource(R.string.tasks_mode_files)
                                    TaskSourceKind.FOLDER -> stringResource(R.string.tasks_mode_folder)
                                },
                            )
                        },
                    )
                }
            }
            SwitchRow(
                label = stringResource(R.string.tasks_recursive),
                checked = state.recursive,
                enabled = state.sourceKind == TaskSourceKind.FOLDER,
                onCheckedChange = onRecursive,
            )
            SwitchRow(
                label = stringResource(R.string.tasks_skip_existing),
                checked = state.skipExisting,
                onCheckedChange = onSkipExisting,
            )
            Text(
                text = stringResource(R.string.tasks_selected_count, state.selectedEntries.size, state.videoCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.tasks_select_all)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.tasks_select_none)) }
            }
            // 灰按钮必须解释原因（真机反馈："勾了 1 项却点不动"）：模型没装 / 一项没勾，都在这里说清楚
            state.enqueueHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onEnqueue, enabled = state.canEnqueue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tasks_enqueue))
            }
        }
    }
}

/** 开关一行（标签 + Switch）。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 目录里的一个文件/文件夹。 */
@Composable
private fun EntryRow(
    entry: TaskEntryUi,
    kind: TaskSourceKind,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val selectable = entry.selectable(kind)
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = entry.selected, onCheckedChange = { onToggle() }, enabled = selectable)
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.FolderOpen else Icons.Default.Movie,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.hasSubtitle) {
                    Text(
                        text = stringResource(R.string.tasks_has_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.isDirectory) {
                TextButton(onClick = onOpen) { Text("进入") }
            }
        }
    }
}

/** 队列头部：总进度 + 汇总 + 操作按钮。 */
@Composable
private fun QueueHeaderCard(state: TasksUiState, viewModel: TasksViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.tasks_queue_title), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { state.queue.totalProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = state.summaryText + " · " + state.queueStateText + " · " + state.progressPercent + "%",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.currentFileName?.let { name ->
                Text(
                    text = stringResource(R.string.tasks_queue_current, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.pause() }, enabled = state.canPause) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tasks_action_pause))
                }
                OutlinedButton(onClick = { viewModel.resume() }, enabled = state.canResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tasks_action_resume))
                }
                OutlinedButton(onClick = { viewModel.cancelAll() }, enabled = state.canCancelAll) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tasks_action_cancel_all))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.retryAllFailed() }, enabled = state.hasRetryable) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tasks_action_retry_all))
                }
                OutlinedButton(onClick = { viewModel.clearFinished() }, enabled = state.hasFinished) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    // 带上数量（真机反馈：按钮在队列卡片上方，滚到列表就看不着了；写清"会清掉几项"更好找）
                    Text(
                        if (state.finishedCount > 0) {
                            stringResource(R.string.tasks_action_clear_count, state.finishedCount)
                        } else {
                            stringResource(R.string.tasks_action_clear)
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tasks_concurrency, state.queue.concurrency),
                    style = MaterialTheme.typography.bodySmall,
                )
                FilterChip(
                    selected = state.queue.concurrency == 1,
                    onClick = { viewModel.setConcurrency(1) },
                    label = { Text("1") },
                )
                FilterChip(
                    selected = state.queue.concurrency == 2,
                    onClick = { viewModel.setConcurrency(2) },
                    label = { Text("2") },
                )
            }
            if (state.queue.items.isNotEmpty()) HorizontalDivider()
        }
    }
}

/**
 * 队列条目的状态文案（**2026-10-03 真机截图**：刚入队时一直显示"识别中 0%"，看着像卡死）。
 *
 * 正在跑但进度还是 0 → 说明还在准备（加载模型 / 解出第一段音频），如实写出来。
 */
private fun queueStateLabel(item: AsrItem): String = when {
    item.state == AsrItemState.RUNNING && item.progress.percent == 0 -> "准备中（加载模型…）"
    item.state == AsrItemState.WRITING -> "正在写字幕文件"
    else -> item.state.zhText
}

/** 队列里的一条任务。 */
@Composable
private fun QueueRow(item: AsrItem, viewModel: TasksViewModel, canReorder: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { item.progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    // 刚开始跑、进度还是 0 的时候，多半在加载模型（small 档模型 400+ MB）——
                    // 只显示"识别中 0%"会被当成卡死（2026-10-03 真机截图）
                    text = queueStateLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.tasks_item_progress, item.progress.percent),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.outputPath?.let { path ->
                Text(
                    text = stringResource(R.string.tasks_item_output, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.skipReason?.let { reason ->
                Text(
                    text = stringResource(R.string.tasks_item_skip, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.failure?.let { failure ->
                Text(
                    text = stringResource(R.string.tasks_item_error, failure.zhText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.retryCount > 0) {
                Text(
                    text = stringResource(R.string.tasks_item_retry_count, item.retryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { viewModel.moveUp(item.id) }, enabled = canReorder && item.state == AsrItemState.QUEUED) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.tasks_action_up))
                }
                IconButton(onClick = { viewModel.moveDown(item.id) }, enabled = canReorder && item.state == AsrItemState.QUEUED) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.tasks_action_down))
                }
                IconButton(
                    onClick = { viewModel.retry(item.id) },
                    enabled = item.state == AsrItemState.FAILED ||
                        item.state == AsrItemState.INTERRUPTED ||
                        item.state == AsrItemState.CANCELLED,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.tasks_action_retry))
                }
                // 没结束的：✕ = 取消（留在列表里可重试）；已结束的：🗑 = 从列表和库里移除
                // （2026-10-03 用户问「任务列表的已取消能不能去掉」——以前这里的 ✕ 只把任务变成"已取消"，
                //   既不消失也没提示，用户自然以为去不掉）
                if (item.state.isTerminal) {
                    IconButton(onClick = { viewModel.remove(item.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.tasks_action_remove))
                    }
                } else {
                    IconButton(onClick = { viewModel.cancel(item.id) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tasks_action_cancel))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (item.state.isActive) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 列目录失败时的中文错误卡片。 */
@Composable
private fun ErrorCard(message: String?) {
    if (message.isNullOrBlank()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        }
    }
}

