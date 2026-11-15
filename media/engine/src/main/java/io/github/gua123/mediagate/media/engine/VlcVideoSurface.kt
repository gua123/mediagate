package io.github.gua123.mediagate.media.engine

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * LibVLC 视频显示容器（**自己算缩放**，不依赖 VLC 的 VideoHelper/fit）。
 *
 * TextureView 布局尺寸 = 视频原始像素，再用 View 的 scaleX/scaleY 做 FIT/CROP/STRETCH/ORIGINAL；
 * 容器 clipChildren=true。2026-11-15 真机验证 LAN 播放可用。
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

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        applyMode()
    }

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
        if (videoWidth <= 0 || videoHeight <= 0) {
            textureView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            textureView.scaleX = 1f
            textureView.scaleY = 1f
            return
        }
        textureView.layoutParams = LayoutParams(videoWidth, videoHeight, Gravity.CENTER)
        textureView.pivotX = videoWidth / 2f
        textureView.pivotY = videoHeight / 2f
        val scale = VideoFitMath.scaleFor(mode, videoWidth, videoHeight, viewW, viewH)
        textureView.scaleX = scale.x
        textureView.scaleY = scale.y
        runCatching {
            textureView.surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
        }
    }
}
