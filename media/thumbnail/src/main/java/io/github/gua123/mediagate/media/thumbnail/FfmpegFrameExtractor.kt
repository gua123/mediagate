package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import io.github.gua123.mediagate.media.ffmpeg.FfmpegCommand
import io.github.gua123.mediagate.media.ffmpeg.FfmpegInputs
import io.github.gua123.mediagate.media.ffmpeg.FfmpegKitRunner
import io.github.gua123.mediagate.media.ffmpeg.FfmpegOutput
import io.github.gua123.mediagate.media.ffmpeg.FfmpegRunner
import java.io.File

/**
 * 兜底抽帧（R5 + R11，plan 4.4 表格的「FFmpeg 简版抽帧」）：MMR 主策略彻底失败时用 ffmpeg-kit 补一刀。
 *
 * 实现思路（与 plan 一致）：把 [RandomAccessSource] 落到临时文件 → 用 [FfmpegCommand.frameExtraction]
 * 组出 `-ss <秒> -i <临时文件> -frames:v 1 -vf scale=<宽>:-2 -f image2 <输出>` → [FfmpegRunner] 执行 →
 * 读回字节 → 删临时文件。
 *
 * M6 起 FFmpeg 的调用细节**统一收在 `:media:ffmpeg`**（命令形状、返回码归类、进度解析、取消），
 * 本类只负责「落盘 → 执行 → 读回」这一步，不再自己拼参数、也不再直接引用 ffmpeg-kit 类型；
 * 因此换 FFmpeg 封装（简版 → 全版）时只需要改一个模块。
 *
 * 为什么必须落临时文件：ffmpeg-kit 只能吃 URL/文件路径，而我们的数据层是挂起的随机读
 * （plan 第 3 章不变量），没有可直接交给 native 的 fd；远端大文件会全量落盘，因此它只做兜底，
 * 主策略永远是 [MediaMetadataRetrieverFrameExtractor]。
 *
 * 失败语义：不抛异常、返回 null 并记 [AppLog]；只有协程取消继续向上抛。
 * 取消说明：native 解码不可中断，但**落盘阶段**逐块检查取消，并且协程取消会经
 * [FfmpegRunner] 转成 `FFmpegKit.cancel(sessionId)`，尽量早地掐掉正在跑的 ffmpeg。
 *
 * @param workDir 临时文件目录（App 里传 `context.cacheDir/thumb-work`）；不存在会自动创建。
 * @param format 输出编码；默认 [ThumbnailImageFormat.JPEG]——ffmpeg-kit-min 不含 libwebp，
 *   输出文件扩展名按它决定。
 * @param io 执行 ffmpeg 与落盘的调度器。
 * @param copyBufferSize 落盘缓冲区大小。
 * @param runner FFmpeg 执行器；默认走真机上的 ffmpeg-kit，单测可注入假实现。
 */
class FfmpegFrameExtractor(
    private val workDir: File,
    private val format: ThumbnailImageFormat = ThumbnailImageFormat.JPEG,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val copyBufferSize: Int = DEFAULT_COPY_BUFFER_SIZE,
    private val runner: FfmpegRunner = FfmpegKitRunner(),
) : FrameExtractor {

    /** 按 plan 4.4 用 FFmpeg 兜底抽帧；失败返回 null，见类注释。 */
    override suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray? = withContext(io) {
        var input: File? = null
        var output: File? = null
        try {
            currentCoroutineContext().ensureActive()
            input = FfmpegInputs.createTemporaryInput(workDir, TMP_INPUT_PREFIX)
            output = File.createTempFile(TMP_OUTPUT_PREFIX, ".${format.extension}", workDir)
            FfmpegInputs.dumpToFile(source, input, copyBufferSize)

            val command = FfmpegCommand.frameExtraction(
                input = input,
                output = output,
                positionMs = positionMs,
                targetWidth = targetWidth,
            )
            val result = runner.run(command)
            currentCoroutineContext().ensureActive()
            if (!result.isSuccess) {
                AppLog.w(
                    TAG,
                    "FFmpeg 抽帧失败：${FfmpegOutput.describeReturnCode(result.returnCode)} " +
                        "positionMs=$positionMs mime=$mimeHint output=${result.output.trim().take(LOG_OUTPUT_LIMIT)}",
                )
                return@withContext null
            }
            val bytes = output.takeIf { it.isFile }?.readBytes()
            if (bytes == null || bytes.isEmpty()) {
                AppLog.w(TAG, "FFmpeg 抽帧没有产出字节：positionMs=$positionMs")
                return@withContext null
            }
            bytes
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.e(TAG, "FFmpeg 兜底抽帧失败：positionMs=$positionMs width=$targetWidth mime=$mimeHint", t)
            null
        } finally {
            // 无论成功失败都清掉临时文件（plan 4.10：中间件不长期占地）
            input?.delete()
            output?.delete()
        }
    }

    companion object {

        private const val TAG = "thumbnail-ffmpeg"

        private const val TMP_INPUT_PREFIX = "thumb-src-"

        private const val TMP_OUTPUT_PREFIX = "thumb-out-"

        /** 落盘缓冲区：1 MiB（与 `:media:ffmpeg` 的默认值一致）。 */
        const val DEFAULT_COPY_BUFFER_SIZE = 1 shl 20

        private const val LOG_OUTPUT_LIMIT = 400
    }
}
