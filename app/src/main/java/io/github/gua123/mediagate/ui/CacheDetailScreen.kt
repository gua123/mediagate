package io.github.gua123.mediagate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.app.CacheQueueState
import io.github.gua123.mediagate.app.CacheTask
import io.github.gua123.mediagate.app.CacheTaskStatus
import io.github.gua123.mediagate.app.cacheEntryLine
import io.github.gua123.mediagate.app.cacheMegabytes
import io.github.gua123.mediagate.app.cacheSummaryLine
import io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend

/**
 * **缓存明细**（2026-10-15 用户要求：「关于缓存部分，是否可以增加一个可视化界面，告诉我哪些缓存过，哪些没有」）。
 *
 * 回答两个问题，一行一个文件：
 * - **哪些缓存过**：文件名（老版本留下的无名缓存如实标注）+ 路径（小字，便于对上号）；
 * - **缓存到什么程度**：已缓存多少 MB、几段、占该文件的比例（总大小未知时显示「总大小未知」）。
 *
 * 另外给两条出口：**单条删除**（只清这个文件的段）与**清空全部**。整页零 IO——数据由 :app 传进来。
 */
object CacheDetailRoutes {

    /** 这一页不需要参数（缓存是全局的）。 */
    const val ROUTE = "cache-detail"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheDetailScreen(
    entries: List<SegmentedCacheBackend.CachedFileEntry>,
    cacheLimitBytes: Long,
    onRemove: (SegmentedCacheBackend.CachedFileEntry) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** **缓存队列**（2026-10-15 用户要求「手动批量缓存」之后要能看见进度）。 */
    queue: CacheQueueState = CacheQueueState(),
    onPauseQueue: () -> Unit = {},
    onResumeQueue: () -> Unit = {},
    onCancelTask: (String) -> Unit = {},
    onClearFinishedTasks: () -> Unit = {},
    /** 取消固定（手动缓存的文件默认不被自动清理）。 */
    onUnpin: (SegmentedCacheBackend.CachedFileEntry) -> Unit = {},
    /**
     * **添加文件夹到队列**（2026-10-15 用户要求：「将添加文件夹进队列和清理缓存界面放到一块」）——
     * 缓存相关的动作都收在这一页：加文件夹、看队列、看已缓存、删单条、清全部。
     */
    onOpenFolderPicker: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cache_detail_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.cache_detail_back)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.cache_detail_summary), style = MaterialTheme.typography.titleMedium)
                    Text(cacheSummaryLine(entries), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(
                            R.string.cache_detail_limit,
                            cacheMegabytes(cacheLimitBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenFolderPicker) {
                            Text(stringResource(R.string.cache_detail_add_folder))
                        }
                        if (entries.isNotEmpty()) {
                            TextButton(onClick = onClearAll) { Text(stringResource(R.string.cache_detail_clear_all)) }
                        }
                    }
                }
            }

            // 队列（有任务才显示）：待处理 / 进行中 / 已完成 / 失败，逐条可取消
            if (queue.hasAnything) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.cache_queue_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.cache_queue_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = cacheQueueSummary(queue.pending, queue.done, queue.failed, queue.paused),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = if (queue.paused) onResumeQueue else onPauseQueue) {
                                Text(
                                    stringResource(
                                        if (queue.paused) R.string.cache_queue_resume else R.string.cache_queue_pause,
                                    ),
                                )
                            }
                            TextButton(onClick = onClearFinishedTasks) {
                                Text(stringResource(R.string.cache_queue_clear))
                            }
                        }
                        queue.tasks.forEach { task ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = cacheTaskStatusText(task),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!task.finished) {
                                    TextButton(onClick = { onCancelTask(task.path) }) {
                                        Text(stringResource(R.string.cache_queue_cancel))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.cache_detail_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.dirName }) { entry ->
                        CacheEntryRow(
                            entry = entry,
                            onRemove = { onRemove(entry) },
                            onUnpin = { onUnpin(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** 一条队列任务的状态文案（纯展示，不拼业务逻辑）。 */
@Composable
private fun cacheTaskStatusText(task: CacheTask): String {
    val progress = cacheTaskLine(task.targetBytes, task.cachedBytes, task.progress)
    return when (task.status) {
        CacheTaskStatus.QUEUED -> "排队中"
        CacheTaskStatus.RUNNING -> "正在缓存 · " + progress
        CacheTaskStatus.DONE -> "已完成 · " + progress
        CacheTaskStatus.FAILED -> "失败：" + (task.error ?: "未知原因")
        CacheTaskStatus.CANCELLED -> "已取消"
    }
}

/** 一条缓存记录：名字 + 程度 + 路径 + 删除。 */
@Composable
private fun CacheEntryRow(
    entry: SegmentedCacheBackend.CachedFileEntry,
    onRemove: () -> Unit,
    onUnpin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) { Text(stringResource(R.string.cache_detail_remove)) }
            }
            Text(cacheEntryLine(entry), style = MaterialTheme.typography.bodySmall)
            if (entry.pinned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.cache_pinned_badge),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onUnpin) { Text(stringResource(R.string.cache_pin_release)) }
                }
            }
            entry.path?.let { path ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
