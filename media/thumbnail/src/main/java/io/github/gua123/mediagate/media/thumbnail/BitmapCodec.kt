package io.github.gua123.mediagate.media.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 位图解码 / 缩放 / 编码小工具（M1-F）：[EmbeddedArtworkExtractor] 与 [ImageThumbnailExtractor] 共用。
 *
 * 只跑在 Android 运行时（依赖 [BitmapFactory] / [Bitmap]），因此不做 JVM 单测；
 * 其中真正需要穷举边界的**采样率计算**被抽到纯 Kotlin 的 [ImageSampling] 里，已由单测覆盖。
 *
 * 约定：调用方（两个提取器）统一 try/catch，失败返回 null。
 */
internal object BitmapCodec {

    /** 单次读取的缓冲块大小（图片提取器用）。 */
    const val READ_CHUNK_BYTES = 256 * 1024

    /**
     * 按 [targetWidth] 采样解码（R1 大图不许 OOM 的关键一步）。
     *
     * 两步走：先用 BitmapFactory.Options.inJustDecodeBounds 只读文件头拿原始宽高（不分配像素内存）
     * → 算出 2 的幂采样率（[ImageSampling.sampleSize]）→ 再真正解码；
     * 最后若仍宽于目标，用 [Bitmap.createScaledBitmap] 精确缩一次。
     *
     * @param targetWidth 目标宽度（像素）；<=0 表示按原尺寸（仍走采样率=1 的正常解码）。
     * @return 解码后的位图；不是图片 / 解码失败返回 null。
     */
    fun decodeSampled(bytes: ByteArray, targetWidth: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0 || bounds.outMimeType == null) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(sourceWidth, sourceHeight, targetWidth, 0)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleToWidth(decoded, targetWidth)
    }

    /**
     * 精确缩到 [targetWidth]；已经不比目标宽时原样返回（不复制位图）。
     *
     * 缩放成功会回收原图，避免「4K 原图 + 缩略图」两份像素同时在内存里。
     */
    fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || bitmap.width <= targetWidth) return bitmap
        val height = ((bitmap.height.toLong() * targetWidth) / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * 把位图编码成图片字节。
     *
     * @return 编码后的字节；编码器不支持或内存不足时返回 null（调用方按失败处理）。
     */
    fun encode(bitmap: Bitmap, format: ThumbnailImageFormat, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(compressFormat(format), quality.coerceIn(1, 100), out)
        if (!ok) return null
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** [ThumbnailImageFormat] → [Bitmap.CompressFormat]（WebP 走有损编码器）。 */
    fun compressFormat(format: ThumbnailImageFormat): Bitmap.CompressFormat = when (format) {
        ThumbnailImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
        ThumbnailImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ThumbnailImageFormat.PNG -> Bitmap.CompressFormat.PNG
    }
}
