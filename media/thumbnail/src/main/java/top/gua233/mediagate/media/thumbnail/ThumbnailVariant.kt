package io.github.gua123.mediagate.media.thumbnail

/**
 * 缓存条目的「用途」维度（M1-F，plan 4.4 三行策略）。
 *
 * 为什么需要它：同一个文件在不同用途下会产出**不同内容**的字节（视频抽帧 / 音频内嵌封面 /
 * 图片按屏缩略图），它们必须落成不同的缓存条目，否则会互相覆盖。
 * 用途进 [ThumbnailKey] 的哈希，[defaultExtension] 决定默认落盘扩展名。
 *
 * 扩展名口径（M1-F 的硬要求「不得把 JPEG/PNG 原图命名成 .webp」）：
 * - [FRAME]：MMR / FFmpeg 抽出来的画面，历史口径统一按 `.webp` 命名（见 [ThumbnailKey] 注释）；
 * - [IMAGE_PREVIEW]：图片解码后重新编码的缩略图，**扩展名跟随实际编码格式**
 *   （由 [ImagePreviewPipeline.format] 传入，WebP 出 `.webp`、JPEG 出 `.jpg`…）。
 *
 * 纯 Kotlin 枚举，可直接 JVM 单测。
 */
enum class ThumbnailVariant(
    /** 未显式指定编码格式时的落盘扩展名（不带点）。 */
    val defaultExtension: String,
) {
    /** 视频抽帧 / 音频内嵌封面：从媒体容器里取出的一帧画面。 */
    FRAME(ThumbnailImageFormat.WEBP.extension),

    /** 图片缩略图：原图解码 → 按目标宽度缩放 → 重新编码。 */
    IMAGE_PREVIEW(ThumbnailImageFormat.WEBP.extension),
}
