package io.github.gua123.mediagate.media.engine

/**
 * 解码模式（R10 硬/软解，plan 4.6「解码模式开关」）。
 *
 * 三个档位的语义与两个内核的落地方式：
 *
 * | 档位 | Media3 | LibVLC |
 * | --- | --- | --- |
 * | [AUTO_HW]（默认） | MediaCodecSelector.DEFAULT + 允许解码器回退（硬解优先） | `avcodec-hw=any` + 设备自适应 mediacodec 模块 |
 * | [FORCE_SW] | 只挑软件解码器（softwareOnly）；设备一个都没有时回退默认顺序并**记录上报** | `avcodec-hw=none` + `setHWDecoderEnabled(false)`（真正关硬解） |
 * | [FORCE_HW] | 默认选择器 + 关闭解码器回退（Android 没有「只能硬解」的官方开关，见 ExoPlayerEngine 注释） | `avcodec-hw=any` + `setHWDecoderEnabled(true, force = true)` |
 *
 * [FORCE_SW] 是**倾向 LibVLC** 的档位：Media3 的软解能力取决于设备是否带 c2.android.* /
 * OMX.google.* 软解码器（未内置 media3 FFmpeg 扩展），而 LibVLC 自带完整软解，
 * 所以 [prefersVlcEngine] 为 true，由引擎切换器据此换内核（plan 4.6 自动降级链）。
 */
enum class DecoderMode(val label: String) {

    /** 自动：硬解优先，失败可回退（默认）。 */
    AUTO_HW("自动（硬解优先）"),

    /** 强制软解（LibVLC 侧真正生效）。 */
    FORCE_SW("强制软解"),

    /** 强制硬解（尽量不让出硬解路径）。 */
    FORCE_HW("强制硬解");

    /** 是否要求「只用软件解码器」（Media3 的选择器策略）。 */
    val preferSoftwareDecoder: Boolean get() = this == FORCE_SW

    /** 是否倾向换到 LibVLC 内核（R10：切软解后同一视频仍可播）。 */
    val prefersVlcEngine: Boolean get() = this == FORCE_SW

    /** 传给 **LibVLC 媒体**的 `avcodec-hw` 选项（带 `:` 前缀 = 媒体级选项）。 */
    val vlcAvcodecHwOption: String get() = if (this == FORCE_SW) ":avcodec-hw=none" else ":avcodec-hw=any"

    /** 传给 **LibVLC 实例**的 `avcodec-hw` 选项（带 `--` 前缀 = 实例级选项，切换档位要重建实例）。 */
    val vlcInstanceOption: String get() = if (this == FORCE_SW) "--avcodec-hw=none" else "--avcodec-hw=any"

    /** `Media.setHWDecoderEnabled(enabled, force)` 的第一个参数。 */
    val vlcHwDecoderEnabled: Boolean get() = this != FORCE_SW

    /** `Media.setHWDecoderEnabled(enabled, force)` 的第二个参数（force = 连黑名单设备也试硬解）。 */
    val vlcHwDecoderForce: Boolean get() = this == FORCE_HW

    /** Media3 的 `setEnableDecoderFallback`（强制硬解时不许回退到别的解码器）。 */
    val media3EnableDecoderFallback: Boolean get() = this != FORCE_HW
}
