package io.github.gua123.mediagate.media.engine

/**
 * 内核状态迁移规则（纯函数，JVM 单测覆盖）。
 *
 * 规则：**播完（[EngineState.Ended]）是终态**，后面再来的"普通"状态事件不能把它冲掉。
 * 离开 Ended 只有一种方式——显式 [reset]（换源 / 释放），不是"某处顺手赋个值"。
 *
 * 为什么必须有这条规则（2026-11-20 用户反馈「开着循环，视频播完不自动切下一个」）：
 * LibVLC 自然播完时，输入线程先把状态推到 END_S（发 `EndReached`），紧接着同一个线程
 * 结束前又发一次 `INPUT_EVENT_DEAD`（VLC 3.0 `src/input/input.c` 的 `Run()`：
 * `End()` 里 `input_ChangeState(END_S)` → 之后无条件 `input_SendEventDead()`；
 * `lib/media_player.c` 把 DEAD 映射成 `Stopped`）。两个事件前后脚到，
 * [VlcEngine] 以前把 `Stopped` 直接写回 Ready，于是播放页 500 ms 一次的进度轮询
 * **永远看不到 Ended**，播放页里"播完按循环档位续播"的分支一次都进不去 ——
 * 文件夹循环 / 单曲循环 / 随机播放三档全部形同不存在。
 *
 * 三条路径的归属（2026-11-20 审计补完：此前 prepare() / play() / setMedia() / release()
 * 还在裸写 `_state.value`，"唯一入口"名不副实）：
 * - 内核事件（Opening / Buffering / Playing / Stopped / EndReached / EncounteredError…）→ [apply]，
 *   Ended 只会被 Ended 或 Error 覆盖；
 * - 换源（[VlcEngine.setMedia] → Preparing）与释放（[VlcEngine.release] → Idle）→ [reset]；
 * - [VlcEngine.prepare] 与 [VlcEngine.play] 里"视频输出未就绪"的 Preparing **也算事件**：
 *   它们绝不能清掉 Ended——否则 [VlcEngine.play] 会以为"这一遍还没播完"，跳过重新装载媒体，
 *   而 LibVLC 的输入线程在播完时已经结束，于是"播完再点播放"变成静默无反应。
 */
internal object EngineStateLatch {

    /** [next] 能不能盖掉 [current]。 */
    fun allows(current: EngineState, next: EngineState): Boolean = when {
        current !is EngineState.Ended -> true
        next is EngineState.Ended -> true
        next is EngineState.Error -> true
        else -> false
    }

    /** 应用一次状态事件；被终态挡住时原样返回。 */
    fun apply(current: EngineState, next: EngineState): EngineState =
        if (allows(current, next)) next else current

    /**
     * 显式重置：允许离开 [EngineState.Ended]，**只给「换源」与「释放」用**。
     *
     * 目标只有两个：[EngineState.Preparing]（装载了新媒体，等内核事件接着推进）或
     * [EngineState.Idle]（没有媒体 / 刚释放）。其他值一律当误用抛出去——
     * 与其再长出一条"绕过 latch 的暗路"，不如让它在单测里立刻炸。
     */
    fun reset(next: EngineState): EngineState {
        require(next == EngineState.Preparing || next == EngineState.Idle) {
            "显式重置只能落到 Preparing（换源）或 Idle（释放），收到 " + next
        }
        return next
    }
}
