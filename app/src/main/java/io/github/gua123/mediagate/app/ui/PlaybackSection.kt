package io.github.gua123.mediagate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.feature.player.video.ControlsAutoHide

/**
 * 设置页「播放」区块（2026-10-03 用户要求：「可以设置软件在后台时是否显示画中画」）。
 *
 * 只有一项：切后台时是否自动进入画中画。关掉之后按 Home/切后台只是继续后台播放，不会弹小窗。
 */
@Composable
fun PlaybackSection(
    pipAutoEnter: Boolean,
    onPipAutoEnter: (Boolean) -> Unit,
    /** 控制层无操作多少秒后隐藏（2026-10-03 用户要求）；0 = 永不自动隐藏。 */
    controlsHideSeconds: Int,
    onControlsHideSeconds: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "播放", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "切后台时自动进入画中画", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (pipAutoEnter) {
                            "播放中切到别的应用会出现小窗，通知栏/锁屏仍可暂停与换集。"
                        } else {
                            "切后台只继续后台播放，不弹小窗；通知栏/锁屏仍可暂停与换集。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pipAutoEnter, onCheckedChange = onPipAutoEnter)
            }
            // 控制层自动隐藏（**2026-10-03 用户要求**：「无操作多少秒时才隐藏，有操作时不能隐藏」）
            Text(text = "控制层自动隐藏", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "播放中超过这段时间没有操作就把控制层收起来（暂停或正在操作时不会隐藏）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlsAutoHide.SECONDS_CHOICES.forEach { seconds ->
                    FilterChip(
                        selected = controlsHideSeconds == seconds,
                        onClick = { onControlsHideSeconds(seconds) },
                        label = { Text(ControlsAutoHide.label(seconds)) },
                    )
                }
            }
        }
    }
}
