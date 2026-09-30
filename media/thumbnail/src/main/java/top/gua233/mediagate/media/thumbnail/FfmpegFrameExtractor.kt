package io.github.gua123.mediagate.media.thumbnail

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 兜底抽帧（R5 + R11，plan 4.4 表格的「FFmpeg 简版抽帧」）：MMR 主策略彻底失败时用 ffmpeg-kit 补一刀。
 *
 * 实现思路（与 plan 一致）：把 [RandomAccessSource] 落到临时文件 → 执行
 * `-ss <秒> -i <临时文件> -frames:v 1 -vf scale=<宽>:-2 -f image2 <输出>` → 读回字节 → 删临时文件。
 *
 * 为什么必须落临时文件：ffmpeg-kit 只能吃 URL/文件路径，而我们的数据层是挂起的随机读
 * （plan 第 3 章不变量），没有可直接交给 native 的 fd；远端大文件会全量落盘，因此它只做兜底，
 * 主策略永远是 [MediaMetadataRetrieverFrameExtractor]。
 *
 * 失败语义：不抛异常、返回 null 并记 [AppLog]；只有协程取消继续向上抛。
 * 取消说明：native 解码本身不可中断，但**落盘阶段**在每块之间检查取消，已尽力做到可取消。
 *
 * @param workDir 临时文件目录（App 里传 `context.cacheDir/thumb-work`）；不存在会自动创建。
 * @param format 输出编码；默认 [ThumbnailImageFormat.JPEG]——ffmpeg-kit-min 不含 libwebp，
 *   输出文件扩展名按它决定。
 * @param io 执行 ffmpeg 与落盘的调度器。
 * @param copyBufferSize 落盘缓冲区大小。
 */
class FfmpegFrameExtractor(
    private val workDir: File,
    private val format: ThumbnailImageFormat = ThumbnailImageFormat.JPEG,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val copyBufferSize: Int = DEFAULT_COPY_BUFFER_SIZE,
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
            if (!workDir.isDirectory && !workDir.mkdirs()) {
                AppLog.w(TAG, "创建临时目录失败：${workDir.path}")
                return@withContext null
            }
            currentCoroutineContext().ensureActive()
            input = File.createTempFile(TMP_INPUT_PREFIX, ".bin", workDir)
            output = File.createTempFile(TMP_OUTPUT_PREFIX, ".${format.extension}", workDir)
            dumpToFile(source, input)

            val seconds = String.format(Locale.US, "%.3f", positionMs.coerceAtLeast(0L) / 1000.0)
            val args = buildList {
                add("-hide_banner")
                add("-loglevel"); add("error")
                // -ss 放在 -i 之前：快速定位，不必解完前面的所有帧
                add("-ss"); add(seconds)
                add("-i"); add(input.absolutePath)
                add("-frames:v"); add("1")
                if (targetWidth > 0) {
                    // -2 让高度按比例取偶（多数编码器要求偶数）
                    add("-vf"); add("scale=$targetWidth:-2")
                }
                add("-f"); add("image2")
                add("-y"); add(output.absolutePath)
            }
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            currentCoroutineContext().ensureActive()
            if (!ReturnCode.isSuccess(session.returnCode)) {
                AppLog.w(
                    TAG,
                    "FFmpeg 抽帧失败：returnCode=${session.returnCode} positionMs=$positionMs mime=$mimeHint " +
                        "output=${session.output?.trim()?.take(LOG_OUTPUT_LIMIT)}",
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

    /** 把数据源整份落到 [target]；每块之间检查协程取消，取消时留下半截临时文件由 finally 清理。 */
    private suspend fun dumpToFile(source: RandomAccessSource, target: File) {
        val total = source.size
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(copyBufferSize)
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val want = if (total >= 0) minOf(buffer.size.toLong(), total - offset).toInt() else buffer.size
                if (want <= 0) break
                val read = source.readAt(offset, buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                offset += read
            }
        }
    }

    companion object {

        private const val TAG = "thumbnail-ffmpeg"

        private const val TMP_INPUT_PREFIX = "thumb-src-"

        private const val TMP_OUTPUT_PREFIX = "thumb-out-"

        /** 落盘缓冲区：1 MiB（远端读取的合理粒度）。 */
        const val DEFAULT_COPY_BUFFER_SIZE = 1 shl 20

        private const val LOG_OUTPUT_LIMIT = 400
    }
}
