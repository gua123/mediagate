package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.data.storage.api.PrefetchTargets

/**
 * **滑窗预取与"下一集"预热的判据**（纯函数，JVM 单测）。
 *
 * 来源：`docs/方案-网络加载增强.md` 的 **P1.6 / P1.7**（2026-10-15 起做，P0 已被用户真机确认"明显变快"）。
 *
 * 两条硬约束（都是用户明确要过的）：
 * - **预取必须给播放让路**：所以这里只在"位置确实往前走了"时才发一次，且只抓一小段；
 * - **不瞎猜偏移**：文件大小未知（stat 拿不到）时**什么都不做**，与 [PrefetchTargets.openPlan] 同一口径。
 */
object PrefetchWindow {

    /** 播放位置至少前进这么多毫秒，才重新规划一次窗口（避免每 500 ms 采样都发请求）。 */
    const val MIN_ADVANCE_MS: Long = 20_000L

    /** 预取"当前位置往后再 30 秒"那一段（比"当前位置"更值：向前拖一点就在本地了）。 */
    const val AHEAD_MS: Long = 30_000L

    /**
     * 一次滑窗预取**抓几段**（2026-10-20 用户反馈「nPlayer 加载快一倍」后加大）。
     *
     * 原来只抓 1 段（4 MB），在高码率视频里连 20 秒都撑不满；现在一次排 4 段（16 MB）**并发**拉，
     * 把"每 20 秒才补一段"的涓流改成"追到前面去"。
     */
    const val AHEAD_SEGMENTS: Int = 4

    /** 距文件末尾不足这么多毫秒时不再向后预取（末尾那一段已由打开时的尾段预取覆盖）。 */
    const val NEAR_END_MS: Long = 10_000L

    /** 播到这个比例就预热"下一集"（P1.7 的 80% 口径）。 */
    const val NEXT_EPISODE_FRACTION: Double = 0.8

    /** 一次滑窗预取的目标区间。 */
    data class Window(val offset: Long, val length: Long)

    /**
     * 该不该发一次滑窗预取；该发就给出**要抓的字节区间**。
     *
     * @param positionMs 当前播放位置；@param durationMs 总时长（0 = 未知）；
     * @param size 文件总大小（null = 未知 ⇒ 不动）；@param segmentBytes 段大小；
     * @param lastWindowPositionMs 上一次窗口预取时的位置（0 = 还没发过）。
     * @return null = 这次不发（位置没怎么动 / 快播完 / 大小未知 / 段大小非法）。
     */
    fun decide(
        positionMs: Long,
        durationMs: Long,
        size: Long?,
        segmentBytes: Long,
        lastWindowPositionMs: Long,
    ): Window? {
        if (size == null || size <= 0L || segmentBytes <= 0L) return null
        if (positionMs <= 0L) return null
        // 位置没往前走够 ⇒ 不发（这就是"让路"的节流阀）
        if (lastWindowPositionMs > 0L && positionMs - lastWindowPositionMs < MIN_ADVANCE_MS) return null
        // 快播完了 ⇒ 不再向后预取（后面没内容，末尾段也早有预取）
        if (durationMs > 0L && durationMs - positionMs <= NEAR_END_MS) return null
        val targetMs = positionMs + AHEAD_MS
        val offset = PrefetchTargets.offsetForPosition(targetMs, durationMs, size) ?: return null
        if (offset <= 0L || offset >= size) return null
        val want = (segmentBytes.coerceAtLeast(1L) * AHEAD_SEGMENTS).coerceAtMost(size - offset)
        return Window(offset = offset, length = want.coerceAtLeast(segmentBytes.coerceAtMost(size - offset)))
    }

    /**
     * 该不该**预热下一集**（P1.7）：播到 80% 之后，且这一次播放还没预热过。
     *
     * @param alreadyWarmed 这次播放是否已经预热过（每个文件只做一次；
     *   用户手动切集/换文件会重置它，由调用方维护）。
     */
    fun shouldWarmNextEpisode(
        positionMs: Long,
        durationMs: Long,
        alreadyWarmed: Boolean,
    ): Boolean {
        if (alreadyWarmed) return false
        if (durationMs <= 0L || positionMs <= 0L) return false
        return positionMs >= (durationMs * NEXT_EPISODE_FRACTION).toLong()
    }
}
