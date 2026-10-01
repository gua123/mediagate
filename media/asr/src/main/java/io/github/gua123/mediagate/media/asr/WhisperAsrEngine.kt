package io.github.gua123.mediagate.media.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.gua123.mediagate.media.ffmpeg.FfmpegCommand
import io.github.gua123.mediagate.media.ffmpeg.FfmpegRunner
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import java.io.File

/**
 * 识别失败（**R19 的失败分类**）——把「该显示哪句中文」从执行代码里抽出来。
 *
 * @property kind 分类（无音轨 / 引擎不可用 / 解码失败…）。
 */
class AsrFailureException(
    val kind: AsrFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 一次单文件识别请求（**M7-B / R14**）。 */
data class AsrRequest(
    /** 音源：本地绝对路径，或 FFmpeg 认得的 URL（远端取音走回环代理时就是回环 URL）。 */
    val source: String,
    /** 音轨时长（毫秒）；<= 0 时按「没有音轨」处理。 */
    val durationMs: Long,
    /** 模型档位（决定期望字节数）。 */
    val model: WhisperModel,
    /** 模型文件绝对路径。 */
    val modelPath: String,
    /** 识别语言；空串 = 自动检测。 */
    val language: String = WhisperContext.DEFAULT_LANGUAGE,
    /** 是否翻译成英语。 */
    val translate: Boolean = false,
)

/** 一次识别的产出（**R14**）。 */
data class AsrRunResult(
    /** 合并好的字幕条目（可直接交给 [AsrOutput] 写文件）。 */
    val cues: List<SubtitleCue>,
    /** 识别出的原始段数（诊断用）。 */
    val segmentCount: Int,
    /** 完整识别的时长（毫秒）；取消时是已完成的部分。 */
    val recognizedMs: Long,
    /** 是否被取消（[cues] 是**部分结果**）。 */
    val cancelled: Boolean,
)

/**
 * 单文件识别执行器（**M7-B**）。
 *
 * 抽成接口是为了让队列与服务只管调度，JVM 单测可以塞一个同步返回的假执行器，
 * 不必真的跑 FFmpeg 与 whisper。
 */
interface AsrEngine {

    /** 识别一条，边跑边回调进度。协程被取消时抛 CancellationException。 */
    suspend fun transcribe(request: AsrRequest, onProgress: (AsrProgress) -> Unit): AsrRunResult
}

/**
 * 把音轨解成 16 kHz 单声道 PCM 文件（**R11 的 ASR 取音**）。
 *
 * 之所以是接口：真机用 FFmpeg 解（[FfmpegPcmProvider]），单测直接写一个几 KB 的假 PCM 文件。
 */
interface AsrPcmProvider {

    /**
     * 解码 [source] 到 [output]（pcm_s16le / 16 kHz / 单声道）。
     *
     * @return true = 解出了内容。
     */
    suspend fun decode(
        source: String,
        output: File,
        expectedDurationMs: Long?,
        onProgress: (AsrProgress) -> Unit,
    ): Boolean
}

/**
 * FFmpeg 实现（真机路径）：-vn -ac 1 -ar 16000 -acodec pcm_s16le -f s16le 写文件。
 *
 * 与 [FfmpegCommand.asrPcmDecode]（走 stdout 管道）的关系：口径一模一样，只是产物落文件。
 * 落文件是刻意的——窗口化识别要反复回头读（30 s 窗口 + 5 s 重叠），管道读完就没了。
 *
 * @param runner 复用 :media:ffmpeg 的执行器（真机是 FfmpegKitRunner）。
 */
class FfmpegPcmProvider(private val runner: FfmpegRunner) : AsrPcmProvider {

    override suspend fun decode(
        source: String,
        output: File,
        expectedDurationMs: Long?,
        onProgress: (AsrProgress) -> Unit,
    ): Boolean {
        output.parentFile?.mkdirs()
        val command = FfmpegCommand.custom(
            arguments = listOf(
                "-i", source,
                "-vn",
                "-ac", "1",
                "-ar", AsrPcm.SAMPLE_RATE.toString(),
                "-acodec", "pcm_s16le",
                "-f", "s16le",
                "-y", output.absolutePath,
            ),
            outputFile = output,
            expectedDurationMs = expectedDurationMs,
        )
        val result = runner.run(command) { progress ->
            onProgress(AsrPipeline.progressOf(progress.positionMs.coerceAtLeast(0L), expectedDurationMs ?: 0L))
        }
        return result.isSuccess && output.isFile && output.length() > 0L
    }
}

/**
 * whisper.cpp 的单文件识别实现（**M7-B / R14**）。
 *
 * 把已经纯函数化的四步串起来（plan 4.7 B 的流水线）：
 * FFmpeg 解 16 kHz 单声道 PCM → [AsrPipeline.planWindows] 切 30 s 窗口（5 s 重叠）→
 * 每窗读 PCM 交 [WhisperContext] 识别 → [AsrPipeline.mergeWindows] 合并时间轴 →
 * [AsrPipeline.toCues] 生成 SubtitleCue。
 *
 * **真机才能验证的部分**：真模型推理、JNI 运行时行为、播放让路对实际帧率的影响。
 * 本类保证的是「上面那四步的接线」以及每一步的**参数口径**与单测一致。
 *
 * @param pcmProvider 取音实现（真机 = [FfmpegPcmProvider]）。
 * @param workDir PCM 临时目录（一般是 cacheDir/asr；跑完即删）。
 * @param yieldGate 播放让路闸门；null = 不让路。
 * @param requestedThreads 满速线程数（默认 4，plan 4.12）。
 */
class WhisperAsrEngine(
    private val pcmProvider: AsrPcmProvider,
    private val workDir: File,
    private val yieldGate: PlaybackYieldGate? = null,
    private val requestedThreads: Int = WhisperNative.DEFAULT_THREADS,
    private val cueOptions: AsrCueOptions = AsrCueOptions(),
) : AsrEngine {

    override suspend fun transcribe(request: AsrRequest, onProgress: (AsrProgress) -> Unit): AsrRunResult =
        run(request, onProgress, keepPartialOnCancel = false)

    /**
     * 同 [transcribe]，但**取消时保留部分结果**（R14/R19：取消后已识别的部分不白费）。
     *
     * 取消（协程被 cancel）时返回 [AsrRunResult.cancelled] = true 与已识别窗口的字幕；
     * 一条窗口都没跑完时 [AsrRunResult.cues] 为空，调用方据此提示而不是写空文件。
     */
    suspend fun transcribePartial(request: AsrRequest, onProgress: (AsrProgress) -> Unit): AsrRunResult =
        run(request, onProgress, keepPartialOnCancel = true)

    private suspend fun run(
        request: AsrRequest,
        onProgress: (AsrProgress) -> Unit,
        keepPartialOnCancel: Boolean,
    ): AsrRunResult {
        val totalMs = request.durationMs
        val windows = AsrPipeline.planWindows(totalMs)
        if (windows.isEmpty()) {
            throw AsrFailureException(AsrFailureKind.NO_AUDIO_TRACK, "解不出音轨时长，可能没有音轨")
        }
        if (!File(request.modelPath).isFile) {
            throw AsrFailureException(AsrFailureKind.MODEL_MISSING, "模型还没下载：" + request.modelPath)
        }
        if (!WhisperNative.isAvailable()) {
            throw AsrFailureException(
                AsrFailureKind.ENGINE_UNAVAILABLE,
                WhisperNative.unavailableReason() ?: "本地识别引擎不可用",
            )
        }

        val pcmFile = File(workDir, "asr-" + System.nanoTime() + ".pcm")
        pcmFile.parentFile?.mkdirs()
        var cancelled = false
        try {
            val decoded = pcmProvider.decode(request.source, pcmFile, totalMs, onProgress)
            if (!decoded) throw AsrFailureException(AsrFailureKind.DECODE_FAILED, "音频解码失败或没有音轨")

            val recognized = ArrayList<RecognizedWindow>(windows.size)
            var doneMs = 0L
            var segments = 0
            WhisperNative.open(
                modelPath = request.modelPath,
                threads = requestedThreads,
                useGpu = false,
                expectedSizeBytes = request.model.sizeBytes,
            ).use { context ->
                for (window in windows) {
                    try {
                        currentCoroutineContext().ensureActive()
                        yieldGate?.awaitTurn()
                        currentCoroutineContext().ensureActive()
                    } catch (e: CancellationException) {
                        if (!keepPartialOnCancel) throw e
                        cancelled = true
                        break
                    }
                    val threads = yieldGate?.policy(requestedThreads)?.threadCount ?: requestedThreads
                    val samples = AsrPcm.readWindow(pcmFile, window)
                    if (samples.size >= MIN_WINDOW_SAMPLES) {
                        // offsetMs 传 0：whisper 的 params.offset_ms 会**跳过**音频开头这么多毫秒，
                        // 不是给时间戳加偏移；窗口的绝对时间由 AsrPipeline.absoluteSegments 负责。
                        val result = context.transcribe(
                            pcm = samples,
                            language = request.language,
                            translate = request.translate,
                            threads = threads,
                            offsetMs = 0L,
                        )
                        recognized += RecognizedWindow(window, result)
                        segments += result.size
                    }
                    doneMs = window.endMs
                    onProgress(AsrPipeline.progressOf(doneMs, totalMs))
                }
            }
            val cues = AsrPipeline.toCues(AsrPipeline.mergeWindows(recognized), cueOptions)
            return AsrRunResult(
                cues = cues,
                segmentCount = segments,
                recognizedMs = if (cancelled) doneMs else totalMs,
                cancelled = cancelled,
            )
        } finally {
            runCatching { pcmFile.delete() }
        }
    }

    private companion object {

        /** whisper 至少需要 100 ms 音频（与 native 侧 kMinSamples 同口径）。 */
        const val MIN_WINDOW_SAMPLES = AsrPcm.SAMPLE_RATE / 10
    }
}
