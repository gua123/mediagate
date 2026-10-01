package io.github.gua123.mediagate.media.tsext

/**
 * [PtsTimeline] 的可持久化快照（R3/R4）：断点续扫时用它把「回绕补偿 / 上一次时间」恢复出来，
 * 保证续扫出来的时间轴与一次扫完**逐毫秒一致**。
 */
data class PtsTimelineState(
    val offsetTicks: Long,
    val lastTicks: Long,
    val lastMs: Long,
    val hasLast: Boolean,
    val wraps: Int,
    val resets: Int,
) {
    companion object {
        /** 还没喂过任何时间戳的初始状态。 */
        val EMPTY = PtsTimelineState(
            offsetTicks = 0L,
            lastTicks = 0L,
            lastMs = 0L,
            hasLast = false,
            wraps = 0,
            resets = 0,
        )
    }
}

/**
 * PTS/DTS/PCR 时间轴重建（R3/R4，plan 4.3 第 1 条「生成时间 ↔ 字节偏移表」）。
 *
 * 为什么需要它：33 位 90 kHz 的时间戳约 26.5 小时回绕一次，而拼接流（多个录像粘在一起）里
 * 时间戳还会**直接跳回**。索引表要求时间单调不减，否则二分查找与拖拽都会错乱。
 *
 * 处理规则：
 * 1. **回绕**：若新值比上一值小超过半圈（2^32 tick ≈ 13 小时），判定为回绕，补偿 +2^33；
 * 2. **重置（拼接流）**：其余回跳一律视为新时间轴起点，补偿到与上一值相等——时间轴继续向前，
 *    只是出现一个「同一毫秒的两个关键帧」，拖拽定位仍然正确；
 * 3. **单调**：返回的毫秒数保证不减。
 *
 * 纯 Kotlin，无协程、无 IO，JVM 可测。
 *
 * @param wrapTicks 回绕周期（33 位 = 2^33）。
 * @param resetThresholdTicks 判定「回绕 vs 重置」的分界；默认半圈。
 */
class PtsTimeline(
    private val wrapTicks: Long = WRAP_TICKS,
    private val resetThresholdTicks: Long = wrapTicks / 2,
) {

    private var offsetTicks = 0L
    private var lastTicks = 0L
    private var lastMs = 0L
    private var hasLast = false

    /** 发生过几次回绕（诊断用）。 */
    var wraps: Int = 0
        private set

    /** 发生过几次时间轴重置（拼接流；诊断用）。 */
    var resets: Int = 0
        private set

    /**
     * 把一个 33 位时间戳（PTS/DTS/PCR base，90 kHz）映射成**单调不减**的毫秒。
     *
     * @param ticks 原始 33 位计数；超出 33 位的部分按掩码截断（应对上游算错）。
     */
    fun map(ticks: Long): Long {
        val raw = ticks and (wrapTicks - 1)
        var candidate = raw + offsetTicks
        if (hasLast && candidate < lastTicks) {
            val drop = lastTicks - candidate
            if (drop > resetThresholdTicks) {
                // 回绕：整圈补偿
                offsetTicks += wrapTicks
                wraps++
            } else {
                // 拼接/重置：把新时间轴接到上一值上（时间继续向前）
                offsetTicks += drop
                resets++
            }
            candidate = raw + offsetTicks
        }
        lastTicks = candidate
        hasLast = true
        val ms = candidate * 1000L / CLOCK_HZ
        if (ms > lastMs || !hasEmitted) {
            lastMs = ms
            hasEmitted = true
        }
        return lastMs
    }

    private var hasEmitted = false

    /** 当前状态快照（断点续扫用）。 */
    fun snapshot(): PtsTimelineState = PtsTimelineState(
        offsetTicks = offsetTicks,
        lastTicks = lastTicks,
        lastMs = lastMs,
        hasLast = hasLast || hasEmitted,
        wraps = wraps,
        resets = resets,
    )

    /** 上一个时间戳（33 位原始值）；还没喂过返回 null。 */
    fun lastRawTicks(): Long? = if (hasLast) lastTicks - offsetTicks else null

    companion object {

        /** 33 位 90 kHz 时间戳的回绕周期。 */
        const val WRAP_TICKS: Long = 1L shl 33

        /** 时基：90 kHz。 */
        const val CLOCK_HZ: Long = 90_000L

        /** 判定拼接流重置的默认阈值（半圈 ≈ 13 小时）。 */
        val DEFAULT_RESET_THRESHOLD_TICKS: Long = WRAP_TICKS / 2

        /** 从快照恢复（断点续扫用）。 */
        fun restore(state: PtsTimelineState): PtsTimeline = PtsTimeline().apply {
            offsetTicks = state.offsetTicks
            lastTicks = state.lastTicks
            lastMs = state.lastMs
            hasLast = state.hasLast
            hasEmitted = state.hasLast
            wraps = state.wraps
            resets = state.resets
        }
    }
}
