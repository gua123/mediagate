package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 探针结论的判据（2026-10-03 用户要求：不能跑的内核就别让切，而且测试本身不能把 App 弄崩）。
 */
class VlcProbeVerdictTest {

    @Test
    fun `探针写下了 ok 就是可用`() {
        assertEquals(VlcProbeVerdict.OK, vlcProbeVerdict(started = true, succeeded = true, exitReason = 4))
    }

    @Test
    fun `没测过就是未知`() {
        assertEquals(VlcProbeVerdict.UNKNOWN, vlcProbeVerdict(started = false, succeeded = false, exitReason = -1))
    }

    @Test
    fun `开始了但没有 ok 且系统记为原生崩溃 —— 本机不可用`() {
        assertEquals(VlcProbeVerdict.FAILED, vlcProbeVerdict(started = true, succeeded = false, exitReason = 4))
        assertEquals(VlcProbeVerdict.FAILED, vlcProbeVerdict(started = true, succeeded = false, exitReason = 1))
        assertEquals(VlcProbeVerdict.FAILED, vlcProbeVerdict(started = true, succeeded = false, exitReason = 8))
    }

    @Test
    fun `开始了但没有 ok、只是被系统回收 —— 不算不可用（下次再测）`() {
        assertEquals(VlcProbeVerdict.UNKNOWN, vlcProbeVerdict(started = true, succeeded = false, exitReason = 3)) // LOW_MEMORY
        assertEquals(VlcProbeVerdict.UNKNOWN, vlcProbeVerdict(started = true, succeeded = false, exitReason = -1))
    }

    @Test
    fun `崩溃类原因的边界`() {
        assertEquals(true, isCrashLikeExit(1))
        assertEquals(true, isCrashLikeExit(4))
        assertEquals(true, isCrashLikeExit(6))
        assertEquals(true, isCrashLikeExit(8))
        assertEquals(false, isCrashLikeExit(0))
        assertEquals(false, isCrashLikeExit(2))
        assertEquals(false, isCrashLikeExit(3))
        assertEquals(false, isCrashLikeExit(-1))
    }
}
