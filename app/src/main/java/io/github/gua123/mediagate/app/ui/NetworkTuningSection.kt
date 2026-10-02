
package io.github.gua123.mediagate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
    onReset: () -> Unit,
    /** 测速：要测的文件路径（相对连接根目录）。 */
    speedPath: String,
    onSpeedPathChange: (String) -> Unit,
    speedRunning: Boolean,
    /** 测速结果文本（可整段选中复制）。 */
    speedResult: String?,
    onRunSpeedTest: () -> Unit,
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

            // ---------------- 测速（2026-10-03）：调参有没有用，量一下就知道
            Text("测速", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "走真实的播放路径（含分段缓存与上面的并发设置）读 8 MB。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = speedPath,
                onValueChange = onSpeedPathChange,
                label = { Text("远端文件路径（相对连接根目录）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunSpeedTest,
                    enabled = !speedRunning && speedPath.isNotBlank(),
                ) {
                    Text(if (speedRunning) "测速中…" else "开始测速")
                }
            }
            speedResult?.let { text ->
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
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
