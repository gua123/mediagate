
package io.github.gua123.mediagate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.data.storage.api.CacheTuning

/**
 * 设置页「网络与缓冲」卡片（**2026-10-03 用户要求**：
 * 「我自己调一下并发数，你加到设置里吧」）。
 *
 * 背景：用户实测**公网 WebDAV 播放不到 1 MB/s**（内网正常）。那大概率是链路带宽，
 * 但"单条连接被人为限速"的服务器也存在——与其替他猜，不如把这几个参数摊开，
 * 让他自己按链路特点调，并写清每种调法的适用场景。
 *
 * 四项参数的生效口径：**并发块数 / 块大小 / 预读段数即时生效**；
 * **段大小在下次连接后生效**（它决定磁盘上段文件的切分方式，中途改等于缓存作废）。
 */
@Composable
fun NetworkTuningSection(
    tuning: CacheTuning,
    onReadAheadSegments: (Int) -> Unit,
    onSegmentBytes: (Long) -> Unit,
    /** 缓存总上限（**GB**，用户要求）。 */
    onCacheBytes: (Long) -> Unit,
    /** 当前缓存占用（字节）；null = 还没读到（本地根目录时为 0）。 */
    cacheUsedBytes: Long?,
    onClearCache: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("网络与缓冲", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "缓存按「段」存：正在看的这个文件的段不会被淘汰，所以下次打开同一个文件是直接读本地。" +
                    "「段大小」在下次连接后生效，「预读段数」即时生效。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = if (tuning.readAheadSegments == 0) {
                    "预读段数：0（关：不提前拉）"
                } else {
                    "预读段数：" + tuning.readAheadSegments + "（提前拉后面 " + tuning.readAheadSegments + " 段）"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = tuning.readAheadSegments.toFloat(),
                onValueChange = { onReadAheadSegments(it.toInt()) },
                valueRange = 0f..CacheTuning.MAX_READ_AHEAD.toFloat(),
                steps = CacheTuning.MAX_READ_AHEAD - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "调大 = 播放更不容易卡，但流量与磁盘占用更多（按流量计费时慎调）。",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---------------- 缓存上限（2026-10-03 用户要求：GB 档位 + **可以自己输入一个值**）
            Text(
                text = "缓存上限：**" + labelOfGb(tuning.maxBytes) + "**（下次连接生效）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CacheTuning.CACHE_CHOICES.forEach { bytes ->
                    FilterChip(
                        selected = tuning.maxBytes == bytes,
                        onClick = { onCacheBytes(bytes) },
                        label = { Text(labelOfGb(bytes)) },
                    )
                }
            }
            // 自定义输入：填 GB（可小数），点「应用」生效；非法值给出中文提示
            var customGb by remember { mutableStateOf("") }
            var customError by remember { mutableStateOf<String?>(null) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customGb,
                    onValueChange = {
                        customGb = it
                        customError = null
                    },
                    label = { Text("自定义（GB）") },
                    singleLine = true,
                    isError = customError != null,
                    modifier = Modifier.width(160.dp),
                )
                Button(
                    onClick = {
                        val parsed = customGb.trim().toDoubleOrNull()
                        when {
                            parsed == null -> customError = "请填数字，例如 3 或 3.5"
                            parsed <= 0.0 -> customError = "要大于 0"
                            parsed * 1024 * 1024 * 1024 < CacheTuning.MIN_CACHE_BYTES ->
                                customError = "最小 " + labelOfGb(CacheTuning.MIN_CACHE_BYTES)
                            parsed * 1024 * 1024 * 1024 > CacheTuning.MAX_CACHE_BYTES ->
                                customError = "最大 " + labelOfGb(CacheTuning.MAX_CACHE_BYTES)
                            else -> {
                                onCacheBytes((parsed * 1024 * 1024 * 1024).toLong())
                                customGb = ""
                            }
                        }
                    },
                ) { Text("应用") }
            }
            customError?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                text = "可填 " + labelOfGb(CacheTuning.MIN_CACHE_BYTES) + " ~ " + labelOfGb(CacheTuning.MAX_CACHE_BYTES) + " 之间任意值。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "现在已用 " + (cacheUsedBytes?.let { humanBytes(it) } ?: "读取中…"),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onClearCache) { Text("清空缓存") }

            Text("段大小（缓存最小单位，下次连接生效）", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CacheTuning.SEGMENT_CHOICES.forEach { bytes ->
                    FilterChip(
                        selected = tuning.segmentBytes == bytes,
                        onClick = { onSegmentBytes(bytes) },
                        label = { Text(labelOfBytes(bytes)) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReset) { Text("恢复默认") }
                Text(
                    text = "默认 4 MB 段 · 预读 2 段",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

        }
    }
}

/** 字节数 → 人话（缓存用量显示用；原来在测速文件里，测速删掉后搬到这里）。 */
private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/** 字节数 → GB 口径；不是整 GB 时保留一位小数（自定义值用得上）。 */
private fun labelOfGb(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024 * 1024)
    return if (gb >= 10 || gb == gb.toLong().toDouble()) {
        gb.toLong().toString() + " GB"
    } else {
        "%.1f GB".format(gb)
    }
}

/** 字节数 → 人话（512 KB / 1 MB / 2 MB / 4 MB / 8 MB）。 */
private fun labelOfBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024) {
        (bytes / (1024L * 1024)).toString() + " MB"
    } else {
        (bytes / 1024).toString() + " KB"
    }
