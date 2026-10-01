package io.github.gua123.mediagate.media.thumbnail

import io.github.gua123.mediagate.core.model.MediaKind

/**
 * 缩略图流水线（M1-F，plan 4.4 的三行策略）。
 *
 * 三种媒体走三条不同的路，[ThumbnailRepository] 按这里的选择分发：
 * - [FRAME]：视频抽帧——MMR 取 10% 帧 → 1% / 25% 换位重试 → FFmpeg 兜底；
 * - [AUDIO_ARTWORK]：音频内嵌封面——MMR [android.media.MediaMetadataRetriever.getEmbeddedPicture]，
 *   **抽帧分支不适用于音频**，失败即失败（写负缓存，不再试 FFmpeg 抽帧）；
 * - [IMAGE_PREVIEW]：图片缩略图——解码 → 按目标宽度缩放 → 重新编码。
 */
enum class ThumbnailPipeline {

    /** 视频抽帧（主策略 + 兜底）。 */
    FRAME,

    /** 音频内嵌封面（ID3 / FLAC）。 */
    AUDIO_ARTWORK,

    /** 图片缩略图（解码 + 重编码）。 */
    IMAGE_PREVIEW;

    companion object {

        /**
         * 按媒体类型选流水线（**纯函数**，JVM 单测覆盖全部分支）。
         *
         * 未接线对应提取器时（[audioArtworkAvailable] / [imagePreviewAvailable] 为 false），
         * 退回 [FRAME]，保持与 M1 早期行为一致，不会因为少传一个参数就整条流水线不可用。
         *
         * @param kind 目录项按扩展名判定的媒体类型（[io.github.gua123.mediagate.core.model.MediaKindGuesser]）。
         * @param audioArtworkAvailable 仓库是否配置了音频内嵌封面提取器。
         * @param imagePreviewAvailable 仓库是否配置了图片缩略图流水线。
         */
        fun select(
            kind: MediaKind,
            audioArtworkAvailable: Boolean,
            imagePreviewAvailable: Boolean,
        ): ThumbnailPipeline = when (kind) {
            MediaKind.AUDIO -> if (audioArtworkAvailable) AUDIO_ARTWORK else FRAME
            MediaKind.IMAGE -> if (imagePreviewAvailable) IMAGE_PREVIEW else FRAME
            else -> FRAME
        }
    }
}
