package io.github.gua123.mediagate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * 统一圆角体系（2026-11-18 UI 审计后加）。
 *
 * 审计发现原工程混用 4 / 6 / 8 / 10 / 12 / 20 六种圆角，观感散乱。
 * 收敛成四档，新代码一律走 MaterialTheme.shapes：
 * - small   8dp：缩略图、输入框、小控件
 * - medium 12dp：卡片、面板、播放页 HUD
 * - large  20dp：音频封面、对话框
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MediaGateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
