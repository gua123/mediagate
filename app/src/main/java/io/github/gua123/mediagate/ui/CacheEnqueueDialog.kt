package io.github.gua123.mediagate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.app.CachePickResult
import io.github.gua123.mediagate.app.cacheMegabytes

/**
 * 「预约缓存」的界面状态（2026-10-15 用户要求：「增加手动批量多个文件的缓存方式，
 * 可以让我不打开对应文件夹就可以缓存视频」）。
 */
internal sealed interface CachePickUi {

    /** 正在统计文件夹里的视频（大目录要一会儿）。 */
    data class Scanning(val name: String) : CachePickUi

    /** 统计好了，等用户确认。 */
    data class Ready(val name: String, val picked: CachePickResult) : CachePickUi

    /** 统计失败（列目录出错等）。 */
    data class Failed(val message: String) : CachePickUi
}

/**
 * 长按一行之后的确认框：告诉用户**将要缓存什么、有多少**，再让他点确认——
 * 批量缓存是"会占空间、会跑流量"的动作，不能长按即执行。
 */
@Composable
internal fun CacheEnqueueDialog(
    state: CachePickUi,
    onConfirm: (CachePickResult) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is CachePickUi.Scanning -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.cache_pick_scanning_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.cache_pick_scanning_body, state.name))
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cache_pick_cancel)) }
            },
        )

        is CachePickUi.Ready -> {
            val picked = state.picked
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.cache_pick_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when {
                                picked.notVideo -> stringResource(R.string.cache_pick_not_video, state.name)
                                picked.entries.isEmpty() -> stringResource(R.string.cache_pick_empty, state.name)
                                else -> stringResource(
                                    R.string.cache_pick_body,
                                    state.name,
                                    picked.entries.size,
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (picked.truncated) {
                            Text(
                                text = stringResource(R.string.cache_pick_truncated),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (picked.entries.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.cache_pick_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = picked.entries.take(3).joinToString("\n") { it.second } +
                                    if (picked.entries.size > 3) "\n…" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (picked.entries.isNotEmpty()) {
                        TextButton(onClick = { onConfirm(picked) }) {
                            Text(stringResource(R.string.cache_pick_confirm))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cache_pick_cancel)) }
                },
            )
        }

        is CachePickUi.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.cache_pick_failed_title)) },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cache_pick_cancel)) }
            },
        )
    }
}

/** 队列摘要文案（缓存明细页头部用；纯函数便于单测）。 */
internal fun cacheQueueSummary(pending: Int, done: Int, failed: Int, paused: Boolean): String = when {
    pending == 0 && done == 0 && failed == 0 -> "缓存队列是空的"
    else -> buildString {
        append("队列：")
        append(if (paused) "已暂停" else "进行中")
        append(" · 待处理 ").append(pending)
        append(" · 已完成 ").append(done)
        if (failed > 0) append(" · 失败 ").append(failed)
    }
}

/**
 * 一条任务的进度文案：**相对"要缓存的那一段"**（预约缓存只温开头，不是整份），
 * 目标未知时只说"已缓存多少"（不编百分比）。
 */
internal fun cacheTaskLine(targetBytes: Long?, cachedBytes: Long, progress: Float?): String {
    val cached = cacheMegabytes(cachedBytes)
    return when {
        targetBytes != null && targetBytes > 0L -> {
            val percent = ((progress ?: 0f) * 100).toInt().coerceIn(0, 100)
            cached + " / " + cacheMegabytes(targetBytes) + "（" + percent + "%）"
        }

        else -> "已缓存 " + cached
    }
}
