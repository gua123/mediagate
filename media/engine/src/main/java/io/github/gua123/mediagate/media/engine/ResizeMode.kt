package io.github.gua123.mediagate.media.engine

/**
 * 画面缩放模式（plan 4.6 `setResizeMode`）。
 *
 * 语义按「用户想看到什么」定义，与各家播放器的私有枚举解耦：
 * 两个内核各自映射到 AspectRatioFrameLayout.RESIZE_MODE_* 与 MediaPlayer.ScaleType。
 *
 * 注意 [ORIGINAL]（原始尺寸）Media3 没有对应档位，ExoPlayerEngine 会退化成 [FIT] 并在注释/日志说明。
 */
enum class ResizeMode(val label: String) {

    /** 适应屏幕（保持宽高比，完整可见，可能有黑边）。 */
    FIT("适应屏幕"),

    /** 裁切填充（保持宽高比铺满，超出部分裁掉）。 */
    CROP("裁切填充"),

    /** 拉伸铺满（不保持宽高比）。 */
    STRETCH("拉伸铺满"),

    /** 原始尺寸（不缩放）。 */
    ORIGINAL("原始尺寸"),
}
