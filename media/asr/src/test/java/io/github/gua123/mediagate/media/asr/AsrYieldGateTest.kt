package io.github.gua123.mediagate.media.asr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放让路的 JVM 单测（**M7-B / R19 的关键约束：不影响看视频**）。
 *
 * 四种组合（在播/不在播 × 开关开/关）与「暂停等播放结束」的挂起-恢复都在这里穷举。
 */
class AsrYieldGateTest {

    private val playing = MutableStateFlow(false)
    private val settings = MutableStateFlow(AsrYieldSettings())

    private fun gate() = PlaybackYieldGate(playing, settings)

    @Test
    fun policy_usesRequestedThreadsWhenNotPlaying() {
        val policy = AsrYieldPolicy.of(playing = false, AsrYieldSettings(enabled = true), requestedThreads = 4)
        assertEquals(4, policy.threadCount)
        assertTrue(policy.backgroundPriority)
        assertFalse(policy.hold)
        assertFalse(policy.yielding)
    }

    @Test
    fun policy_dropsToOneThreadWhenPlaying() {
        val policy = AsrYieldPolicy.of(playing = true, AsrYieldSettings(enabled = true), requestedThreads = 4)
        assertEquals(AsrYieldPolicy.YIELD_THREADS, policy.threadCount)
        assertTrue(policy.yielding)
        assertFalse(policy.hold)
        assertTrue(policy.backgroundPriority)
    }

    @Test
    fun policy_keepsFullSpeedWhenYieldDisabled() {
        val policy = AsrYieldPolicy.of(playing = true, AsrYieldSettings(enabled = false), requestedThreads = 4)
        assertEquals(4, policy.threadCount)
        assertFalse(policy.yielding)
    }

    @Test
    fun policy_holdsWhenPauseWhilePlayingIsOn() {
        val policy = AsrYieldPolicy.of(
            playing = true,
            AsrYieldSettings(enabled = true, pauseWhilePlaying = true),
            requestedThreads = 2,
        )
        assertTrue(policy.hold)
        assertEquals(1, policy.threadCount)
    }

    @Test
    fun policy_clampsRequestedThreads() {
        assertEquals(
            WhisperNative.MAX_THREADS,
            AsrYieldPolicy.of(false, AsrYieldSettings(enabled = false), 99).threadCount,
        )
        assertEquals(
            WhisperNative.MIN_THREADS,
            AsrYieldPolicy.of(false, AsrYieldSettings(enabled = false), 0).threadCount,
        )
    }

    @Test
    fun gate_followsThePlayingFlow() = runTest {
        val gate = gate()
        assertFalse(gate.yielding.first())
        playing.value = true
        assertTrue(gate.yielding.first())
        assertEquals(1, gate.policy(4).threadCount)
        settings.value = AsrYieldSettings(enabled = false)
        assertFalse(gate.yielding.first())
        assertEquals(4, gate.policy(4).threadCount)
    }

    @Test
    fun awaitTurn_returnsImmediatelyWhenNotHolding() = runTest {
        val gate = gate()
        gate.awaitTurn()
        playing.value = true
        // 只降线程、不暂停 → 依然立刻返回
        gate.awaitTurn()
        assertTrue(true)
    }

    @Test
    fun awaitTurn_suspendsUntilPlaybackStops() = runTest {
        playing.value = true
        settings.value = AsrYieldSettings(enabled = true, pauseWhilePlaying = true)
        val gate = gate()
        var resumed = false
        val job = launch {
            gate.awaitTurn()
            resumed = true
        }
        runCurrent()
        assertFalse(resumed)
        playing.value = false
        advanceUntilIdle()
        assertTrue(resumed)
        job.cancel()
    }

    @Test
    fun describe_mentionsYieldOnlyWhenYielding() {
        val gate = gate()
        assertEquals("", gate.describe())
        playing.value = true
        assertTrue(gate.describe().contains("让路"))
        assertEquals("", gate.describe(AsrYieldPolicy.of(false, AsrYieldSettings(), 4)))
    }
}
