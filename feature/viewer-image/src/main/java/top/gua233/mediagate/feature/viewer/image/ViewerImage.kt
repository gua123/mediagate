package io.github.gua123.mediagate.feature.viewer.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog

/** 日志 TAG。 */
private const val TAG = "viewer-image"

/**
 * 解码后的图片（**M1-F**）：只暴露尺寸，UI 层用具体实现取底层位图。
 *
 * 为什么要这层抽象：`android.graphics.Bitmap` 在 JVM 单测里造不出来（final 类 + 桩方法），
 * 若 ViewModel 的 UiState 直接持有 Bitmap，「加载成功」这条分支就没法单测。
 * 有了接口，单测注入假实现，生产用 [BitmapDecodedImage]。
 */
interface DecodedImage {

    /** 解码后的宽（像素），已按屏幕采样。 */
    val width: Int

    /** 解码后的高（像素），已按屏幕采样。 */
    val height: Int
}

/** [DecodedImage] 的 Android 实现：包装 [Bitmap]（UI 层 `asImageBitmap()` 后直接绘制）。 */
class BitmapDecodedImage(val bitmap: Bitmap) : DecodedImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * 图片解码器（**M1-F**）：把原始字节解码成可绘制的图片。
 *
 * 抽成接口是为了让 [ImageViewerViewModel] 可以在纯 JVM 单测里替身化——
 * 真实实现（[BitmapImageViewerDecoder]）只跑在设备上。
 *
 * 约定：**不抛异常**（除协程取消），解不出来返回 null（页面提示「无法解码」）。
 */
fun interface ImageViewerDecoder {

    /**
     * 解码 [bytes]。
     *
     * @param requestedWidth 期望宽（像素，通常是屏幕宽）；用于算采样率。
     * @param requestedHeight 期望高（像素，通常是屏幕高）。
     * @return 解码结果；不是图片 / 内存不足返回 null。
     */
    suspend fun decode(bytes: ByteArray, requestedWidth: Int, requestedHeight: Int): DecodedImage?
}

/**
 * 生产用解码器（**M1-F**）：`BitmapFactory` + 按屏幕尺寸采样（R1「大图不许 OOM」）。
 *
 * 两步走：`inJustDecodeBounds` 只读文件头拿原始宽高（不分配像素内存）→ [ViewerMath.sampleSize]
 * 算 2 的幂采样率 → 真正解码。整个过程跑在 [io]（默认 [Dispatchers.IO]），组合函数零 IO。
 *
 * @param io 解码所在的调度器。
 */
class BitmapImageViewerDecoder(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ImageViewerDecoder {

    override suspend fun decode(
        bytes: ByteArray,
        requestedWidth: Int,
        requestedHeight: Int,
    ): DecodedImage? = withContext(io) {
        try {
            if (bytes.isEmpty()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = ViewerMath.sampleSize(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                )
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            BitmapDecodedImage(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "图片解码失败：bytes=${bytes.size} req=${requestedWidth}x${requestedHeight}", t)
            null
        }
    }
}
