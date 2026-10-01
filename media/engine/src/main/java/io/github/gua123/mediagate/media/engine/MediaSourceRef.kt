package io.github.gua123.mediagate.media.engine

import io.github.gua123.mediagate.media.proxy.MediaUriCodec

/**
 * 播放源引用（plan 4.6 `setMedia(src: MediaSourceRef)`）。
 *
 * 只装「后端 id + 后端内路径 + 标题」三件事，字节永远由数据层按需取：
 * - Media3 把 [uri]（`mediagate://` 伪 URI）交给 BackendDataSource；
 * - LibVLC 把 [uri] 交给回环 HTTP 代理换成 `http://127.0.0.1:<port>/m/...`。
 *
 * [uri] 由 [MediaUriCodec] 现算（与 media:playback 的 MediaUri 逐字一致，见其金标单测），
 * 所以本类与 MediaUri 的互转是**无损**的：[fromUri] 解析出的 backendId/path 再格式化回去完全相同。
 *
 * @property backendId 后端 id（与 StorageBackend.id 逐字相等）。
 * @property path 后端内路径（与 StorageBackend.openRead 的 path 同口径）。
 * @property title 展示名；null 时用路径末段（[displayName]）。
 */
data class MediaSourceRef(
    val backendId: String,
    val path: String,
    val title: String? = null,
) {

    /** `mediagate://<backendId>/<path>` 伪 URI（与 media:playback 的 MediaUri 口径一致）。 */
    val uri: String get() = MediaUriCodec.format(backendId, path)

    /** 通知栏 / 播放页展示的名字。 */
    val displayName: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: path

    /** 与 [uri] 同义的方法形态（便于 Java 侧与显式意图调用）。 */
    fun toUri(): String = uri

    companion object {

        /** `mediagate://` 伪 URI → 播放源；URI 非法时返回 null（调用方决定怎么报错）。 */
        fun fromUri(uri: String, title: String? = null): MediaSourceRef? =
            MediaUriCodec.parse(uri)?.let { MediaSourceRef(it.backendId, it.path, title) }

        /** 解析结果 → 播放源。 */
        fun from(parsed: MediaUriCodec.Parsed, title: String? = null): MediaSourceRef =
            MediaSourceRef(parsed.backendId, parsed.path, title)

        /** 是否是可识别的伪 URI。 */
        fun isMediaUri(uri: String): Boolean = MediaUriCodec.isMediaUri(uri)
    }
}
