package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineStateLatch] 的用例：钉住「播完是终态」这条规则。
 *
 * 现场 bug（2026-11-20 用户：「播放完成后开了循环时也不会自动切换下一个视频」）就是
 * LibVLC 播完时 EndReached 之后立刻补一个 Stopped，把 Ended 冲成 Ready，
 * 于是播放页 500 ms 一轮的轮询永远看不到 Ended，循环四档全都失效。
 */
class EngineStateLatchTest {

    @Test
    fun 播完之后的_Stopped_与_Paused_都不能把状态冲回_Ready() {
        // LibVLC 播完的真实顺序：EndReached（Ended）→ Stopped（INPUT_EVENT_DEAD，映射成 Ready）
        var state: EngineState = EngineState.Ready
        state = EngineStateLatch.apply(state, EngineState.Ended)
        assertEquals(EngineState.Ended, state)

        state = EngineStateLatch.apply(state, EngineState.Ready) // Stopped / Paused / LengthChanged
        assertEquals("播完不能被后续事件冲掉", EngineState.Ended, state)

        state = EngineStateLatch.apply(state, EngineState.Preparing) // Buffering / Opening
        assertEquals(EngineState.Ended, state)
    }

    @Test
    fun 播完之后仍然能看到错误() {
        val state = EngineStateLatch.apply(EngineState.Ended, EngineState.Error("解码失败"))
        assertTrue(state is EngineState.Error)
    }

    @Test
    fun 播完之前的状态迁移不受影响() {
        var state: EngineState = EngineState.Idle
        state = EngineStateLatch.apply(state, EngineState.Preparing)
        assertEquals(EngineState.Preparing, state)
        state = EngineStateLatch.apply(state, EngineState.Ready)
        assertEquals(EngineState.Ready, state)
        state = EngineStateLatch.apply(state, EngineState.Preparing)
        assertEquals("换源/缓冲时照旧可以回到 Preparing", EngineState.Preparing, state)
        state = EngineStateLatch.apply(state, EngineState.Ended)
        assertEquals(EngineState.Ended, state)
    }

    @Test
    fun 显式重置是离开播完态的唯一通道() {
        // 同样两个目标：走"事件"在 Ended 上一律被挡，走"重置"才放行
        assertEquals(EngineState.Ended, EngineStateLatch.apply(EngineState.Ended, EngineState.Preparing))
        assertEquals(EngineState.Ended, EngineStateLatch.apply(EngineState.Ended, EngineState.Idle))
        assertEquals(EngineState.Preparing, EngineStateLatch.reset(EngineState.Preparing))
        assertEquals(EngineState.Idle, EngineStateLatch.reset(EngineState.Idle))
    }

    @Test
    fun 显式重置不接受换源与释放之外的目标() {
        val error = runCatching { EngineStateLatch.reset(EngineState.Ready) }.exceptionOrNull()
        assertTrue("reset(Ready) 属于误用，要当场炸", error is IllegalArgumentException)
        val ended = runCatching { EngineStateLatch.reset(EngineState.Ended) }.exceptionOrNull()
        assertTrue("重置到 Ended 不是换源也不是释放", ended is IllegalArgumentException)
    }

    @Test
    fun 允许判定与终态口径一致() {
        assertFalse(EngineStateLatch.allows(EngineState.Ended, EngineState.Ready))
        assertTrue(EngineStateLatch.allows(EngineState.Ended, EngineState.Ended))
        assertTrue(EngineStateLatch.allows(EngineState.Ready, EngineState.Ended))
        assertTrue(EngineStateLatch.allows(EngineState.Preparing, EngineState.Ready))
    }
}
