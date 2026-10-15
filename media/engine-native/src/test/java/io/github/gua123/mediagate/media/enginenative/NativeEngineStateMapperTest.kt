package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.media.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态映射的护栏（纯 JVM）：native 的状态词一旦改了、或者哪一档映射错了，
 * 界面上就是"卡在准备中""明明在播却显示暂停"这类只有肉眼才能发现的问题。
 */
class NativeEngineStateMapperTest {

    private fun statusOf(state: String) = NativePlayerStatus(state = state)

    @Test
    fun 六种_native_状态都要映射对() {
        assertEquals(EngineState.Idle, NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_IDLE)))
        assertEquals(EngineState.Ready, NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_PLAYING)))
        assertEquals(EngineState.Ready, NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_PAUSED)))
        assertEquals(EngineState.Idle, NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_STOPPED)))
        assertEquals(EngineState.Ended, NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_ENDED)))
        assertTrue(NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_ERROR)) is EngineState.Error)
    }

    @Test
    fun 暂停仍然算_ready_只是没在播() {
        val mapped = NativeEngineStateMapper.map(statusOf(NativeEngineStateMapper.STATE_PAUSED))
        assertEquals(EngineState.Ready, mapped)
    }

    @Test
    fun 出错时要把中文原因原样带出来_界面直接显示它() {
        val raw = "错误：这个文件里没有能解码的视频流"
        val mapped = NativeEngineStateMapper.map(NativePlayerStatus.parse(raw))
        assertTrue(mapped is EngineState.Error)
        assertEquals("这个文件里没有能解码的视频流", (mapped as EngineState.Error).message)
    }

    @Test
    fun 状态串读不出来时要报错而不是当成空闲() {
        // 当成空闲的话，界面会永远停在"准备中"，而且没有任何线索
        assertTrue(NativeEngineStateMapper.map(null) is EngineState.Error)
    }

    @Test
    fun 不认识的状态要报错并把原文带上_便于定位契约变化() {
        val mapped = NativeEngineStateMapper.map(statusOf("缓冲中"))
        assertTrue(mapped is EngineState.Error)
        assertTrue((mapped as EngineState.Error).message.contains("缓冲中"))
    }

    @Test
    fun 状态词要与_native_侧写死的常量一致() {
        // 这六个词是 engine_jni.cpp 里 stateName 的原文；改了 C++ 忘了改 Kotlin 就会在这里红
        assertEquals("空闲", NativeEngineStateMapper.STATE_IDLE)
        assertEquals("播放中", NativeEngineStateMapper.STATE_PLAYING)
        assertEquals("已暂停", NativeEngineStateMapper.STATE_PAUSED)
        assertEquals("已停止", NativeEngineStateMapper.STATE_STOPPED)
        assertEquals("已播完", NativeEngineStateMapper.STATE_ENDED)
        assertEquals("出错", NativeEngineStateMapper.STATE_ERROR)
    }
}
