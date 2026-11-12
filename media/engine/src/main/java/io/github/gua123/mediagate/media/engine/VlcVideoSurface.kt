package io.github.gua123.mediagate.media.engine

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout

/**
 * LibVLC 的视频显示容器（**自己算缩放**，不再依赖 VLC 的 VideoHelper/fit）。
 *
 * 为什么不用 VLCVideoLayout 的 fit：2026-11-12 真机（0.1.86）用户反馈
 * 「播放位置还是有问题、缩放不起作用」——VLC 的 setVideoScale 在部分机型/部分 vout 上
 * 不生效。这里改为经典做法：
 * - 内部 TextureView 的布局尺寸 = **视频原始尺寸**（720x1280 就 720x1280）；
 * - 用 View 的 scaleX/scaleY 把这块“原始画布”缩放/平移到容器里：
 *   FIT=min 等比、CROP=max 等比、STRETCH=非等比铺满、ORIGINAL=1:1；
 * - 容器 clipChildren=true，CROP 超出的部分被裁掉。
 *
 * 因为缩放的是 View 层，和 VLC 内部怎么画无关，所以四种档位一定能生效。
 */
class VlcVideoSurface(context: Context) : FrameLayout(context) {

    /** VLC 实际渲染到的 TextureView；VlcEngine 用它调 IVLCVout.setVideoView。 */
    val textureView: TextureView = TextureView(context).apply {
        isOpaque = false
    }

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var mode: ResizeMode = ResizeMode.FIT

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** 视频轨道尺寸（来自 MediaPlayer.currentVideoTrack，解码出轨道后才有）。 */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        applyMode()
    }

    /** 切换缩放档位（由 VlcEngine.setResizeMode 调用）。 */
    fun setResizeMode(resizeMode: ResizeMode) {
        mode = resizeMode
        applyMode()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMode()
    }

    private fun applyMode() {
        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0) return

        // 还没拿到视频尺寸：先铺满，等轨道信息到了再算
        if (videoWidth <= 0 || videoHeight <= 0) {
            textureView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            textureView.scaleX = 1f
            textureView.scaleY = 1f
            return
        }

        // 视频原始画布：1:1 像素，居中摆放；缩放围绕 View 中心，因此视觉框仍居中
        textureView.layoutParams = LayoutParams(videoWidth, videoHeight, Gravity.CENTER)
        textureView.pivotX = videoWidth / 2f
        textureView.pivotY = videoHeight / 2f

        val scale = VideoFitMath.scaleFor(mode, videoWidth, videoHeight, viewW, viewH)
        textureView.scaleX = scale.x
        textureView.scaleY = scale.y
        // TextureView 缓冲区按原始视频尺寸给，避免 VLC 按容器大小画导致二次缩放
        runCatching {
            textureView.surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
        }
    }
}
