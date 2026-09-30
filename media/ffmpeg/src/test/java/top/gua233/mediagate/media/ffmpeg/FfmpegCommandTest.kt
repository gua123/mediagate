package io.github.gua123.mediagate.media.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FfmpegCommand] 的 JVM 单测（R11）：三类现成构造的参数形状必须与 plan 4.3 / 4.4 / 4.7 B 逐字一致。
 *
 * 纯逻辑，不加载 ffmpeg-kit 的 native 库。测试方法名不含 `.` / `/`（Kotlin/JVM 反引号名字的硬限制）。
 */
class FfmpegCommandTest {

    private val input = File("/tmp/mg/in.ts")
    private val output = File("/tmp/mg/out.jpg")

    @Test
    fun `抽帧命令参数顺序与形状正确`() {
        val command = FfmpegCommand.frameExtraction(input, output, positionMs = 1234L, targetWidth = 320)
        val args = command.arguments
        assertEquals(FfmpegPurpose.FRAME_EXTRACTION, command.purpose)
        assertEquals(output, command.outputFile)
        assertFalse(command.writesToStdout)
        // -ss 必须在 -i 之前（快速定位，不解前面的帧）
        assertTrue("-ss 应在 -i 之前：$args", args.indexOf("-ss") < args.indexOf("-i"))
        assertEquals("1.234", args[args.indexOf("-ss") + 1])
        assertEquals(input.absolutePath, args[args.indexOf("-i") + 1])
        assertEquals("1", args[args.indexOf("-frames:v") + 1])
        assertEquals("scale=320:-2", args[args.indexOf("-vf") + 1])
        assertEquals("image2", args[args.indexOf("-f") + 1])
        assertEquals(output.absolutePath, args.last())
        assertTrue(args.take(3).containsAll(listOf("-hide_banner", "-loglevel", "error")))
    }

    @Test
    fun `抽帧宽度为零时不带 scale`() {
        val command = FfmpegCommand.frameExtraction(input, output, positionMs = 0L, targetWidth = 0)
        assertFalse(command.arguments.contains("-vf"))
        assertFalse(command.arguments.contains("scale=0:-2"))
    }

    @Test
    fun `毫秒到秒参数字符串固定三位小数`() {
        assertEquals("0.000", FfmpegCommand.secondsArgument(0L))
        assertEquals("0.000", FfmpegCommand.secondsArgument(-500L))
        assertEquals("1.000", FfmpegCommand.secondsArgument(1_000L))
        assertEquals("90.000", FfmpegCommand.secondsArgument(90_000L))
        assertEquals("3600.500", FfmpegCommand.secondsArgument(3_600_500L))
    }

    @Test
    fun `时间戳重建命令使用 copy 与 genpts`() {
        val out = File("/tmp/mg/repaired.mp4")
        val command = FfmpegCommand.timestampRepairToFile(input, out)
        val args = command.arguments
        assertEquals(FfmpegPurpose.TIMESTAMP_REPAIR, command.purpose)
        assertEquals(out, command.outputFile)
        assertEquals("copy", args[args.indexOf("-c") + 1])
        assertEquals("+genpts", args[args.indexOf("-fflags") + 1])
        assertEquals("mp4", args[args.indexOf("-f") + 1])
        assertEquals(out.absolutePath, args.last())
    }

    @Test
    fun `时间戳重建管道形态必须分片且输出到 stdout`() {
        val command = FfmpegCommand.timestampRepairToStdout(input)
        assertTrue(command.writesToStdout)
        assertNull(command.outputFile)
        val args = command.arguments
        assertEquals(FfmpegCommand.FRAGMENTED_MP4_FLAGS, args[args.indexOf("-movflags") + 1])
        assertEquals("-", args.last())
        // 管道形态可以关掉分片（用于落文件的特殊场景），此时不应带 -movflags
        assertFalse(FfmpegCommand.timestampRepairToStdout(input, fragmented = false).arguments.contains("-movflags"))
    }

    @Test
    fun `ASR 取音命令是单声道十六千赫 s16le 管道`() {
        val command = FfmpegCommand.asrPcmDecode(input)
        val args = command.arguments
        assertEquals(FfmpegPurpose.ASR_PCM_DECODE, command.purpose)
        assertTrue(command.writesToStdout)
        assertTrue(args.contains("-vn"))
        assertEquals("1", args[args.indexOf("-ac") + 1])
        assertEquals("16000", args[args.indexOf("-ar") + 1])
        assertEquals("pcm_s16le", args[args.indexOf("-acodec") + 1])
        assertEquals("s16le", args[args.indexOf("-f") + 1])
        assertEquals("-", args.last())
    }

    @Test
    fun `ASR 取音可改采样率与声道`() {
        val command = FfmpegCommand.asrPcmDecode(input, sampleRateHz = 8_000, channels = 2)
        assertEquals("8000", command.arguments[command.arguments.indexOf("-ar") + 1])
        assertEquals("2", command.arguments[command.arguments.indexOf("-ac") + 1])
        assertEquals(FfmpegCommand.DEFAULT_ASR_SAMPLE_RATE, 16_000)
    }

    @Test
    fun `自定义命令统一带基础前缀与时长估计`() {
        val command = FfmpegCommand.custom(listOf("-i", "x.mp4", "-f", "null", "-"), expectedDurationMs = 5_000L)
        assertEquals(FfmpegCommand.BASE_ARGUMENTS + listOf("-i", "x.mp4", "-f", "null", "-"), command.arguments)
        assertEquals(5_000L, command.expectedDurationMs)
        assertEquals(5_000L, command.withExpectedDuration(5_000L).expectedDurationMs)
        assertNull(command.withExpectedDuration(5_000L).copy(expectedDurationMs = null).expectedDurationMs)
    }

    @Test
    fun `命令行展示会对含空格路径加引号`() {
        val spaced = File("/tmp/mg with space/in.ts")
        val command = FfmpegCommand.frameExtraction(spaced, output, 0L)
        assertTrue(command.commandLine.startsWith("ffmpeg -hide_banner -loglevel error"))
        assertTrue(command.commandLine.contains("\"/tmp/mg with space/in.ts\""))
    }
}
