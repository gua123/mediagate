package io.github.gua123.mediagate.media.thumbnail

/**
 * 采样率计算（R1 图片可缩放 / R5 缩略图，plan 4.4「按屏采样解码」）。
 *
 * 纯 Kotlin（**不引用任何 Android API**），因此可以直接 JVM 单测穷举边界；
 * [android.graphics.BitmapFactory.Options.inSampleSize] 的口径在这里收敛成一份实现，
 * 缩略图提取器（[ImageThumbnailExtractor] / [EmbeddedArtworkExtractor]）与图片查看器共用。
 *
 * 约定：结果永远是 **2 的幂**（BitmapFactory 只保证 2 的幂精确生效，其它值会被向下取整），
 * 且是「仍能让解码结果不小于请求尺寸」的最大值——即解码内存最省、又不会糊到比请求还小。
 */
object ImageSampling {

    /**
     * 计算 [android.graphics.BitmapFactory.Options.inSampleSize]。
     *
     * @param sourceWidth 原图宽（[android.graphics.BitmapFactory.Options.outWidth]）；<=0 视为未知。
     * @param sourceHeight 原图高（outHeight）；<=0 视为未知。
     * @param requestedWidth 期望宽（像素）；<=0 表示该方向不约束。
     * @param requestedHeight 期望高（像素）；<=0 表示该方向不约束。
     * @return 采样率，恒 >= 1；尺寸未知、请求无约束或原图本来就不大时返回 1（不采样）。
     */
    fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        if (requestedWidth <= 0 && requestedHeight <= 0) return 1
        var sample = 1
        while (true) {
            val next = sample * 2
            // 整数除法即「向下取整」：只要还有一边会小于请求尺寸，就停在当前采样率
            val fitsWidth = requestedWidth <= 0 || sourceWidth / next >= requestedWidth
            val fitsHeight = requestedHeight <= 0 || sourceHeight / next >= requestedHeight
            if (!fitsWidth || !fitsHeight) return sample
            sample = next
        }
    }
}
