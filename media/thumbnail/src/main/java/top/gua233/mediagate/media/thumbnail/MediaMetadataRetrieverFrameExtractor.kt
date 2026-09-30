package io.github.gua123.mediagate.media.thumbnail

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 日志 TAG（文件级：同文件的 [SourceMediaDataSource] 也要用）。 */
private const val TAG = "thumbnail-mmr"

/**
 * 主策略抽帧（R5，plan 4.4 表格第一行）：[MediaMetadataRetriever] + 自研 [MediaDataSource]。
 *
 * 为什么走 MMR：它是系统自带、硬件无关、对 mp4/mkv/ts 等常见容器支持最好的抽帧路径，
 * 不需要拖进 native 库；关键在于把 [RandomAccessSource]（plan 第 3 章的不变量：所有媒体数据
 * 只经过这一个抽象）包装成 [MediaDataSource]，MMR 就能通过我们的数据层随机读远端文件。
 *
 * 线程与阻塞：MMR 是阻塞 API，整体跑在 [io]（默认 [Dispatchers.IO]）；MMR 的 native 线程回调
 * [SourceMediaDataSource.readAt] 时用 [runBlocking] 把挂起的 [RandomAccessSource.readAt] 桥接成
 * 阻塞调用（回调线程不是主线程，不会卡 UI）。
 *
 * 失败语义：任何异常都吞掉并返回 null（只记 [AppLog]），只有协程取消继续向上抛——
 * 抽帧失败要能安静地降级到兜底实现，不能把缩略图流水线炸掉。
 *
 * @param format 输出编码；默认 [ThumbnailImageFormat.WEBP]，与磁盘缓存 `<hash>.webp` 一致，
 *   需要 JPEG/PNG 时构造时指定即可。
 * @param quality 有损编码质量（1..100）。
 * @param io 抽帧所在的调度器。
 */
class MediaMetadataRetrieverFrameExtractor(
    private val format: ThumbnailImageFormat = ThumbnailImageFormat.WEBP,
    private val quality: Int = DEFAULT_QUALITY,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FrameExtractor, MediaDurationProbe {

    /** 按 plan 4.4 的主策略抽帧；失败返回 null，见类注释。 */
    override suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray? = withContext(io) {
        var retriever: MediaMetadataRetriever? = null
        val dataSource = SourceMediaDataSource(source)
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(dataSource)
            val timeUs = if (positionMs < 0) ANY_FRAME_US else positionMs * 1_000L
            frame = frameAt(retriever, timeUs, targetWidth)
            if (frame == null) {
                AppLog.w(TAG, "取帧为空：positionMs=$positionMs width=$targetWidth")
                return@withContext null
            }
            scaled = scaleTo(frame, targetWidth)
            val out = ByteArrayOutputStream()
            if (!scaled.compress(compressFormat(), quality, out)) {
                AppLog.w(TAG, "帧编码失败：format=$format")
                return@withContext null
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.e(TAG, "MMR 抽帧失败：positionMs=$positionMs width=$targetWidth mime=$mimeHint", t)
            null
        } finally {
            if (scaled != null && scaled !== frame) scaled.recycle()
            frame?.recycle()
            try {
                retriever?.release()
            } catch (t: Throwable) {
                AppLog.w(TAG, "释放 MediaMetadataRetriever 失败：${t.message}", t)
            }
            dataSource.close()
        }
    }

    /** 读取时长（plan 4.4 的 10% / 1% / 25% 定位依赖它）；失败返回 null。 */
    override suspend fun durationMs(source: RandomAccessSource, mimeHint: String?): Long? = withContext(io) {
        var retriever: MediaMetadataRetriever? = null
        val dataSource = SourceMediaDataSource(source)
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(dataSource)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "读取媒体时长失败：mime=$mimeHint ${t.message}", t)
            null
        } finally {
            try {
                retriever?.release()
            } catch (t: Throwable) {
                AppLog.w(TAG, "释放 MediaMetadataRetriever 失败：${t.message}", t)
            }
            dataSource.close()
        }
    }

    /**
     * 取帧：能拿到视频宽高时用 [MediaMetadataRetriever.getScaledFrameAtTime] 直接出目标尺寸
     * （避免 4K 源先解出一张全尺寸位图再缩，内存峰值差一个数量级）；拿不到就退化为取原帧再缩。
     */
    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long, targetWidth: Int): Bitmap? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (targetWidth > 0 && width > 0 && height > 0) {
            val targetHeight = ((height.toLong() * targetWidth) / width).toInt().coerceAtLeast(1)
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight,
            )
        }
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    private fun scaleTo(frame: Bitmap, targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || frame.width <= targetWidth) return frame
        val height = ((frame.height.toLong() * targetWidth) / frame.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, targetWidth, height, true)
    }

    private fun compressFormat(): Bitmap.CompressFormat = when (format) {
        ThumbnailImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
        ThumbnailImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ThumbnailImageFormat.PNG -> Bitmap.CompressFormat.PNG
    }

    companion object {

        /** 负数时间戳 = 任意一帧（MMR 的约定）。 */
        private const val ANY_FRAME_US = -1L

        /** 默认编码质量。 */
        const val DEFAULT_QUALITY = 85
    }
}

/**
 * 把 [RandomAccessSource] 包装成 MMR 认的 [MediaDataSource]（plan 4.4 主策略的关键件）。
 *
 * MMR 会在自己的 native 线程上同步回调 [readAt] / [getSize] / [close]，因此这里用
 * [runBlocking] 把挂起读桥接成阻塞读；[readAt] **绝不抛异常**（抛出去会变成 native 崩溃），
 * 出错统一返回 -1（等同于文件结束）并只记一次日志。
 *
 * 生命周期：本类**不**关闭上游 [RandomAccessSource]——它由 [ThumbnailRepository] 持有并在
 * 抽帧结束后统一关闭，[close] 只把自己标记为停用，避免 MMR 提前掐断后续重试要用的数据源。
 *
 * 可见性为 internal（M1-F）：[EmbeddedArtworkExtractor] 读音频内嵌封面时同样需要把数据层
 * 桥接给 MMR，复用同一份实现，避免两处各写一遍「挂起读 → 阻塞读」的桥接代码。
 */
internal class SourceMediaDataSource(private val source: RandomAccessSource) : MediaDataSource() {

    private val readErrorLogged = AtomicBoolean(false)

    @Volatile
    private var closed = false

    override fun getSize(): Long = source.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed || size <= 0) return 0
        return try {
            runBlocking { source.readAt(position, buffer, offset, size) }
        } catch (t: Throwable) {
            if (readErrorLogged.compareAndSet(false, true)) {
                AppLog.w(TAG, "缩略图数据源读取失败：position=$position size=$size ${t.message}", t)
            }
            -1
        }
    }

    override fun close() {
        closed = true
    }
}
