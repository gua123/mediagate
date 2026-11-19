package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineStateHolder] 的用例：钉住 [VlcEngine] 四个"写状态"入口的归属（2026-11-20 审计收口）。
 *
 * 背景：上一轮把 LibVLC 事件回调改走 latch 之后，`VlcEngine` 里还剩 4 处裸写 `_state.value`：
 * setMedia（Preparing）、release（Idle）、prepare()（Preparing）、play() 的输出未就绪（Preparing）。
 * 其中 **prepare() 是漏洞**：它能在 Ended 之后把终态冲掉，紧接着 play() 就不认"播完了"，
 * 跳过重新装载媒体——而 LibVLC 的输入线程在播完时已经结束，于是"播完再点播放"静默无反应。
 *
 * 归类：setMedia / release 是"显式重置"（可以离开 Ended）；prepare / play 是"事件"（必须被 Ended 挡住）。
 */
class EngineStateHolderTest {

    /** 模拟"刚刚播完"：Ready → Ended。 */
    private fun endedHolder(): EngineStateHolder =
        EngineStateHolder(EngineState.Ready).apply { event(EngineState.Ended) }

    @Test
    fun 播完之后调_prepare_状态仍是_Ended() {
        val holder = endedHolder()
        // VlcEngine.prepare() → moveTo(Preparing) → event(Preparing)
        holder.event(EngineState.Preparing)
        assertEquals("prepare() 不许把播完态冲掉", EngineState.Ended, holder.state.value)
    }

    @Test
    fun 播完之后_play_的输出未就绪分支也不会冲掉_Ended() {
        val holder = endedHolder()
        // VlcEngine.play() 两条 pendingPlay 分支 → moveTo(Preparing)
        holder.event(EngineState.Preparing)
        holder.event(EngineState.Preparing)
        assertEquals(EngineState.Ended, holder.state.value)
    }

    @Test
    fun 播完之后调_release_变_Idle() {
        val holder = endedHolder()
        // VlcEngine.release() → resetTo(Idle) → reset(Idle)
        holder.reset(EngineState.Idle)
        assertEquals(EngineState.Idle, holder.state.value)
    }

    @Test
    fun 播完之后调_setMedia_变_Preparing() {
        val holder = endedHolder()
        // VlcEngine.setMedia() → resetTo(Preparing) → reset(Preparing)
        holder.reset(EngineState.Preparing)
        assertEquals("换片了就不该还是「播完了」", EngineState.Preparing, holder.state.value)
    }

    @Test
    fun 播完之后重新装载的第一批事件能把流程推下去() {
        val holder = endedHolder()
        holder.reset(EngineState.Preparing) // 换源 / restartAfterEnded
        holder.event(EngineState.Ready) // Playing
        assertEquals(EngineState.Ready, holder.state.value)
    }

    @Test
    fun 播完之后错误仍然看得见() {
        val holder = endedHolder()
        holder.event(EngineState.Error("解码失败"))
        assertTrue(holder.state.value is EngineState.Error)
    }

    @Test
    fun 重置目标越界时当场抛出且不改状态() {
        val holder = endedHolder()
        val error = runCatching { holder.reset(EngineState.Ready) }.exceptionOrNull()
        assertTrue("reset(Ready) 既不是换源也不是释放，属于误用", error is IllegalArgumentException)
        assertEquals("抛了就不该留下半个状态", EngineState.Ended, holder.state.value)
    }

    @Test
    fun 播完之前事件照常推进() {
        val holder = EngineStateHolder()
        assertEquals(EngineState.Idle, holder.state.value)
        holder.event(EngineState.Preparing)
        assertEquals(EngineState.Preparing, holder.state.value)
        holder.event(EngineState.Ready)
        assertEquals(EngineState.Ready, holder.state.value)
        holder.event(EngineState.Ended)
        assertEquals(EngineState.Ended, holder.state.value)
    }
}
