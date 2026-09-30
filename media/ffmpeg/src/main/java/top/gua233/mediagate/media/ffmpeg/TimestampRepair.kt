package io.github.gua123.mediagate.media.ffmpeg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.File

/**
 * 时间戳重建的产物去向（R3 / R4，plan 4.3 第 2 条）。
 *
 * 「无 PCR / 拼接流」经 `-c copy -fflags +genpts` 转封装成 MP4 后，需要交给播放器消费，
 * 有两条路：
 * 1. [LocalFile]：落到 App 缓存的文件，适合本地兜底播放；
 * 2. [LoopbackProxy]：**输出到回环 HTTP 服务**——与 plan 4.6 的回环代理同一条思路，
 *    播放器吃 `http://127.0.0.1:<port>/m/...`，数据仍在 App 进程内。
 *
 * 本轮只定义形态：真正的对外暴露留给上层（用 `media:proxy` 的
 * `LoopbackHttpProxy.playUrl(backendId, path)` 生成 [LoopbackProxy.playUrl]，
 * 并把重建出的 MP4 作为该地址的数据源注册进去）。这样 `:media:ffmpeg` 不必依赖 `:media:proxy`，
 * 也不会把代理的 HTTP 细节漏进 FFmpeg 封装。
 */
sealed interface TimestampRepairTarget {

    /** 重建产物落地位置（回环代理形态下是「暂存文件」，由代理读它对外服务）。 */
    val outputFile: File

    /** 播放器可直接消费的地址；本地文件形态为 null（上层自行包 `file://`）。 */
    val playUrl: String?

    /** 落到本地文件（上层可自行决定后续是否经代理暴露）。 */
    data class LocalFile(
        override val outputFile: File,
    ) : TimestampRepairTarget {
        override val playUrl: String? get() = null
    }

    /**
     * 输出到回环 HTTP 代理。
     *
     * @param outputFile 重建出的分段 MP4 暂存文件（代理读它对外服务）。
     * @param playUrl 上层用 `LoopbackHttpProxy.playUrl(backendId, path)` 生成的地址。
     * @param backendId 后端 id（与代理注册的口径一致，便于上层做映射）。
     * @param path 后端内路径（同上）。
     */
    data class LoopbackProxy(
        override val outputFile: File,
        override val playUrl: String,
        val backendId: String,
        val path: String,
    ) : TimestampRepairTarget
}

/**
 * 一次时间戳重建的「计划」（命令 + 去向），在执行前就能看到要跑什么（R3/R4）。
 *
 * @param streamed 是否走管道形态（边转边喂给回环服务，见 [FfmpegCommand.timestampRepairToStdout]）；
 *   false 表示先落文件再暴露。
 */
data class TimestampRepairPlan(
    val command: FfmpegCommand,
    val target: TimestampRepairTarget,
    val streamed: Boolean,
) {
    val playUrl: String? get() = target.playUrl
}

/** 时间戳重建的结果（R3/R4）。 */
sealed interface TimestampRepairResult {

    /** 底层会话结果；落盘阶段就失败时为 null。 */
    val result: FfmpegResult?

    /** 产物位置与可播放地址。 */
    data class Success(
        val outputFile: File,
        val playUrl: String?,
        override val result: FfmpegResult,
    ) : TimestampRepairResult

    /** 失败（含被取消）；[cause] 只在不涉及 FFmpeg 会话时非空（落盘 / 建临时文件失败）。 */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        override val result: FfmpegResult? = null,
    ) : TimestampRepairResult
}

/**
 * 时间戳重建（R3 / R4，plan 4.3 第 2 条）：无 PCR / 拼接流边读边转封装 MP4。
 *
 * 流程：`RandomAccessSource` →（[FfmpegInputs.dumpToFile]）临时输入文件 →
 * [FfmpegCommand.timestampRepairToFile] 或 [FfmpegCommand.timestampRepairToStdout] → [FfmpegRunner] 执行 →
 * 清理临时输入文件。`-c copy` 不重编码，速度与读盘速度同量级，因此可以一边转一边播。
 *
 * 两点说明：
 * - **必须落一次临时文件**：ffmpeg-kit 只吃路径（plan 第 3 章的不变量：数据只经 [RandomAccessSource]），
 *   没有可直接交给 native 的 fd；`-c copy` 不重编码，这一步只是顺序复制。
 * - **取消**：协程取消会连带取消 FFmpeg 会话（[FfmpegKitRunner] 内部 `FFmpegKit.cancel(sessionId)`），
 *   临时文件在 finally 里删掉。
 *
 * @param workDir 临时输入文件目录（App 里传 `context.cacheDir` 下的子目录）。
 * @param bufferSize 落盘缓冲。
 */
class TimestampRepair(
    private val runner: FfmpegRunner,
    private val workDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = FfmpegInputs.DEFAULT_BUFFER_SIZE,
) {

    /**
     * 组装「重建到 [target]」的命令与去向，不执行（单测断言命令形状用）。
     *
     * @param inputFile 已落盘的输入文件（由 [FfmpegInputs.dumpToFile] 产出）。
     * @param streamed true = 管道形态（边转边喂回环服务），false = 直接写 [TimestampRepairTarget.outputFile]。
     */
    fun plan(
        inputFile: File,
        target: TimestampRepairTarget,
        durationHintMs: Long? = null,
        streamed: Boolean = false,
    ): TimestampRepairPlan {
        val command = if (streamed) {
            FfmpegCommand.timestampRepairToStdout(inputFile).withExpectedDuration(durationHintMs)
        } else {
            FfmpegCommand.timestampRepairToFile(inputFile, target.outputFile).withExpectedDuration(durationHintMs)
        }
        return TimestampRepairPlan(command = command, target = target, streamed = streamed)
    }

    /** 重建到本地文件（plan 4.3 第 2 条的落盘形态）。 */
    suspend fun repairToFile(
        source: RandomAccessSource,
        outFile: File,
        onProgress: ((FfmpegProgress) -> Unit)? = null,
        durationHintMs: Long? = null,
    ): TimestampRepairResult = withContext(io) {
        repair(source, TimestampRepairTarget.LocalFile(outFile), onProgress, durationHintMs, streamed = false)
    }

    /**
     * 重建并输出到回环代理形态（plan 4.3 第 2 条 + 4.6）。
     *
     * 产物先写 [TimestampRepairTarget.LoopbackProxy.outputFile]（分段 MP4，可边写边读），
     * 上线由上层把该文件注册进回环代理；[TimestampRepairTarget.LoopbackProxy.playUrl] 原样回传。
     */
    suspend fun repairToLoopback(
        source: RandomAccessSource,
        target: TimestampRepairTarget.LoopbackProxy,
        onProgress: ((FfmpegProgress) -> Unit)? = null,
        durationHintMs: Long? = null,
        streamed: Boolean = false,
    ): TimestampRepairResult = withContext(io) {
        repair(source, target, onProgress, durationHintMs, streamed)
    }

    private suspend fun repair(
        source: RandomAccessSource,
        target: TimestampRepairTarget,
        onProgress: ((FfmpegProgress) -> Unit)?,
        durationHintMs: Long?,
        streamed: Boolean,
    ): TimestampRepairResult {
        var temporary: File? = null
        try {
            target.outputFile.parentFile?.mkdirs()
            temporary = FfmpegInputs.createTemporaryInput(workDir)
            FfmpegInputs.dumpToFile(source, temporary, bufferSize)
            val prepared = plan(temporary, target, durationHintMs, streamed)
            val result = runner.run(prepared.command, onProgress)
            return when (result.outcome) {
                FfmpegOutcome.SUCCESS -> TimestampRepairResult.Success(target.outputFile, target.playUrl, result)
                FfmpegOutcome.CANCELLED -> TimestampRepairResult.Failure("时间戳重建已取消", result = result)
                FfmpegOutcome.FAILED -> TimestampRepairResult.Failure(
                    message = "时间戳重建失败：${FfmpegOutput.describeReturnCode(result.returnCode)}" +
                        result.output.trim().takeIf { it.isNotEmpty() }?.let { "；$it" }.orEmpty(),
                    result = result,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return TimestampRepairResult.Failure(
                message = "时间戳重建失败：${t.message ?: t.javaClass.simpleName}",
                cause = t,
            )
        } finally {
            temporary?.delete()
        }
    }
}
