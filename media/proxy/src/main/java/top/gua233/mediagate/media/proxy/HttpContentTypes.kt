package io.github.gua123.mediagate.media.proxy

/**
 * 回环代理响应的 `Content-Type` 猜测（给 LibVLC / 外部播放器的提示，plan 4.6）。
 *
 * 只按扩展名做一个很小的映射：猜不出来一律 `application/octet-stream`，
 * **不猜错**（LibVLC 与 Media3 都以容器探测为准，头只是提示）。
 */
object HttpContentTypes {

    /** 默认类型。 */
    const val DEFAULT = "application/octet-stream"

    private val BY_EXTENSION = mapOf(
        // 容器 / 视频
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "webm" to "video/webm",
        "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mts" to "video/mp2t", "tp" to "video/mp2t",
        "avi" to "video/x-msvideo", "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv",
        "m3u8" to "application/vnd.apple.mpegurl", "mpd" to "application/dash+xml",
        // 音频
        "mp3" to "audio/mpeg", "flac" to "audio/flac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/opus", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
        "wma" to "audio/x-ms-wma", "ape" to "audio/x-ape",
        // 字幕 / 图片
        "srt" to "application/x-subrip", "vtt" to "text/vtt", "ass" to "text/x-ssa", "ssa" to "text/x-ssa",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic", "avif" to "image/avif",
    )

    /** 按路径扩展名猜测 MIME；未知返回 [DEFAULT]。 */
    fun of(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return DEFAULT
        return BY_EXTENSION[name.substring(dot + 1).lowercase()] ?: DEFAULT
    }
}
