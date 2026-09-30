package io.github.gua123.mediagate.media.thumbnail

/**
 * 抽帧输出的编码格式（R5 缩略图 / R11 FFmpeg 兜底抽帧）。
 *
 * 本枚举是纯 Kotlin 的（不引用 `android.graphics.Bitmap`），方便 JVM 单测与替换实现共用。
 */
enum class ThumbnailImageFormat(
    /** 落临时文件用的扩展名。 */
    val extension: String,
    /** MIME 类型。 */
    val mimeType: String,
) {
    /** 有损 WebP：体积最小，且与磁盘缓存 `<hash>.webp` 命名一致（Android 10+ 的编码器）。 */
    WEBP("webp", "image/webp"),

    /** JPEG：通用性最好，ffmpeg-kit-min 不含 libwebp，兜底抽帧只能出它。 */
    JPEG("jpg", "image/jpeg"),

    /** PNG：无损（调试图或需要 alpha 时用）。 */
    PNG("png", "image/png"),
}
