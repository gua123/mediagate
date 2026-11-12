package io.github.gua123.mediagate.media.engine

import kotlin.math.max
import kotlin.math.min

/** 视频画面在容器里的缩放比例（纯函数，JVM 可测）。 */
data class VideoScale(val x: Float, val y: Float)

/**
 * 自算缩放：不再依赖 VLC 的 VideoHelper/setVideoScale。
 *
 * TextureView 的布局尺寸固定为视频原始像素，再用 View 的 scaleX/scaleY 把它放进容器：
 * - FIT：等比，完整可见（可能有黑边）；
 * - CROP：等比，铺满容器，超出裁掉；
 * - STRETCH：非等比，铺满；
 * - ORIGINAL：1:1。
 */
object VideoFitMath {

    fun scaleFor(
        mode: ResizeMode,
        videoWidth: Int,
        videoHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): VideoScale {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return VideoScale(1f, 1f)
        }
        val fit = min(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val cover = max(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        return when (mode) {
            ResizeMode.FIT -> VideoScale(fit, fit)
            ResizeMode.CROP -> VideoScale(cover, cover)
            ResizeMode.STRETCH -> VideoScale(
                viewWidth.toFloat() / videoWidth,
                viewHeight.toFloat() / videoHeight,
            )

            ResizeMode.ORIGINAL -> VideoScale(1f, 1f)
        }
    }
}
