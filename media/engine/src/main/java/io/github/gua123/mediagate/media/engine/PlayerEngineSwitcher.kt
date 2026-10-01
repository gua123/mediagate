package io.github.gua123.mediagate.media.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog

/**
 * 引擎切换器（R9 多内核 / R10 硬软解切换）——**Android 胶水层**。
 *
 * 纯逻辑（读现场、算方案、文案、内核选择）都在 [EngineSwitchPlanner] / [EngineSelection] 里，
 * 本类只做「必须在主线程上对真实播放器做的调用」：暂停旧内核 → 释放 → 建新内核 →
 * 按 [SwitchPlan.restore] 恢复位置/倍速/解码模式/画面模式/字幕/播放状态。
 *
 * 切换过程中 [state] 为 [SwitchState.Switching]（文案由 [EngineSwitchPlanner.describe] 生成，
 * 例如「正在切换解码器：Media3 → LibVLC，位置 12.3s，倍速 1.00x，强制软解」）。
 *
 * @param create 按内核种类造实例（由 App 层注入，避免 media:engine 反向依赖 media:playback）。
 */
class PlayerEngineSwitcher(
    private val create: (EngineKind) -> PlayerEngine,
) {

    private val _state = MutableStateFlow<SwitchState>(SwitchState.Idle)

    /** 切换过程状态（UI 可据此显示「正在切换解码器」遮罩）。 */
    val state: StateFlow<SwitchState> = _state.asStateFlow()

    private var engine: PlayerEngine? = null

    /** 当前内核；还没创建过时为 null。 */
    fun current(): PlayerEngine? = engine

    /** 读出内核现场（供 [EngineSwitchPlanner] 使用；必须在主线程调用）。 */
    fun snapshotOf(source: PlayerEngine): EngineSnapshot = EngineSnapshot(
        media = source.currentMedia,
        positionMs = source.positionMs(),
        durationMs = source.durationMs(),
        speed = source.speed,
        decoderMode = source.decoderMode,
        resizeMode = source.resizeMode,
        playWhenReady = source.isPlaying,
        subtitles = source.subtitles().state.value,
    )

    /**
     * 切到 [kind]（或同内核重建），并恢复现场。
     *
     * @param decoderMode 目标解码模式；null = 沿用旧内核当前档位（手动换内核时用）。
     * @param source 显式指定要播放的媒体（例如自动降级时上层已知道该播什么）；null = 沿用旧内核当前媒体。
     */
    suspend fun switchTo(
        kind: EngineKind,
        reason: SwitchReason,
        decoderMode: DecoderMode? = null,
        source: MediaSourceRef? = null,
    ): PlayerEngine = withContext(Dispatchers.Main.immediate) {
        val previous = engine
        val snapshot = previous?.let { snapshotOf(it) } ?: EngineSnapshot()
        val prepared = snapshot.copy(
            media = source ?: snapshot.media,
            decoderMode = decoderMode ?: snapshot.decoderMode,
        )
        val plan = EngineSwitchPlanner.plan(
            from = previous?.kind ?: kind,
            to = kind,
            snapshot = prepared,
            reason = reason,
        )
        _state.value = SwitchState.Switching(plan, EngineSwitchPlanner.describe(plan))
        try {
            // 1) 旧引擎停在 plan.stopAtMs 后释放（位置已经读进 plan，不再依赖旧实例）
            previous?.pause()
            previous?.release()
            // 2) 新引擎起来，按恢复清单逐项还原
            val created = create(kind)
            engine = created
            applyRestore(created, plan.restore)
            created
        } finally {
            _state.value = SwitchState.Idle
        }
    }

    /** 释放当前内核（幂等）。 */
    fun release() {
        engine?.release()
        engine = null
        _state.value = SwitchState.Idle
    }

    /** 恢复清单 → 内核调用（顺序：解码模式 → 画面 → 媒体 → 位置 → 倍速 → 字幕 → 播放）。 */
    private fun applyRestore(target: PlayerEngine, restore: RestoreSpec) {
        target.setDecoderMode(restore.decoderMode)
        target.setResizeMode(restore.resizeMode)
        val media = restore.media
        if (media == null) {
            AppLog.w(TAG, "切换内核时没有可恢复的媒体，新内核停在空闲状态")
            return
        }
        target.setMedia(media)
        target.prepare()
        if (restore.positionMs > 0) target.seekTo(restore.positionMs)
        target.setSpeed(restore.speed)
        restoreSubtitle(target.subtitles(), restore.subtitles)
        if (restore.playWhenReady) target.play()
    }

    private fun restoreSubtitle(controller: SubtitleTrackController, state: SubtitleState) {
        controller.selectTrack(state.uri)
        controller.setOffsetMs(state.offsetMs)
        controller.setEnabled(state.enabled)
    }

    /** 按解码模式切换内核（R10 入口）：必要时空操作（已经是目标内核且档位相同）。 */
    suspend fun applyDecoderMode(mode: DecoderMode): PlayerEngine = switchTo(
        kind = EngineSelection.preferredFor(mode),
        reason = SwitchReason.DECODER_MODE_CHANGE,
        decoderMode = mode,
    )

    /** 出错后的自动降级（plan 4.6）：返回 null 表示已经无处可退。 */
    suspend fun fallbackFromFailure(): PlayerEngine? {
        val current = engine?.kind ?: return null
        val next = EngineSelection.fallbackFrom(current) ?: return null
        return switchTo(kind = next, reason = SwitchReason.ERROR_FALLBACK)
    }

    private companion object {
        const val TAG = "engine"
    }
}
