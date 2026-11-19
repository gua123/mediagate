package io.github.gua123.mediagate.media.engine

import io.github.gua123.mediagate.core.common.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内核状态的**唯一写入口**（[EngineStateLatch] 的落地壳）。
 *
 * 为什么把 `MutableStateFlow` 收到这里，而不是留在 [VlcEngine] 字段上：
 * 上一轮只把 LibVLC 的事件回调改成走 latch，setMedia / release / prepare / play 里还剩
 * 几处 `_state.value = ...` 裸写，审计一眼就能看出"唯一入口"名不副实——prepare() 甚至能在
 * Ended 之后把终态冲掉（[VlcEngine.play] 随后就不认"播完了"，跳过重新装载媒体，播放静默失败）。
 * 现在 flow 私有在这里：引擎只能 [event]（受终态约束）或 [reset]（只有换源/释放），
 * 类型层面就没有第三条路。
 *
 * 纯 JVM 可测（只依赖 kotlinx-coroutines 的 flow），用例见 EngineStateHolderTest。
 */
internal class EngineStateHolder(initial: EngineState = EngineState.Idle) {

    private val _state = MutableStateFlow(initial)

    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** 当前状态快照（不订阅）。 */
    val current: EngineState get() = _state.value

    /** 一次内核事件：Ended 之后只有 Ended / Error 能盖上去。 */
    fun event(next: EngineState) {
        _state.value = EngineStateLatch.apply(_state.value, next)
    }

    /**
     * 一次显式重置：只给换源（→ Preparing）与释放（→ Idle）用。
     *
     * 目标越界会由 [EngineStateLatch.reset] 抛 IllegalArgumentException，这里不吞。
     */
    fun reset(next: EngineState) {
        val before = _state.value
        _state.value = EngineStateLatch.reset(next)
        if (before is EngineState.Ended) {
            // 播完态被显式清掉是"重要且低频"的事，留一行现场（诊断页里能看出是谁把 Ended 放走的）
            AppLog.i(TAG, "离开播完态（显式重置）：" + before + " → " + next)
        }
    }

    private companion object {
        const val TAG = "engine"
    }
}
