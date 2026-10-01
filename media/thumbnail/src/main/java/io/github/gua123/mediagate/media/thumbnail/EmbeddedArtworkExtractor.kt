package io.github.gua123.mediagate.media.thumbnail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource

/** 日志 TAG。 */
private const val TAG = "thumbnail-artwork"

/**
 * 音频内嵌封面提取（**M1-F**，plan 4.4 表格的「音频 | MMR 内嵌封面（ID3/FLAC）| 首字母色块」行）。
 *
 * 为什么不能复用抽帧：音频里没有视频帧，MediaMetadataRetriever.getFrameAtTime 对纯音频容器只会失败；
 * 封面必须走 MediaMetadataRetriever.getEmbeddedPicture（ID3v2 APIC / FLAC PICTURE 块）。
 *
 * 与 [MediaMetadataRetrieverFrameExtractor] 完全同构：
 * - 数据仍然只经过 [RandomAccessSource]（plan 第 3 章不变量），通过 [SourceMediaDataSource] 喂给 MMR；
 * - 整体跑在 [io]（默认 [Dispatchers.IO]），MMR 的 native 回调线程用 runBlocking 桥接；
 * - **不抛异常**：没有封面、格式不支持、解码失败都返回 null（只记 [AppLog]），
 *   由 [ThumbnailRepository] 写失败负缓存；只有协程取消继续向上抛；
 * - 大图不 OOM：内嵌封面先按 [targetWidth] 采样解码（[BitmapCodec.decodeSampled]）。
 *
 * @param format 输出编码；默认 WebP（与视频帧缓存口径一致）。
 * @param quality 有损编码质量（1..100）。
 * @param io 提取所在的调度器。
 */
class EmbeddedArtworkExtractor(
    private val format: ThumbnailImageFormat = ThumbnailImageFormat.WEBP,
    private val quality: Int = DEFAULT_QUALITY,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FrameExtractor {

    /**
     * 取内嵌封面并按 [targetWidth] 缩放编码。
     *
     * [positionMs] 对封面没有意义（一张专辑图不随时间变化），实现忽略它。
     */
    override suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray? = withContext(io) {
        var retriever: MediaMetadataRetriever? = null
        val dataSource = SourceMediaDataSource(source)
        var bitmap: Bitmap? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(dataSource)
            val picture = retriever.embeddedPicture
            if (picture == null || picture.isEmpty()) {
                AppLog.d(TAG, "音频没有内嵌封面：mime=$mimeHint")
                return@withContext null
            }
            bitmap = BitmapCodec.decodeSampled(picture, targetWidth)
            if (bitmap == null) {
                AppLog.w(TAG, "内嵌封面解码失败：mime=$mimeHint bytes=" + picture.size)
                return@withContext null
            }
            BitmapCodec.encode(bitmap, format, quality)?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.e(TAG, "读取音频内嵌封面失败：mime=$mimeHint width=$targetWidth", t)
            null
        } finally {
            bitmap?.recycle()
            try {
                retriever?.release()
            } catch (t: Throwable) {
                AppLog.w(TAG, "释放 MediaMetadataRetriever 失败：" + t.message, t)
            }
            dataSource.close()
        }
    }

    companion object {
        /** 默认编码质量。 */
        const val DEFAULT_QUALITY = 85
    }
}
