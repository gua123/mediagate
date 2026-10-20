package io.github.gua123.mediagate.media.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络统计（2026-10-20）：一行中文，弱网定位的证据。 */
class NetworkMeterTest {

    @Test
    fun 没数据时不显示空行() {
        val meter = NetworkMeter()
        assertNull(meter.line())
    }

    @Test
    fun 字节与耗时换算成中文一行() {
        val meter = NetworkMeter()
        // 1 MiB / 0.5 s ⇒ 2.0 MB/s
        meter.record(1024 * 1024, 500_000_000L)
        val line = meter.line()!!
        assertTrue(line, line.contains("1.0 MB"))
        assertTrue(line, line.contains("0.5 s"))
        assertTrue(line, line.contains("2.0 MB/s"))
    }

    @Test
    fun 清零后重新计数() {
        val meter = NetworkMeter()
        meter.record(1024 * 1024, 1_000_000_000L)
        meter.reset()
        assertNull(meter.line())
        meter.record(512 * 1024, 1_000_000_000L)
        assertTrue(meter.line()!!.contains("0.5 MB"))
    }
}
