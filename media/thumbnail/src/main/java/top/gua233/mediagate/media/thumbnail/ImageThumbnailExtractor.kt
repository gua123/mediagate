package io.github.gua123.mediagate.media.thumbnail

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.ByteArrayOutputStream

/** 日志 TAG。 */
private const val TAG = "thumbnail-image"

/**
 * 图片缩略图提取（**M1-F**，plan 4.4 表格的「图片 | 按屏采样解码 | 占位图 + 重试」行）。
 *
 * 流程：整份读入 → [BitmapCodec.decodeSampled]（inJustDecodeBounds + inSampleSize，**大图不 OOM**）
 * → 精确缩到目标宽度 → 按 [format] 重新编码。
 *
 * 为什么重新编码，而不是把原图字节直接塞进缓存：
 * 1. 原图动辄几 MB~几十 MB，落进缩略图缓存会把 512 MB 的预算吃光（plan 4.10）；
 * 2. 列表只需要几百像素宽的小图，重编码后体积小一个数量级，二次进入才能「秒显」（R5）；
 * 3. 编码格式与缓存 key 的扩展名一一对应（见 [ImagePreviewPipeline]），
 *    **不会出现「JPEG/PNG 原图命名成 .webp」这种内容与扩展名不符的情况**。
 *
 * 失败语义与其它提取器一致：不抛异常（除协程取消），失败返回 null 交给失败负缓存。
 *
 * @param format 输出编码；默认 WebP（体积最小）。
 * @param quality 有损编码质量（1..100）。
 * @param maxBytes 单张图片允许读入内存的字节上限；超过直接放弃（返回 null，让列表显示类型图标）。
 * @param io 解码所在的调度器。
 */
class ImageThumbnailExtractor(
    /** 实际写出的编码格式；[ImagePreviewPipeline] 用它决定缓存文件扩展名。 */
    val format: ThumbnailImageFormat = ThumbnailImageFormat.WEBP,
    private val quality: Int = DEFAULT_QUALITY,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FrameExtractor {

    /**
     * 解码 [source] 指向的图片并输出缩略图字节。
     *
     * [positionMs] 对静态图片没有意义（[mimeHint] 也仅用于日志），实现忽略它们。
     */
    override suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray? = withContext(io) {
        var bitmap: Bitmap? = null
        try {
            val bytes = readAll(source) ?: return@withContext null
            bitmap = BitmapCodec.decodeSampled(bytes, targetWidth)
            if (bitmap == null) {
                AppLog.w(TAG, "图片解码失败：mime=$mimeHint bytes=" + bytes.size)
                return@withContext null
            }
            BitmapCodec.encode(bitmap, format, quality)?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.e(TAG, "生成图片缩略图失败：mime=$mimeHint width=$targetWidth", t)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * 把数据源整份读进内存。
     *
     * 为什么整份读：BitmapFactory 需要可重复读的完整字节（先 bounds 再解码，两次扫描），
     * 而 [RandomAccessSource] 的挂起读没法直接喂给它。因此用 [maxBytes] 兜住最坏情况。
     */
    private suspend fun readAll(source: RandomAccessSource): ByteArray? {
        val size = source.size
        if (size > maxBytes) {
            AppLog.w(TAG, "图片超过缩略图上限，跳过：size=$size max=$maxBytes")
            return null
        }
        val buffer = ByteArray(BitmapCodec.READ_CHUNK_BYTES)
        val out = ByteArrayOutputStream(if (size in 1..Int.MAX_VALUE) size.toInt() else buffer.size)
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val want = if (size >= 0) minOf(buffer.size.toLong(), size - offset).toInt() else buffer.size
            if (want <= 0) break
            val read = source.readAt(offset, buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            offset += read
            if (offset > maxBytes) {
                AppLog.w(TAG, "图片读取超过缩略图上限，放弃：read=$offset max=$maxBytes")
                return null
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    companion object {
        /** 默认编码质量。 */
        const val DEFAULT_QUALITY = 85

        /** 默认读入上限：64 MB（与浏览页图片直读的口径一致，超过一律放弃）。 */
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
    }
}

/**
 * 图片缩略图流水线（**M1-F**，plan 4.4 图片行）：提取器 + 它实际写出的编码格式。
 *
 * 两者必须一起传：缓存 key 的**扩展名**由 [format] 决定（M1-F 的硬要求——
 * JPEG/PNG 内容不得命名成 .webp），绑成一个类型就不会出现「格式改了、扩展名没跟上」。
 *
 * @param extractor 实际解码/编码的提取器（生产用 [ImageThumbnailExtractor]，单测用假实现）。
 * @param format [extractor] 写出的编码格式；默认 WebP。
 */
data class ImagePreviewPipeline(
    val extractor: FrameExtractor,
    val format: ThumbnailImageFormat = ThumbnailImageFormat.WEBP,
)
