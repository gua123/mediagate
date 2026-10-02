
package io.github.gua123.mediagate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
    onParallelChunks: (Int) -> Unit,
    onChunkBytes: (Long) -> Unit,
    onReadAheadSegments: (Int) -> Unit,
    onSegmentBytes: (Long) -> Unit,
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
                text = "公网慢的时候可以调这几项试试；局域网一般不用动。" +
                    "「段大小」在下次连接后生效，其余三项都是即时生效。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = if (tuning.parallelChunks <= 1) {
                    "并发块数：1（不并发，只开一条连接）"
                } else {
                    "并发块数：" + tuning.parallelChunks + "（同时下 " + tuning.parallelChunks + " 块）"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = tuning.parallelChunks.toFloat(),
                onValueChange = { onParallelChunks(it.toInt()) },
                valueRange = CacheTuning.MIN_PARALLEL_CHUNKS.toFloat()..CacheTuning.MAX_PARALLEL_CHUNKS.toFloat(),
                steps = CacheTuning.MAX_PARALLEL_CHUNKS - CacheTuning.MIN_PARALLEL_CHUNKS - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "服务器对单条连接限速时，调大有效；整条链路带宽不够时，调大也没用。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("块大小（每块多大）", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CacheTuning.CHUNK_CHOICES.forEach { bytes ->
                    FilterChip(
                        selected = tuning.chunkBytes == bytes,
                        onClick = { onChunkBytes(bytes) },
                        label = { Text(labelOfBytes(bytes)) },
                    )
                }
            }

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
                    text = "默认 4 MB 段 · 1 MB 块 · 4 并发 · 预读 2 段",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 字节数 → 人话（512 KB / 1 MB / 2 MB / 4 MB / 8 MB）。 */
private fun labelOfBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024) {
        (bytes / (1024L * 1024)).toString() + " MB"
    } else {
        (bytes / 1024).toString() + " KB"
    }
