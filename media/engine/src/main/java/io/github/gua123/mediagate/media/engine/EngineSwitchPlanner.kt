package io.github.gua123.mediagate.media.engine

import java.util.Locale

/** 切换原因（决定提示文案与是否允许换内核）。 */
enum class SwitchReason(val label: String) {

    /** 用户在播放页手动选内核（R9）。 */
    USER_REQUEST("手动切换内核"),

    /** 改解码模式导致的重建/换内核（R10）。 */
    DECODER_MODE_CHANGE("切换解码模式"),

    /** 内核报错后的自动降级（plan 4.6：Media3 失败 → VLC 同位置续播）。 */
    ERROR_FALLBACK("解码失败自动降级"),
}

/**
 * 引擎切换时读出的现场（R9：位置、倍速、字幕等必须保持）。
 *
 * 全部字段都是纯数据，可以从任意 [PlayerEngine] 读出来，也可以在 JVM 单测里直接构造。
 */
data class EngineSnapshot(
    val media: MediaSourceRef? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val decoderMode: DecoderMode = DecoderMode.AUTO_HW,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val playWhenReady: Boolean = false,
    val subtitles: SubtitleState = SubtitleState(),
)

/** 新引擎起来后要恢复什么（[SwitchPlan] 的后半段）。 */
data class RestoreSpec(
    val media: MediaSourceRef?,
    val positionMs: Long,
    val speed: Float,
    val decoderMode: DecoderMode,
    val resizeMode: ResizeMode,
    val subtitles: SubtitleState,
    val playWhenReady: Boolean,
)

/**
 * 切换方案（纯数据，JVM 可测）：**旧引擎停在哪 + 新引擎起来后要恢复什么**。
 *
 * @property from 旧内核。
 * @property to 新内核。
 * @property stopAtMs 旧引擎的停放位置（= 恢复位置，便于上层断言「位置没变」）。
 * @property restore 恢复清单。
 * @property reason 切换原因。
 * @property rebuildOnly true = 内核没换，只是同一内核内重建（改解码模式必须重建 Media3 渲染器 / VLC 管线）。
 */
data class SwitchPlan(
    val from: EngineKind,
    val to: EngineKind,
    val stopAtMs: Long,
    val restore: RestoreSpec,
    val reason: SwitchReason,
    val rebuildOnly: Boolean,
)

/** 引擎选择（R10 的解码模式 → 内核映射，纯函数）。 */
object EngineSelection {

    /**
     * 某个解码模式「倾向」用哪个内核：
     * - [DecoderMode.FORCE_SW] → LibVLC（自带软解，Media3 只有设备带软解码器时才软得起来）；
     * - [DecoderMode.AUTO_HW] / [DecoderMode.FORCE_HW] → Media3（MediaCodec 硬解，seek/字幕联动最顺）。
     */
    fun preferredFor(decoderMode: DecoderMode): EngineKind =
        if (decoderMode.prefersVlcEngine) EngineKind.VLC else EngineKind.MEDIA3

    /**
     * 出错后的降级目标（plan 4.6 自动降级链）：Media3 出错 → LibVLC；LibVLC 再出错 → null（无处可退）。
     */
    fun fallbackFrom(current: EngineKind): EngineKind? = when (current) {
        EngineKind.MEDIA3 -> EngineKind.VLC
        EngineKind.VLC -> null
    }
}

/**
 * 引擎切换的**纯逻辑**部分（JVM 单测覆盖；Android 调用留给 [PlayerEngineSwitcher]）。
 *
 * 职责边界：本对象只回答「停在哪个位置、新引擎要恢复什么」，不碰任何播放器实例——
 * 于是「位置/倍速/解码模式/字幕偏移都被保留」这件事可以在 JVM 上确定性地验证（R9）。
 */
object EngineSwitchPlanner {

    /** 允许的最小倍速（与 Media3 PlaybackParameters 的有效区间一致）。 */
    const val MIN_SPEED = 0.25f

    /** 允许的最大倍速。 */
    const val MAX_SPEED = 4.0f

    /** 默认倍速。 */
    const val DEFAULT_SPEED = 1.0f

    /** 默认回退量（毫秒）：0 = 精确续播（R9 要求「位置保持」）。 */
    const val DEFAULT_BACKOFF_MS = 0L

    /**
     * 生成切换方案。
     *
     * 位置口径：`resume = clamp(position - backoff, 0, duration)`；
     * 已经播到结尾（`position >= duration > 0`）时从头开始（0），否则换内核后会立刻又判定 ENDED。
     *
     * @param backoffMs 允许的回退量（Live/关键帧场景可留一点余量），默认 0。
     */
    fun plan(
        from: EngineKind,
        to: EngineKind,
        snapshot: EngineSnapshot,
        reason: SwitchReason,
        backoffMs: Long = DEFAULT_BACKOFF_MS,
    ): SwitchPlan {
        val resume = resumePosition(snapshot, backoffMs)
        return SwitchPlan(
            from = from,
            to = to,
            stopAtMs = resume,
            restore = RestoreSpec(
                media = snapshot.media,
                positionMs = resume,
                speed = sanitizeSpeed(snapshot.speed),
                decoderMode = snapshot.decoderMode,
                resizeMode = snapshot.resizeMode,
                subtitles = snapshot.subtitles.copy(offsetMs = SubtitleState.clampOffset(snapshot.subtitles.offsetMs)),
                playWhenReady = snapshot.playWhenReady,
            ),
            reason = reason,
            rebuildOnly = from == to,
        )
    }

    /** 续播位置（毫秒）：见 [plan] 的位置口径。 */
    fun resumePosition(snapshot: EngineSnapshot, backoffMs: Long = DEFAULT_BACKOFF_MS): Long {
        val duration = snapshot.durationMs
        if (duration > 0 && snapshot.positionMs >= duration) return 0L
        val target = snapshot.positionMs - backoffMs.coerceAtLeast(0L)
        val clamped = target.coerceAtLeast(0L)
        return if (duration > 0) clamped.coerceAtMost(duration) else clamped
    }

    /** 倍速兜底：非有限值/非正数回落到 1x，其余夹到 [MIN_SPEED]..[MAX_SPEED]。 */
    fun sanitizeSpeed(speed: Float): Float = when {
        !speed.isFinite() || speed <= 0f -> DEFAULT_SPEED
        else -> speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** 面向用户的切换说明（R10「正在切换解码器」提示）。 */
    fun describe(plan: SwitchPlan): String = buildString {
        append(if (plan.rebuildOnly) "正在切换解码器：" else "正在切换播放内核：")
        append(plan.from.label)
        append(" → ")
        append(plan.to.label)
        append("，位置 ")
        append(formatSeconds(plan.restore.positionMs))
        append("，倍速 ")
        append(String.format(Locale.ROOT, "%.2fx", plan.restore.speed))
        append("，")
        append(plan.restore.decoderMode.label)
        if (plan.restore.subtitles.uri != null) append("，保留外挂字幕")
        if (plan.restore.subtitles.offsetMs != 0L) {
            append("（偏移 ")
            append(formatSeconds(plan.restore.subtitles.offsetMs))
            append("）")
        }
    }

    /** 毫秒 → 「12.3s」/「1:02:03」之类的简短展示。 */
    fun formatSeconds(ms: Long): String {
        val totalSeconds = ms / 1000.0
        if (totalSeconds < 60) return String.format(Locale.ROOT, "%.1fs", totalSeconds)
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1000
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}

/** 切换过程状态（对 UI 暴露「正在切换解码器」，plan 4.6 要求）。 */
sealed interface SwitchState {

    /** 空闲：没有切换在进行。 */
    data object Idle : SwitchState

    /** 切换中：[message] 可直接显示在播放页。 */
    data class Switching(val plan: SwitchPlan, val message: String) : SwitchState
}
