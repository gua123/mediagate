package io.github.gua123.mediagate.media.ffmpeg

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.File
import java.io.IOException

/**
 * [TimestampRepair] 的 JVM 单测（R3/R4，R11）。
 *
 * 真跑 FFmpeg 需要设备，这里用 [RecordingFfmpegRunner] 验证「落盘 → 组命令 → 收结果 → 清理」全流程，
 * 以及失败/取消/落盘异常三条分支不吞错。
 */
class TimestampRepairTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val payload = ByteArray(300) { (it % 251).toByte() }

    private fun workDir(): File = temporary.newFolder("work")

    @Test
    fun `成功路径先落盘再转封装并清理临时输入`() = runTest {
        val runner = RecordingFfmpegRunner()
        val work = workDir()
        val repair = TimestampRepair(runner, work)
        val outFile = File(temporary.root, "out/repaired.mp4")

        val result = repair.repairToFile(MemorySource(payload), outFile)

        assertTrue("结果是 Success：$result", result is TimestampRepairResult.Success)
        val success = result as TimestampRepairResult.Success
        assertEquals(outFile, success.outputFile)
        assertNull(success.playUrl)
        assertEquals(1, runner.commands.size)
        val command = runner.commands.single()
        assertEquals(FfmpegPurpose.TIMESTAMP_REPAIR, command.purpose)
        assertEquals("+genpts", command.arguments[command.arguments.indexOf("-fflags") + 1])
        // run 被调用的那一刻，-i 指向的临时文件里已经是我们整份数据
        assertEquals(payload.size, runner.inputBytesAtRun?.size)
        assertArrayEquals(payload, runner.inputBytesAtRun!!)
        // 临时输入文件用完即删（plan 4.10：中间件不长期占地）
        assertTrue("workDir 不应残留：${work.list()?.toList()}", work.listFiles().isNullOrEmpty())
    }

    @Test
    fun `失败时返回 Failure 并带上 FFmpeg 输出`() = runTest {
        val runner = RecordingFfmpegRunner(outcome = FfmpegOutcome.FAILED)
        val repair = TimestampRepair(runner, workDir())

        val result = repair.repairToFile(MemorySource(payload), File(temporary.root, "bad.mp4"))

        assertTrue(result is TimestampRepairResult.Failure)
        val failure = result as TimestampRepairResult.Failure
        assertTrue(failure.message, failure.message.contains("moov atom not found"))
        assertNotNull(failure.result)
        assertEquals(FfmpegOutcome.FAILED, failure.result!!.outcome)
    }

    @Test
    fun `被取消时返回 Failure 而不是成功`() = runTest {
        val runner = RecordingFfmpegRunner(outcome = FfmpegOutcome.CANCELLED)
        val repair = TimestampRepair(runner, workDir())

        val result = repair.repairToFile(MemorySource(payload), File(temporary.root, "cancelled.mp4"))

        val failure = result as TimestampRepairResult.Failure
        assertEquals("时间戳重建已取消", failure.message)
        assertEquals(FfmpegOutcome.CANCELLED, failure.result!!.outcome)
    }

    @Test
    fun `回环代理形态回传播放地址`() = runTest {
        val runner = RecordingFfmpegRunner()
        val repair = TimestampRepair(runner, workDir())
        val staging = File(temporary.root, "loopback/repaired.mp4")
        val target = TimestampRepairTarget.LoopbackProxy(
            outputFile = staging,
            playUrl = "http://127.0.0.1:43210/m/local-file%3A%2Fsdcard/repaired.mp4",
            backendId = "local-file:/sdcard",
            path = "/repaired.mp4",
        )

        val result = repair.repairToLoopback(MemorySource(payload), target)

        val success = result as TimestampRepairResult.Success
        assertEquals(staging, success.outputFile)
        assertEquals(target.playUrl, success.playUrl)
        assertEquals(staging.absolutePath, runner.commands.single().arguments.last())
    }

    @Test
    fun `plan 管道形态输出到 stdout 且带时长估计`() {
        val runner = RecordingFfmpegRunner()
        val repair = TimestampRepair(runner, workDir())
        val input = File(temporary.root, "in.ts")
        input.writeBytes(payload)
        val staging = File(temporary.root, "stream.mp4")

        val plan = repair.plan(
            inputFile = input,
            target = TimestampRepairTarget.LoopbackProxy(staging, "http://127.0.0.1:1/m/x", "local", "/x"),
            durationHintMs = 90_000L,
            streamed = true,
        )

        assertTrue(plan.streamed)
        assertTrue(plan.command.writesToStdout)
        assertNull(plan.command.outputFile)
        assertEquals(90_000L, plan.command.expectedDurationMs)
        assertEquals("http://127.0.0.1:1/m/x", plan.playUrl)
        // plan 只是组装，不执行
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `进度回调原样透传`() = runTest {
        val script = listOf(
            FfmpegProgress(1_000L, 1.0, 0.1),
            FfmpegProgress(5_000L, 2.0, 0.5),
        )
        val runner = RecordingFfmpegRunner(progressScript = script)
        val repair = TimestampRepair(runner, workDir())
        val seen = mutableListOf<FfmpegProgress>()

        repair.repairToFile(
            source = MemorySource(payload),
            outFile = File(temporary.root, "progress.mp4"),
            onProgress = { seen += it },
        )

        assertEquals(script, seen)
        assertEquals(script.last(), runner.lastProgress)
    }

    @Test
    fun `落盘失败时不发命令且不留临时文件`() = runTest {
        val runner = RecordingFfmpegRunner()
        val work = workDir()
        val repair = TimestampRepair(runner, work)
        val broken = object : RandomAccessSource {
            override val size: Long = 128L
            override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
                throw IOException("网络中断")
            override suspend fun readFully(offset: Long, len: Int): ByteArray = throw IOException("网络中断")
            override fun close() = Unit
        }

        val result = repair.repairToFile(broken, File(temporary.root, "never.mp4"))

        val failure = result as TimestampRepairResult.Failure
        assertTrue(failure.message, failure.message.contains("网络中断"))
        assertNotNull(failure.cause)
        assertTrue(runner.commands.isEmpty())
        assertTrue(work.listFiles().isNullOrEmpty())
    }

    @Test
    fun `runner 自身抛错被兜成 Failure`() = runTest {
        val runner = RecordingFfmpegRunner(throwOnRun = IllegalStateException("ffmpeg-kit 未初始化"))
        val repair = TimestampRepair(runner, workDir())

        val result = repair.repairToFile(MemorySource(payload), File(temporary.root, "boom.mp4"))

        val failure = result as TimestampRepairResult.Failure
        assertTrue(failure.message, failure.message.contains("ffmpeg-kit 未初始化"))
        assertNull(failure.result)
    }

    @Test
    fun `未知总长度也能落盘`() = runTest {
        val runner = RecordingFfmpegRunner()
        val repair = TimestampRepair(runner, workDir())
        val unknownSize = MemorySource(payload, size = -1L)

        val result = repair.repairToFile(unknownSize, File(temporary.root, "unknown.mp4"))

        assertTrue(result is TimestampRepairResult.Success)
        assertArrayEquals(payload, runner.inputBytesAtRun!!)
    }

    @Test
    fun `取消协程会向上抛 CancellationException`() = runTest {
        val runner = RecordingFfmpegRunner(throwOnRun = kotlinx.coroutines.CancellationException("离屏取消"))
        val repair = TimestampRepair(runner, workDir())

        var cancelled = false
        try {
            repair.repairToFile(MemorySource(payload), File(temporary.root, "cancel.mp4"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue("取消必须向上抛，不能被兜成 Failure", cancelled)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("长度不一致", expected.size, actual.size)
        assertFalse("内容不一致", expected.indices.any { expected[it] != actual[it] })
    }
}
