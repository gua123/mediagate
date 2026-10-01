package io.github.gua123.mediagate.media.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [FfmpegOutput] 的 JVM 单测（R11）：返回码归类、时间码/倍速/统计行解析、进度换算。
 *
 * 这些纯函数是「真机上唯一可能出错又无法在设备上穷举」的部分，所以在这里覆盖边界与畸形输入。
 */
class FfmpegOutputTest {

    @Test
    fun `返回码归类`() {
        assertEquals(FfmpegOutcome.SUCCESS, FfmpegOutcome.of(0))
        assertEquals(FfmpegOutcome.CANCELLED, FfmpegOutcome.of(255))
        assertEquals(FfmpegOutcome.FAILED, FfmpegOutcome.of(1))
        assertEquals(FfmpegOutcome.FAILED, FfmpegOutcome.of(-1))
        assertTrue(FfmpegOutcome.of(0).isSuccess)
        assertTrue(FfmpegOutcome.of(255).isCancelled)
        assertFalse(FfmpegOutcome.of(1).isSuccess)
        assertEquals("已取消", FfmpegOutput.describeReturnCode(255))
        assertTrue(FfmpegOutput.describeReturnCode(3).contains("3"))
    }

    @Test
    fun `时间码解析覆盖时分秒与异常`() {
        assertEquals(0L, FfmpegOutput.parseTimecode("00:00:00.00"))
        assertEquals(4_000L, FfmpegOutput.parseTimecode("00:00:04.00"))
        assertEquals(3_723_500L, FfmpegOutput.parseTimecode("01:02:03.50"))
        assertEquals(62_000L, FfmpegOutput.parseTimecode("01:02"))
        assertEquals(-1_000L, FfmpegOutput.parseTimecode("-00:00:01.00"))
        assertNull(FfmpegOutput.parseTimecode("N/A"))
        assertNull(FfmpegOutput.parseTimecode(""))
        assertNull(FfmpegOutput.parseTimecode(null))
        assertNull(FfmpegOutput.parseTimecode("abc"))
        assertNull(FfmpegOutput.parseTimecode("1:2:3:4"))
    }

    @Test
    fun `倍速解析`() {
        assertEquals(1.5, FfmpegOutput.parseSpeed("1.5x")!!, 1e-9)
        assertEquals(250.0, FfmpegOutput.parseSpeed(" 250x")!!, 1e-9)
        assertEquals(0.98, FfmpegOutput.parseSpeed("0.98X")!!, 1e-9)
        assertNull(FfmpegOutput.parseSpeed("N/A"))
        assertNull(FfmpegOutput.parseSpeed(""))
        assertNull(FfmpegOutput.parseSpeed("fast"))
    }

    @Test
    fun `进度换算夹在 0 到 1 且总时长未知返回 null`() {
        assertEquals(0.5, FfmpegOutput.fractionOf(5_000L, 10_000L)!!, 1e-9)
        assertEquals(0.0, FfmpegOutput.fractionOf(-1L, 10_000L) ?: 0.0, 1e-9)
        assertEquals(1.0, FfmpegOutput.fractionOf(20_000L, 10_000L)!!, 1e-9)
        assertNull(FfmpegOutput.fractionOf(5_000L, null))
        assertNull(FfmpegOutput.fractionOf(5_000L, 0L))
        assertEquals(50, FfmpegProgress(5_000L, 1.0, 0.5).percent)
        assertEquals(-1, FfmpegProgress(5_000L, 1.0, null).percent)
    }

    @Test
    fun `统计行解析出全部字段`() {
        val line = "frame=  250 fps= 25 q=28.0 size=    1024kB time=00:00:10.00 bitrate= 838.9kbits/s speed=1.01x"
        val progress = FfmpegOutput.parseStatisticsLine(line, totalDurationMs = 20_000L)!!
        assertEquals(10_000L, progress.positionMs)
        assertEquals(250, progress.frameNumber)
        assertEquals(1.01, progress.speed, 1e-9)
        assertEquals(0.5, progress.fraction!!, 1e-9)
        assertEquals(838.9, progress.bitrateKbps, 1e-6)
        assertEquals(1024L * 1024L, progress.sizeBytes)
    }

    @Test
    fun `copy 命令的统计行没有 frame 字段也能解析`() {
        val line = "size=   20480kB time=00:01:00.00 bitrate=2796.2kbits/s speed= 120x"
        val progress = FfmpegOutput.parseStatisticsLine(line)!!
        assertEquals(60_000L, progress.positionMs)
        assertEquals(-1, progress.frameNumber)
        assertEquals(120.0, progress.speed, 1e-9)
        assertNull(progress.fraction) // 没给总时长 → 不做假进度
    }

    @Test
    fun `统计行畸形输入不会崩`() {
        assertNull(FfmpegOutput.parseStatisticsLine(""))
        assertNull(FfmpegOutput.parseStatisticsLine("Press [q] to stop, [?] for help"))
        val nbsp = FfmpegOutput.parseStatisticsLine("time=N/A speed=N/A")
        assertEquals(-1L, nbsp!!.positionMs)
        assertEquals(-1.0, nbsp.speed, 1e-9)
        assertNull(nbsp.fraction)
    }

    @Test
    fun `Statistics 数值换算成进度`() {
        val progress = FfmpegOutput.fromStatistics(timeSeconds = 12.5, speed = 2.0, totalDurationMs = 25_000L, frameNumber = 300)
        assertEquals(12_500L, progress.positionMs)
        assertEquals(2.0, progress.speed, 1e-9)
        assertEquals(0.5, progress.fraction!!, 1e-9)
        assertEquals(300, progress.frameNumber)
        // 负数 / 非法值按「未知」处理
        val unknown = FfmpegOutput.fromStatistics(timeSeconds = Double.NaN, speed = -1.0, totalDurationMs = null)
        assertEquals(-1L, unknown.positionMs)
        assertEquals(-1.0, unknown.speed, 1e-9)
        assertNull(unknown.fraction)
    }

    @Test
    fun `Duration 行解析`() {
        val line = "  Duration: 00:02:00.05, start: 0.000000, bitrate: 1234 kb/s"
        assertEquals(120_050L, FfmpegOutput.parseDurationLine(line))
        assertNull(FfmpegOutput.parseDurationLine("  Stream #0:0: Video: h264"))
    }

    @Test
    fun `码率与体积解析`() {
        assertEquals(838.9, FfmpegOutput.parseBitrateKbps("838.9kbits/s"), 1e-6)
        assertEquals(1200.0, FfmpegOutput.parseBitrateKbps("1.2Mbits/s"), 1e-6)
        assertEquals(-1.0, FfmpegOutput.parseBitrateKbps("N/A"), 1e-9)
        assertEquals(1024L, FfmpegOutput.parseSizeBytes("1kB"))
        assertEquals(1_572_864L, FfmpegOutput.parseSizeBytes("1.5MiB"))
        assertEquals(-1L, FfmpegOutput.parseSizeBytes("N/A"))
    }

    @Test
    fun `解析不受系统 Locale 影响`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // 德语用逗号做小数点
            assertEquals("1.234", FfmpegCommand.secondsArgument(1_234L))
            assertEquals(4_000L, FfmpegOutput.parseTimecode("00:00:04.00"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
