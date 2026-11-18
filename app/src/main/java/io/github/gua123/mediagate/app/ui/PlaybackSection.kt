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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gua123.mediagate.feature.player.video.ControlsAutoHide

/**
 * 设置页「播放」区块（2026-10-03 用户要求：「可以设置软件在后台时是否显示画中画」）。
 *
 * **2026-11-18 精简**（用户要求「主要优化设置界面的UI，精简一点，去掉一些不该有的，然后把不常用的
 * 都折叠或者隐藏起来」）：常显只留**日常真会切的一个开关**（切后台自动进入画中画）；
 * 「控制层自动隐藏」是**一次调好就不再动**的秒数档位，收进「更多播放参数」折叠区。
 *
 * **2026-11-18 设置清理：删掉「播放缓冲」档位**。它只喂 Media3 兜底内核
 * （:app AppContainer.kt:1235 的 buildMedia3Engine → ExoPlayerEngine.kt:261 的 setLoadControl），
 * 而视频**只能**走 LibVLC（EngineSwitchPlanner.kt:74 的 availableEngines() 只返回 VLC），
 * 正常播放的缓冲由 VlcCachePolicy 按 LAN 1500 ms / WAN 4000 ms 固定（VlcEngine.kt:428）——
 * 这个档位在默认路径上改了等于没改，属于「已被别的机制覆盖」。
 * [bufferSeconds] / [onBufferSeconds] 两个参数**保留只为不动 :app 的调用点**，页面不再显示它们。
 *
 * 折叠区的默认状态：**用户已经改过默认值就默认展开**——他自己调过的值不该被藏起来，
 * 否则等于设置页"把他改过的项吞了"；没改过则默认收起，一屏只留常用项。
 */
@Composable
fun PlaybackSection(
    pipAutoEnter: Boolean,
    onPipAutoEnter: (Boolean) -> Unit,
    /** 控制层无操作多少秒后隐藏（2026-10-03 用户要求）；0 = 永不自动隐藏。 */
    controlsHideSeconds: Int,
    onControlsHideSeconds: (Int) -> Unit,
    /** **已无界面入口**（2026-11-18 设置清理）：只对 Media3 兜底内核生效，见类注释；参数保留以兼容 :app 调用点。 */
    bufferSeconds: Int,
    /** 同 [bufferSeconds]：不再有界面入口（没有任何地方会回调它）。 */
    onBufferSeconds: (Int) -> Unit,
) {
    val customized = controlsHideSeconds != ControlsAutoHide.DEFAULT_SECONDS
    var advancedExpanded by rememberSaveable { mutableStateOf(customized) }

    Card(modifier = Modifier.fillMaxWidth()) {
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
                            "切到别的应用时弹小窗，通知栏/锁屏仍可暂停与换集。"
                        } else {
                            "切后台只继续后台播放，不弹小窗。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pipAutoEnter, onCheckedChange = onPipAutoEnter)
            }

            // 秒数档位：一次调好的参数，收进折叠区（2026-11-18 精简）
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(
                    if (advancedExpanded) {
                        "收起更多参数"
                    } else {
                        "更多播放参数（控制层自动隐藏）"
                    },
                )
            }
            if (!advancedExpanded) return@Column

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
