package io.github.gua123.mediagate.core.model

/**
 * 媒体大类（R1 三类媒体 / R3 通用格式 + .ts / R5 缩略图策略分流 / R14 字幕）。
 *
 * 由 [MediaKindGuesser] 按文件扩展名判定，只用于「列表怎么展示、缩略图走哪条流水线、
 * 能不能当字幕加载」这类粗分流；真正能不能播仍由播放内核探测决定。
 */
enum class MediaKind {
    /** 视频：通用容器 + MPEG-TS 家族 + HLS 播放列表。 */
    VIDEO,

    /** 音频。 */
    AUDIO,

    /** 图片。 */
    IMAGE,

    /** 字幕：外挂字幕加载（R14）。 */
    SUBTITLE,

    /** 其他：不参与媒体列表，仍可在文件浏览器里看到。 */
    OTHER,
}

/**
 * 按扩展名猜测媒体类型（大小写不敏感）。
 *
 * 覆盖范围（用户明确要求「通用格式 + .ts」）：
 * - 视频：通用容器（mp4/mkv/avi/flv/mov/webm/wmv/rmvb/mpg/mpeg/vob/3gp/asf/ogv 等）
 *   **以及 MPEG-TS 家族 ts / m2ts / mts / tp 与 HLS 播放列表 m3u8**（R3）；
 * - 音频：mp3/flac/ogg/opus/m4a/aac/wav/wma/ape 等；
 * - 图片：jpg/jpeg/png/heic/heif/avif/gif/webp/bmp 等；
 * - 字幕：srt/vtt/ass/ssa 等（R14）。
 *
 * 无扩展名或未收录的扩展名一律返回 [MediaKind.OTHER]，不抛异常。
 */
object MediaKindGuesser {

    /** MPEG-TS 家族 + HLS，plan 4.3 要求专项强化，这里保证不被漏判。 */
    private val VIDEO = hashSetOf(
        // 通用视频容器
        "mp4", "m4v", "mkv", "avi", "flv", "mov", "webm", "wmv", "rmvb", "rm",
        "mpg", "mpeg", "mpe", "m2v", "vob", "3gp", "3g2", "f4v", "asf", "ogv", "divx",
        // MPEG-TS 家族（R3：额外支持 .ts）
        "ts", "m2ts", "mts", "tp",
        // HLS 播放列表（R3：同时支持 m3u8）
        "m3u8",
    )

    private val AUDIO = hashSetOf(
        "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "ape",
        "mka", "aif", "aiff", "alac", "amr", "ac3", "eac3", "dts", "oga", "mp2",
    )

    private val IMAGE = hashSetOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "heic", "heif", "avif", "gif",
        "webp", "bmp", "tif", "tiff", "ico", "jxl",
    )

    private val SUBTITLE = hashSetOf(
        "srt", "vtt", "ass", "ssa", "sub", "ttml", "dfxp", "smi",
    )

    /**
     * 猜测 [fileName] 对应的媒体大类。
     *
     * @param fileName 文件名或完整路径均可（只取最后一个 '.' 之后的部分判定）。
     * @return 命中的媒体大类；未知或没有扩展名返回 [MediaKind.OTHER]。
     */
    fun guess(fileName: String): MediaKind {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return MediaKind.OTHER
        val ext = fileName.substring(dot + 1).lowercase()
        return when (ext) {
            in VIDEO -> MediaKind.VIDEO
            in AUDIO -> MediaKind.AUDIO
            in IMAGE -> MediaKind.IMAGE
            in SUBTITLE -> MediaKind.SUBTITLE
            else -> MediaKind.OTHER
        }
    }
}
