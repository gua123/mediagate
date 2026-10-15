package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研引擎启动探针的判据（纯函数）。
 *
 * 为什么值得钉住：探针的全部意义就是"别让用户切到一个在本机起不来的内核"，
 * 判错了要么白拦（用户用不上新引擎），要么放行（点一下就播不了）。
 */
class EngineProbeVerdictTest {

    @Test
    fun 写了_ok_就是通过() {
        assertEquals(EngineProbeVerdict.OK, engineProbeVerdict(started = true, succeeded = true, finished = true))
        assertEquals(ProbeOutcome.OK, probeOutcome(started = true, succeeded = true, finished = false))
    }

    @Test
    fun 没开始过算未知() {
        assertEquals(EngineProbeVerdict.UNKNOWN, engineProbeVerdict(started = false, succeeded = false, finished = false))
    }

    @Test
    fun 开始过但没写成_ok_就是不通过() {
        // 跑到底抛了 Java 异常
        assertEquals(EngineProbeVerdict.FAILED, engineProbeVerdict(started = true, succeeded = false, finished = true))
        // 进程在自检中途被带走（连 done 都没写）
        assertEquals(EngineProbeVerdict.FAILED, engineProbeVerdict(started = true, succeeded = false, finished = false))
    }

    @Test
    fun 两个探针共用同一套判据() {
        // LibVLC 与自研引擎的探针只是"跑什么"不同，判据必须一致（否则两处会慢慢漂移）
        for (started in listOf(true, false)) {
            for (succeeded in listOf(true, false)) {
                for (finished in listOf(true, false)) {
                    val outcome = probeOutcome(started, succeeded, finished)
                    val vlc = vlcProbeVerdict(started, succeeded, finished)
                    val engine = engineProbeVerdict(started, succeeded, finished)
                    assertEquals(
                        "start=$started ok=$succeeded done=$finished 两个探针结论不一致",
                        outcome.name,
                        vlc.name,
                    )
                    assertEquals(outcome.name, engine.name)
                }
            }
        }
    }

    @Test
    fun 诊断那一行四种情形都要说人话() {
        val ok = engineProbeLine(EngineProbeVerdict.OK, report = "FFmpeg 7.1；音频 48000 Hz 正常")
        assertTrue(ok.contains("通过"))
        assertTrue("通过时要带上自检摘要（真机上截图就是证据）", ok.contains("FFmpeg 7.1"))

        val failed = engineProbeLine(EngineProbeVerdict.FAILED, reason = "音频设备打不开")
        assertTrue(failed.contains("没通过"))
        assertTrue(failed.contains("音频设备打不开"))

        val crashed = engineProbeLine(EngineProbeVerdict.FAILED)
        assertTrue("没留下原因也要说清是「进程被带走」", crashed.contains("被带走"))

        val unknown = engineProbeLine(EngineProbeVerdict.UNKNOWN)
        assertTrue(unknown.contains("还没测"))
    }
}
