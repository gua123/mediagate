package io.github.gua123.mediagate.media.ffmpeg

import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.File

/** 内存 [RandomAccessSource]：语义与真实后端一致，用于纯 JVM 单测。 */
internal class MemorySource(
    private val data: ByteArray,
    override val size: Long = data.size.toLong(),
) : RandomAccessSource {

    @Volatile
    var closed: Boolean = false
        private set

    override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset < 0 || offset >= data.size) return -1
        val n = minOf(len.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buf, off, n)
        return n
    }

    override suspend fun readFully(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= data.size) return ByteArray(0)
        val n = minOf(len.toLong(), data.size - offset).toInt()
        return data.copyOfRange(offset.toInt(), offset.toInt() + n)
    }

    override fun close() {
        closed = true
    }
}

/**
 * 假的 [FfmpegRunner]：记录命令、在「执行时」抓取输入文件内容、按脚本回调进度。
 *
 * 用它就能在没有设备的情况下验证 TimestampRepair 的完整流程（落盘 → 组命令 → 收结果 → 清理）。
 */
internal class RecordingFfmpegRunner(
    private val outcome: FfmpegOutcome = FfmpegOutcome.SUCCESS,
    private val writeOutputBytes: ByteArray? = byteArrayOf(0x11, 0x22, 0x33),
    private val progressScript: List<FfmpegProgress> = emptyList(),
    private val throwOnRun: Throwable? = null,
) : FfmpegRunner {

    /** 按调用顺序记录的命令。 */
    val commands = mutableListOf<FfmpegCommand>()

    /** run 被调用的那一刻，`-i` 指向的文件内容（用来证明「先落盘再跑」）。 */
    var inputBytesAtRun: ByteArray? = null

    /** 最后一次进度回调。 */
    var lastProgress: FfmpegProgress? = null

    /** 已取消的会话 id。 */
    val cancelledSessions = mutableListOf<Long>()

    var cancelAllCount = 0
        private set

    override val lastSessionId: Long? = SESSION_ID

    override suspend fun run(
        command: FfmpegCommand,
        onProgress: ((FfmpegProgress) -> Unit)?,
    ): FfmpegResult {
        commands += command
        throwOnRun?.let { throw it }
        val indexOfInput = command.arguments.indexOf("-i")
        val inputPath = command.arguments.getOrNull(indexOfInput + 1)
        inputBytesAtRun = inputPath?.let { path -> File(path).takeIf { it.isFile }?.readBytes() }
        for (progress in progressScript) {
            onProgress?.invoke(progress)
            lastProgress = progress
        }
        val output = command.outputFile
        if (output != null && writeOutputBytes != null) {
            output.parentFile?.mkdirs()
            output.writeBytes(writeOutputBytes)
        }
        return FfmpegResult(
            sessionId = SESSION_ID,
            returnCode = returnCodeOf(outcome),
            outcome = outcome,
            output = if (outcome == FfmpegOutcome.FAILED) "moov atom not found" else "",
            failStackTrace = null,
            durationMs = 7L,
            command = command,
        )
    }

    override fun cancel(sessionId: Long) {
        cancelledSessions += sessionId
    }

    override fun cancelAll() {
        cancelAllCount++
    }

    private companion object {
        const val SESSION_ID = 4242L

        fun returnCodeOf(outcome: FfmpegOutcome): Int = when (outcome) {
            FfmpegOutcome.SUCCESS -> 0
            FfmpegOutcome.CANCELLED -> FfmpegOutcome.CANCEL_RETURN_CODE
            FfmpegOutcome.FAILED -> 1
        }
    }
}
