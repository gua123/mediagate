package io.github.gua123.mediagate.app

import android.content.Context
import androidx.datastore.preferences.core.edit
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

/** 视频播放页偏好的 DataStore（App 私有；进程内单例由 [preferencesDataStore] 委托保证）。 */
private val Context.videoPlayerDataStore by preferencesDataStore(name = "video_player_settings")

/**
 * 视频播放页偏好的 DataStore 实现（M2-B，R9 内核 / R10 解码档位）。
 *
 * 只记两件事：**上次用的内核**（Media3 / LibVLC）与**解码档位**（自动 / 强制软解 / 强制硬解）。
 * 与 [PlaybackProgressSettings] 同一风格：值以字符串存，读的时候按枚举名解析，
 * 解析不出来（旧版本残留、手改文件）一律回默认值，绝不因为一个坏值让播放页崩掉。
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

    private companion object {
        val KEY_ENGINE = stringPreferencesKey("engine_kind")
        val KEY_DECODER = stringPreferencesKey("decoder_mode")
    }
}

/** 文本 → 内核（无法识别时回到默认 Media3，不抛异常）。 */
internal fun parseEngine(raw: String?): EngineKind =
    EngineKind.entries.firstOrNull { it.name == raw } ?: EngineKind.MEDIA3

/** 文本 → 解码档位（无法识别时回到默认 AUTO_HW = 硬解优先，不抛异常）。 */
internal fun parseDecoderMode(raw: String?): DecoderMode =
    DecoderMode.entries.firstOrNull { it.name == raw } ?: DecoderMode.AUTO_HW
