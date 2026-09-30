package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import io.github.gua123.mediagate.feature.player.video.VideoPlayerPreferences
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.subtitle.SubtitleStyle

/** 视频播放页偏好的 DataStore（App 私有；进程内单例由 [preferencesDataStore] 委托保证）。 */
private val Context.videoPlayerDataStore by preferencesDataStore(name = "video_player_settings")

/**
 * 视频播放页偏好的 DataStore 实现（M2-B，R9 内核 / R10 解码档位 / M7-A，R14 字幕）。
 *
 * 记三件事：**上次用的内核**（Media3 / LibVLC）、**解码档位**（自动 / 强制软解 / 强制硬解）、
 * **字幕设置**（开关 / 样式 / 时间轴微调）。M7-A 是**在同一个偏好文件上做加法**：
 * 老键位（engine_kind / decoder_mode）一个没动，旧版本升级上来读到的仍是原来的值。
 *
 * 与 [PlaybackProgressSettings] 同一风格：值以字符串存，读的时候按枚举名解析，
 * 解析不出来（旧版本残留、手改文件）一律回默认值，绝不因为一个坏值让播放页崩掉；
 * 字幕样式读出来还会过一遍 [SubtitleStyle.clamped]（越界值夹回合法区间）。
 *
 * @param context 应用上下文。
 * @param scope 用于 [stateIn] 的应用级作用域（由 [AppContainer] 提供，随进程存活）。
 */
class VideoPlayerPreferencesSettings(
    private val context: Context,
    scope: CoroutineScope,
) : VideoPlayerPreferences {

    /** 上次选择的内核（R9）；没有记录时是 Media3（默认内核）。 */
    override val engine: StateFlow<EngineKind> = context.videoPlayerDataStore.data
        .map { preferences -> parseEngine(preferences[KEY_ENGINE]) }
        .stateIn(scope, SharingStarted.Eagerly, EngineKind.MEDIA3)

    /** 上次选择的解码档位（R10）；没有记录时是 AUTO_HW（硬解优先，用户拍板的默认档）。 */
    override val decoderMode: StateFlow<DecoderMode> = context.videoPlayerDataStore.data
        .map { preferences -> parseDecoderMode(preferences[KEY_DECODER]) }
        .stateIn(scope, SharingStarted.Eagerly, DecoderMode.AUTO_HW)

    override suspend fun setEngine(kind: EngineKind) {
        context.videoPlayerDataStore.edit { preferences -> preferences[KEY_ENGINE] = kind.name }
    }

    override suspend fun setDecoderMode(mode: DecoderMode) {
        context.videoPlayerDataStore.edit { preferences -> preferences[KEY_DECODER] = mode.name }
    }

    // ------------------------------------------------------------ 字幕（M7-A，R14）

    /** 字幕总开关（R14）；没有记录时为关。 */
    override val subtitleEnabled: StateFlow<Boolean> = context.videoPlayerDataStore.data
        .map { preferences -> preferences[KEY_SUBTITLE_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 字幕样式（R14）；没有记录时给默认值，读出来一律 [SubtitleStyle.clamped]。 */
    override val subtitleStyle: StateFlow<SubtitleStyle> = context.videoPlayerDataStore.data
        .map { preferences ->
            SubtitleStyle(
                fontSizeSp = preferences[KEY_SUBTITLE_FONT_SIZE] ?: SubtitleStyle.DEFAULT_FONT_SIZE_SP,
                textColorArgb = preferences[KEY_SUBTITLE_TEXT_COLOR] ?: SubtitleStyle.DEFAULT_TEXT_COLOR,
                outlineWidthDp = preferences[KEY_SUBTITLE_OUTLINE_WIDTH] ?: SubtitleStyle.DEFAULT_OUTLINE_WIDTH_DP,
                outlineColorArgb = preferences[KEY_SUBTITLE_OUTLINE_COLOR] ?: SubtitleStyle.DEFAULT_OUTLINE_COLOR,
                bottomMarginDp = preferences[KEY_SUBTITLE_BOTTOM_MARGIN] ?: SubtitleStyle.DEFAULT_BOTTOM_MARGIN_DP,
                bold = preferences[KEY_SUBTITLE_BOLD] ?: false,
                italic = preferences[KEY_SUBTITLE_ITALIC] ?: false,
            ).clamped()
        }
        .stateIn(scope, SharingStarted.Eagerly, SubtitleStyle.DEFAULT)

    /** 字幕时间轴微调（R14）；没有记录时为 0。 */
    override val subtitleOffsetMs: StateFlow<Long> = context.videoPlayerDataStore.data
        .map { preferences -> preferences[KEY_SUBTITLE_OFFSET_MS] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    override suspend fun setSubtitleEnabled(enabled: Boolean) {
        context.videoPlayerDataStore.edit { preferences -> preferences[KEY_SUBTITLE_ENABLED] = enabled }
    }

    override suspend fun setSubtitleStyle(style: SubtitleStyle) {
        val value = style.clamped()
        context.videoPlayerDataStore.edit { preferences ->
            preferences[KEY_SUBTITLE_FONT_SIZE] = value.fontSizeSp
            preferences[KEY_SUBTITLE_TEXT_COLOR] = value.textColorArgb
            preferences[KEY_SUBTITLE_OUTLINE_WIDTH] = value.outlineWidthDp
            preferences[KEY_SUBTITLE_OUTLINE_COLOR] = value.outlineColorArgb
            preferences[KEY_SUBTITLE_BOTTOM_MARGIN] = value.bottomMarginDp
            preferences[KEY_SUBTITLE_BOLD] = value.bold
            preferences[KEY_SUBTITLE_ITALIC] = value.italic
        }
    }

    override suspend fun setSubtitleOffsetMs(offsetMs: Long) {
        context.videoPlayerDataStore.edit { preferences -> preferences[KEY_SUBTITLE_OFFSET_MS] = offsetMs }
    }

    private companion object {
        val KEY_ENGINE = stringPreferencesKey("engine_kind")
        val KEY_DECODER = stringPreferencesKey("decoder_mode")

        // ---- 字幕（M7-A，R14）：新增键，动不到上面的老键位 ----
        val KEY_SUBTITLE_ENABLED = booleanPreferencesKey("subtitle_enabled")
        val KEY_SUBTITLE_FONT_SIZE = floatPreferencesKey("subtitle_font_size_sp")
        val KEY_SUBTITLE_TEXT_COLOR = intPreferencesKey("subtitle_text_color")
        val KEY_SUBTITLE_OUTLINE_WIDTH = floatPreferencesKey("subtitle_outline_width_dp")
        val KEY_SUBTITLE_OUTLINE_COLOR = intPreferencesKey("subtitle_outline_color")
        val KEY_SUBTITLE_BOTTOM_MARGIN = floatPreferencesKey("subtitle_bottom_margin_dp")
        val KEY_SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")
        val KEY_SUBTITLE_ITALIC = booleanPreferencesKey("subtitle_italic")
        val KEY_SUBTITLE_OFFSET_MS = longPreferencesKey("subtitle_offset_ms")
    }
}

/** 文本 → 内核（无法识别时回到默认 Media3，不抛异常）。 */
internal fun parseEngine(raw: String?): EngineKind =
    EngineKind.entries.firstOrNull { it.name == raw } ?: EngineKind.MEDIA3

/** 文本 → 解码档位（无法识别时回到默认 AUTO_HW = 硬解优先，不抛异常）。 */
internal fun parseDecoderMode(raw: String?): DecoderMode =
    DecoderMode.entries.firstOrNull { it.name == raw } ?: DecoderMode.AUTO_HW
