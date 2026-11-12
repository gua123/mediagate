package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 探针结论的判据（2026-10-03 用户要求：不能跑的内核就别让切，而且测试本身不能把 App 弄崩）。
 *
 * **2026-10-03 改版**：判据从"看系统记录的退出原因"改成**只看探针留下的三个文件**——
 * 真机上反射读 `ApplicationExitInfo` 拿不到东西，于是探针明明被带走了却报"没得到结论"，
 * 用户按了按钮还是不知道该不该切。
 */
class VlcProbeVerdictTest {

    @Test
    fun `探针写下了 ok 就是可用`() {
        assertEquals(VlcProbeVerdict.OK, vlcProbeVerdict(started = true, succeeded = true, finished = true))
    }

    @Test
    fun `没测过就是未知`() {
        assertEquals(VlcProbeVerdict.UNKNOWN, vlcProbeVerdict(started = false, succeeded = false, finished = false))
    }

    @Test
    fun `开始了、没写成 ok、也没写 done —— 进程被带走了（真机就是这种）`() {
        assertEquals(VlcProbeVerdict.FAILED, vlcProbeVerdict(started = true, succeeded = false, finished = false))
    }

    @Test
    fun `开始了、跑到底了但没写成 ok —— LibVLC 抛了 Java 异常，同样算起不来`() {
        assertEquals(VlcProbeVerdict.FAILED, vlcProbeVerdict(started = true, succeeded = false, finished = true))
    }

    @Test
    fun `崩溃类退出原因的判据保留（用于报告文案，不再决定结论）`() {
        assertEquals(true, isCrashLikeExit(1))
        assertEquals(true, isCrashLikeExit(4))
        assertEquals(true, isCrashLikeExit(6))
        assertEquals(true, isCrashLikeExit(8))
        assertEquals(false, isCrashLikeExit(0))
        assertEquals(false, isCrashLikeExit(3))
        assertEquals(false, isCrashLikeExit(-1))
    }
}
