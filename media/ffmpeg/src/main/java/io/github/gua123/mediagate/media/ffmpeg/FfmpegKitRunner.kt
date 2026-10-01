package io.github.gua123.mediagate.media.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import io.github.gua123.mediagate.core.common.AppLog
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [FfmpegRunner] 的 ffmpeg-kit 实现（R11，plan 2 章选型 `dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9`）。
 *
 * 关键点：
 * - 用 **异步** API `FFmpegKit.executeWithArgumentsAsync`（参数数组形态，避免命令行按空格切分的转义坑），
 *   协程挂起直到完成回调；同步的 `executeWithArguments` 会占住一个线程，不适合放进协程调度。
 * - 取消：`invokeOnCancellation` 里调 `FFmpegKit.cancel(sessionId)`，只杀本次会话；
 *   [cancelAll] 对应静态的 `FFmpegKit.cancel()`。
 * - 进度：`StatisticsCallback` 的 `time`（秒）与 `speed`（倍速）经 [FfmpegOutput.fromStatistics]
 *   换算成 [FfmpegProgress]（0..1 需要命令带 `expectedDurationMs`，没有则 `fraction = null`）。
 * - 返回码与输出：`session.returnCode` / `session.output` / `session.failStackTrace`，归类走 [FfmpegOutcome.of]。
 *
 * 本类只做「把命令交出去、把结果收回来」，不含任何业务判断——业务在调用方（抽帧 / 时间戳重建 / ASR）。
 *
 * @param logLines 是否把 ffmpeg 日志转发到 [AppLog]（默认关；`-loglevel error` 下本来也没几行）。
 */
class FfmpegKitRunner(
    private val logLines: Boolean = false,
) : FfmpegRunner {

    private val lastSession = AtomicLong(NO_SESSION)

    override val lastSessionId: Long? get() = lastSession.get().takeIf { it != NO_SESSION }

    override suspend fun run(
        command: FfmpegCommand,
        onProgress: ((FfmpegProgress) -> Unit)?,
    ): FfmpegResult = suspendCancellableCoroutine { continuation ->
        val startedAt = System.currentTimeMillis()
        val session = try {
            FFmpegKit.executeWithArgumentsAsync(
                command.arguments.toTypedArray(),
                FFmpegSessionCompleteCallback { finished ->
                    if (continuation.isActive) {
                        val returnCode = finished.returnCode?.value ?: NO_RETURN_CODE
                        continuation.resume(
                            FfmpegResult(
                                sessionId = finished.sessionId,
                                returnCode = returnCode,
                                outcome = FfmpegOutcome.of(returnCode),
                                output = finished.output ?: "",
                                failStackTrace = finished.failStackTrace,
                                durationMs = finished.duration.takeIf { it > 0 } ?: (System.currentTimeMillis() - startedAt),
                                command = command,
                            ),
                        )
                    }
                },
                LogCallback { entry -> if (logLines) AppLog.d(TAG, entry.message ?: "") },
                StatisticsCallback { statistics ->
                    val listener = onProgress
                    if (listener != null) {
                        listener(
                            FfmpegOutput.fromStatistics(
                                timeSeconds = statistics.time,
                                speed = statistics.speed,
                                totalDurationMs = command.expectedDurationMs,
                                frameNumber = statistics.videoFrameNumber,
                            ),
                        )
                    }
                },
            )
        } catch (t: Throwable) {
            // ffmpeg-kit 未初始化 / native 库缺失：把原因如实抛给调用方，不吞
            AppLog.e(TAG, "启动 FFmpeg 会话失败：${command.commandLine}", t)
            continuation.resumeWithException(t)
            return@suspendCancellableCoroutine
        }
        lastSession.set(session.sessionId)
        AppLog.d(TAG, "FFmpeg 会话 #${session.sessionId} 启动：${command.commandLine}")
        continuation.invokeOnCancellation {
            // 协程被取消 → 只杀本次会话（不是 cancelAll）
            FFmpegKit.cancel(session.sessionId)
        }
    }

    override fun cancel(sessionId: Long) {
        FFmpegKit.cancel(sessionId)
    }

    override fun cancelAll() {
        FFmpegKit.cancel()
    }

    private companion object {

        const val TAG = "ffmpeg"

        /** 未分配会话 id 的哨兵值（ffmpeg-kit 的会话 id 从 1 开始）。 */
        const val NO_SESSION = -1L

        /** return code 拿不到时的占位（正常路径不会出现）。 */
        const val NO_RETURN_CODE = -1
    }
}
