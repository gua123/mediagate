package io.github.gua123.mediagate.media.engine

import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一播放内核抽象（plan 4.6 `PlayerEngine` 草案 + R9/R10）。
 *
 * 草案里的 13 个动作原样实现：setMedia / prepare / play / pause / seekTo / setSpeed /
 * setResizeMode / setDecoderMode / positionMs / durationMs / subtitles / videoView / release，
 * 外加任务要求的 [kind] 与 [state]。
 *
 * **比草案多出来的 5 个只读访问器**（[isPlaying] / [speed] / [decoderMode] / [resizeMode] /
 * [currentMedia]）：R9 要求「换内核后位置、倍速、字幕保持」，切换器必须在释放旧内核**之前**
 * 把当前状态读出来，否则只能靠上层记账（一旦内核内部改了状态就对不上）。它们都只读、无语义副作用。
 *
 * 线程约定：所有方法都在主线程调用（Media3 / LibVLC 都要求），实现内部不得做阻塞 IO；
 * 数据层的挂起 API 由数据源/代理在各自的后台线程桥接。
 *
 * 生命周期：[release] 之后本实例不可再用；[state] 会回到 [EngineState.Idle]。
 */
interface PlayerEngine {

    /** 内核种类（R9：同一视频在两者间切换）。 */
    val kind: EngineKind

    /** 状态机（UI 只依赖它，不依赖内核私有事件）。 */
    val state: StateFlow<EngineState>

    /** 当前是否在播（切换内核时要恢复播放状态）。 */
    val isPlaying: Boolean

    /** 当前倍速（切换内核时要恢复）。 */
    val speed: Float

    /** 当前解码模式（R10；切换内核时要恢复）。 */
    val decoderMode: DecoderMode

    /** 当前画面缩放模式（切换内核时要恢复）。 */
    val resizeMode: ResizeMode

    /** 当前播放源；未装载时为 null。 */
    val currentMedia: MediaSourceRef?

    /** 装载媒体（不自动起播，按 plan 草案语义：setMedia → prepare → play）。 */
    fun setMedia(src: MediaSourceRef)

    /** 准备（打开解码器/缓冲）。 */
    fun prepare()

    /** 播放。 */
    fun play()

    /** 暂停。 */
    fun pause()

    /** 定位到 [positionMs]（R4 拖拽 seek）。 */
    fun seekTo(positionMs: Long)

    /** 设置倍速（与 plan 草案一致，非法值由实现夹取）。 */
    fun setSpeed(x: Float)

    /** 设置画面缩放模式。 */
    fun setResizeMode(mode: ResizeMode)

    /** 设置解码模式（R10；可触发内核内部重建，见各实现注释）。 */
    fun setDecoderMode(mode: DecoderMode)

    /** 字幕控制器（R14 / R9）。 */
    fun subtitles(): SubtitleTrackController

    /**
     * 视频输出挂载点（Compose 里用 AndroidView 挂上去）。
     *
     * Media3 返回内部懒建的 PlayerView；LibVLC 返回外部传入的 SurfaceView/TextureView。
     * 返回 null 表示当前没有可用画面（例如 VLC 还没 attach 视图）。
     */
    fun videoView(): View?

    /**
     * **音量倍率**（2026-10-03 用户要求：「右侧上下为调整音量，同时增加音量上限为 200%」）。
     *
     * 1.0 = 原始音量；上限 [MAX_VOLUME]（200%）。口径：0~100% 走播放器自身音量，
     * 100%~200% 用系统增益（Media3 侧 LoudnessEnhancer；LibVLC 自身就支持到 200）。
     */
    val volume: Float

    /** 设置音量倍率（实现内部夹到 0..[MAX_VOLUME]）。 */
    fun setVolume(volume: Float)

    /** 当前播放位置（毫秒）。 */
    fun positionMs(): Long

    /** 媒体总时长（毫秒）；未知返回 0。 */
    fun durationMs(): Long

    /** 释放所有资源（幂等）。 */
    fun release()

    companion object {

        /** 音量上限倍率：200%（用户要求）。 */
        const val MAX_VOLUME: Float = 2f

        /** 音量倍率夹到合法范围（NaN/负数一律当 0）。 */
        fun sanitizeVolume(value: Float): Float = when {
            value.isNaN() -> 1f
            value <= 0f -> 0f
            value > MAX_VOLUME -> MAX_VOLUME
            else -> value
        }
    }
}
