package io.github.gua123.mediagate.feature.player.video

import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineSelection
import io.github.gua123.mediagate.media.engine.EngineSnapshot
import io.github.gua123.mediagate.media.engine.EngineSwitchPlanner
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.engine.RestoreSpec
import io.github.gua123.mediagate.media.engine.SwitchPlan
import io.github.gua123.mediagate.media.engine.SwitchReason

/** 改解码档位后该怎么落地（R10）。 */
sealed interface DecoderAction {

    /** 内核不变，由引擎自己在内部重建解码器（画面短暂黑屏，引擎实现已处理）。 */
    data object RebuildInPlace : DecoderAction

    /** 必须换内核（强制软解倾向 LibVLC，Media3 的软解能力取决于设备是否带软解码器）。 */
    data class SwitchTo(val kind: EngineKind) : DecoderAction
}

/**
 * 引擎切换的落地胶水（R9 多内核 / R10 硬软解）——**只做纯逻辑 + 对 [PlayerEngine] 的调用**。
 *
 * 为什么不用 [io.github.gua123.mediagate.media.engine.PlayerEngineSwitcher]：它的 `create` 是**非挂起**
 * lambda，而 :app 建内核是挂起操作（要读 DataStore 偏好、起回环代理）。这里改用它内部同一套
 * 纯逻辑（[EngineSwitchPlanner] 出计划），恢复顺序与 PlayerEngineSwitcher.applyRestore 逐条一致，
 * 于是「位置 / 倍速 / 解码档位 / 画面模式 / 字幕 / 播放状态都保持」（R9 验收口径）在 JVM 单测里可断言。
 *
 * 降级决策同样是 [EngineSelection.fallbackFrom] 这一份纯函数——与
 * PlayerEngineSwitcher.fallbackFromFailure() 的判定完全相同，只是切换流程由本模块自己驱动。
 */
object VideoEngineSwitch {

    /**
     * 读出内核现场（R9：切之前必须先读，否则释放后只能靠上层记账）。
     *
     * @param positionFloorMs 位置下限：内核已经出错时它可能报 0，用 UI 最后观测到的位置兜底，
     *   这样「Media3 解码失败 → 切 LibVLC」不会把进度丢回开头。
     * @param playWhenReady 新内核起来后是否继续播放；默认沿用旧内核当前状态。
     */
    fun snapshotOf(
        engine: PlayerEngine,
        positionFloorMs: Long = 0L,
        playWhenReady: Boolean = engine.isPlaying,
    ): EngineSnapshot = EngineSnapshot(
        media = engine.currentMedia,
        positionMs = maxOf(engine.positionMs(), positionFloorMs.coerceAtLeast(0L)),
        durationMs = engine.durationMs(),
        speed = engine.speed,
        decoderMode = engine.decoderMode,
        resizeMode = engine.resizeMode,
        playWhenReady = playWhenReady,
        subtitles = engine.subtitles().state.value,
    )

    /**
     * 生成切换方案（R9/R10）：纯函数，输入是旧内核 + 目标内核/档位，输出是「停在哪个位置、
     * 新内核要恢复什么」。
     *
     * @param decoderMode 目标解码档位；null = 沿用旧内核当前档位（手动换内核时用）。
     * @param source 显式指定要播的媒体（自动降级时上层已知道该播什么）；null = 沿用旧内核当前媒体。
     */
    fun planFor(
        engine: PlayerEngine,
        target: EngineKind,
        reason: SwitchReason,
        decoderMode: DecoderMode? = null,
        source: MediaSourceRef? = null,
        positionFloorMs: Long = 0L,
        playWhenReady: Boolean = engine.isPlaying,
    ): SwitchPlan {
        val snapshot = snapshotOf(engine, positionFloorMs, playWhenReady)
        val prepared = snapshot.copy(
            media = source ?: snapshot.media,
            decoderMode = decoderMode ?: snapshot.decoderMode,
        )
        return EngineSwitchPlanner.plan(from = engine.kind, to = target, snapshot = prepared, reason = reason)
    }

    /**
     * 把恢复清单落到新内核上。
     *
     * 顺序（与 PlayerEngineSwitcher 一致）：解码档位 → 画面模式 → 媒体 → 准备 → 位置 → 倍速 →
     * 字幕 → 播放状态。先设解码档位是因为它是渲染器构建期参数（改档位会重建解码器），
     * 放在装载媒体之前可以少一次黑屏。
     */
    fun applyRestore(engine: PlayerEngine, restore: RestoreSpec) {
        engine.setDecoderMode(restore.decoderMode)
        engine.setResizeMode(restore.resizeMode)
        val media = restore.media ?: return
        engine.setMedia(media)
        engine.prepare()
        if (restore.positionMs > 0L) engine.seekTo(restore.positionMs)
        engine.setSpeed(restore.speed)
        val subtitles = engine.subtitles()
        subtitles.selectTrack(restore.subtitles.uri)
        subtitles.setOffsetMs(restore.subtitles.offsetMs)
        subtitles.setEnabled(restore.subtitles.enabled)
        if (restore.playWhenReady) engine.play()
    }

    /**
     * 改解码档位的落地方式（R10，纯决策）。
     *
     * 规则：档位倾向的内核（[EngineSelection.preferredFor]）与当前不同、且该内核可用时换内核；
     * 否则让引擎自己在内部重建（Media3 的软解选择器 / LibVLC 的 instance 选项都会随之重建）。
     *
     * @param canUseVlc LibVLC 是否可用（回环代理起不来时不能换过去，只能原地重建）。
     */
    fun decoderAction(current: EngineKind, mode: DecoderMode, canUseVlc: Boolean): DecoderAction {
        val preferred = EngineSelection.preferredFor(mode)
        if (preferred == current) return DecoderAction.RebuildInPlace
        if (preferred == EngineKind.VLC && !canUseVlc) return DecoderAction.RebuildInPlace
        return DecoderAction.SwitchTo(preferred)
    }

    /**
     * 出错后的降级目标（plan 4.6 自动降级链，纯决策）：Media3 → LibVLC；LibVLC → null（无处可退）。
     *
     * 与 PlayerEngineSwitcher.fallbackFromFailure() 的判定一致：LibVLC 需要回环代理，
     * 代理不可用时不再给出降级动作。
     */
    fun fallbackTarget(current: EngineKind, canUseVlc: Boolean): EngineKind? {
        val next = EngineSelection.fallbackFrom(current) ?: return null
        if (next == EngineKind.VLC && !canUseVlc) return null
        return next
    }
}
