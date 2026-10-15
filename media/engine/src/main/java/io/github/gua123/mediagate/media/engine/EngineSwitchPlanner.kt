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
     * **当前在用的内核清单**（界面据此决定要不要显示「内核」按钮；也是"能不能切"的唯一判据）。
     *
     * **2026-10-15 用户拍板：只保留原有引擎（Media3）**——
     * 原话「画面全没有，所以我想切回原先的引擎，将自研引擎和 libvlc 都去掉吧，原有引擎就可以」。
     * 背景：自研引擎在真机上能切过去但**不出画面**；LibVLC 在这台机器上会把 App 整个带走（历史面包屑为证）。
     * 两个都不再出现在任何入口里；代码与设计留档见 docs/方案-播放引擎重写.md（真要复活有据可查）。
     */
    fun availableEngines(): List<EngineKind> = listOf(EngineKind.MEDIA3)

    /** 现在只能有一个内核（界面据此隐藏切换入口）。 */
    fun canSwitchEngine(): Boolean = availableEngines().size > 1

    /**
     * 把（可能来自旧版本的）**偏好**解析成实际要用的内核。
     *
     * 老版本可能把 LibVLC / 自研引擎写进偏好；那些内核已下线，所以这里统一回到唯一可用的那个。
     * 放在契约层（纯函数、有单测）而不是藏在 :app 的容器里——这样"旧偏好怎么办"有唯一答案、也能被测到。
     */
    fun resolvePreference(preferred: EngineKind): EngineKind =
        if (availableEngines().contains(preferred)) preferred
        else availableEngines().firstOrNull() ?: EngineKind.MEDIA3

    /**
     * 某个解码模式「倾向」用哪个内核。
     *
     * Media3 三种档位都支持（[DecoderMode.FORCE_SW] 走 softwareOnly 解码器筛选，
     * 见 ExoPlayerEngine.codecSelectorFor），所以**一律 Media3**，不再靠 LibVLC 软解。
     */
    fun preferredFor(decoderMode: DecoderMode): EngineKind = EngineKind.MEDIA3

    /**
     * 用户点「内核」按钮时的**下一档**。
     *
     * 只有一个内核可用时**原地不动**（按钮本身也会被界面隐藏，这里只是兜底）。
     */
    fun cycleNext(current: EngineKind, canUseVlc: Boolean): EngineKind {
        val order = availableEngines()
        // 只有一个可用内核时：**返回它**（而不是当前的旧值）——万一当前值来自旧版本存的偏好，
        // 这样也能把它拉回唯一可用的那个内核
        if (order.size <= 1) return order.firstOrNull() ?: current
        var index = order.indexOf(current).coerceAtLeast(0)
        repeat(order.size) {
            index = (index + 1) % order.size
            val candidate = order[index]
            if (candidate != EngineKind.VLC || canUseVlc) return candidate
        }
        return current
    }

    /**
     * 出错后的降级目标：**没有别的内核可退 ⇒ null**（界面因此不显示「改用 X 续播」）。
     *
     * 2026-10-15 之前这里是 Media3 → VLC → 自研引擎 的降级链；VLC 与自研引擎下线后链路为空。
     */
    fun fallbackFrom(current: EngineKind): EngineKind? =
        availableEngines().firstOrNull { it != current && it != EngineKind.MEDIA3 } ?: null
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
