package io.github.gua123.mediagate.media.thumbnail

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.security.MessageDigest

/**
 * 缩略图缓存 key（R5 缩略图，plan 4.4 / 第 7 章 thumb_meta）。
 *
 * 字段口径严格对齐 plan：`backendId + path + size + mtime + kind`。
 * - **同 key 稳定**：同一份文件（同一后端、同一路径、同样的 size/mtime/类型）永远得到同一个 key，
 *   可以直接当内存/磁盘缓存的索引，也能落库（thumb_meta）在进程重启后复用；
 * - **变更即失效**：文件大小或修改时间一变 key 必变，旧缩略图自然作废，不需要额外的失效通知；
 * - [kind] 取自 [MediaKindGuesser]（视频/音频/图片走不同流水线，见 plan 4.4 的三行策略）。
 *
 * 额外带上 [targetWidth] 作为**第六段**：磁盘布局 `<cacheDir>/<kind>/<hash前2位>/<hash>.<扩展名>`
 * 里不含宽度，若不把宽度并入哈希，同一文件的不同缩略图尺寸会互相覆盖。同一宽度下 key 依然稳定，
 * plan 的五个字段依旧是 key 的语义主体。
 *
 * **M1-F 新增两段**：
 * - [variant]：用途维度（视频帧 / 音频封面 / 图片缩略图）。同一文件的不同用途产出不同内容，
 *   不进哈希就会互相覆盖；
 * - [extension]：落盘扩展名，默认跟随 [variant]，图片缩略图按**实际编码格式**传入
 *   （见 [of] 的 `format` 参数）——这样缓存文件名与内容格式一致，不会出现
 *   「JPEG/PNG 原图命名成 .webp」（M1-F 的硬要求）。
 *
 * 纯 Kotlin，无任何 Android API，可直接 JVM 单测。
 */
data class ThumbnailKey(
    /** 后端 id（[io.github.gua123.mediagate.data.storage.api.StorageBackend.id]），区分本地/WebDAV/SFTP/FTP。 */
    val backendId: String,
    /** 后端内的完整路径（POSIX 风格）。 */
    val path: String,
    /** 文件大小（字节）；未知为 -1（[RemoteEntry.size] 的口径）。 */
    val size: Long,
    /** 最后修改时间（Unix 毫秒）；未知为 0（[RemoteEntry.mtime] 的口径）。 */
    val mtime: Long,
    /** 媒体大类，决定磁盘一级目录与走哪条抽帧/解码流水线。 */
    val kind: MediaKind,
    /** 目标宽度（像素）；[NO_WIDTH] 表示不区分宽度。 */
    val targetWidth: Int = NO_WIDTH,
    /** 用途维度（M1-F）：视频帧 / 音频内嵌封面 / 图片缩略图，见 [ThumbnailVariant]。 */
    val variant: ThumbnailVariant = ThumbnailVariant.FRAME,
    /** 落盘扩展名（不带点）；默认跟随 [variant]，图片缩略图按实际编码格式传入。 */
    val extension: String = variant.defaultExtension,
) {

    /** 内容哈希：SHA-256 前 16 字节的十六进制（128 bit），跨进程、跨次运行稳定。 */
    val hash: String by lazy { sha256Hex(canonical()) }

    /** 哈希前 2 位做一级分片目录，避免单个目录塞进几十万个文件。 */
    val shard: String get() = hash.substring(0, 2)

    /** 相对缓存根目录的落盘路径，形如 `video/ab/abcdef….webp`。 */
    val relativePath: String get() = "${kind.name.lowercase()}/$shard/$hash.$extension"

    /** 参与哈希的规范串：用换行分隔，避免路径里出现分隔符导致不同输入撞同一个串。 */
    private fun canonical(): String = listOf(
        SCHEMA,
        backendId,
        path,
        size.toString(),
        mtime.toString(),
        kind.name,
        targetWidth.toString(),
        variant.name,
        extension,
    ).joinToString("\n")

    companion object {

        /**
         * 哈希口径版本号：算法或字段一变就升版本，老缓存自然全部失效。
         *
         * v1 → v2（M1-F）：哈希串加入用途 [ThumbnailVariant] 与落盘扩展名两段。
         */
        private const val SCHEMA = "mediagate-thumb-v2"

        /**
         * 默认磁盘缓存扩展名（plan 4.4：磁盘 webp），即 [ThumbnailVariant.FRAME] 的口径。
         *
         * 注意：视频抽帧这一路是**缓存文件命名**而不是内容格式声明——主策略
         * [MediaMetadataRetrieverFrameExtractor] 默认就编 WebP；FFmpeg 兜底受 ffmpeg-kit-min
         * 不含 libwebp 限制只能出 JPEG，而 key 在抽帧之前就已确定，无从得知最终是哪一条产出的。
         * 图片缩略图这一路不同：内容与格式在上层完全可控，因此 [ThumbnailVariant.IMAGE_PREVIEW]
         * 的扩展名严格跟随实际编码格式（M1-F）。
         */
        const val EXTENSION = "webp"

        /** 不区分目标宽度。 */
        const val NO_WIDTH = 0

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * 由后端 id + 目录项构造 key（kind 默认按文件名猜，见 [MediaKindGuesser]）。
         *
         * @param targetWidth 目标宽度，与 [ThumbnailRepository] 的配置保持一致。
         * @param kind 显式指定媒体类型（例如 HLS 播放列表要按视频处理时）。
         * @param variant 用途维度；默认 [ThumbnailVariant.FRAME]（视频抽帧 / 音频封面）。
         * @param format 实际写出的编码格式；给了就用它的扩展名（图片缩略图必传），
         *   不给则用 [variant] 的默认扩展名。
         */
        fun of(
            backendId: String,
            entry: RemoteEntry,
            targetWidth: Int = NO_WIDTH,
            kind: MediaKind = MediaKindGuesser.guess(entry.name),
            variant: ThumbnailVariant = ThumbnailVariant.FRAME,
            format: ThumbnailImageFormat? = null,
        ): ThumbnailKey = ThumbnailKey(
            backendId = backendId,
            path = entry.path,
            size = entry.size,
            mtime = entry.mtime,
            kind = kind,
            targetWidth = targetWidth,
            variant = variant,
            extension = format?.extension ?: variant.defaultExtension,
        )

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(32)
            // 取前 16 字节（128 bit）：文件名更短，对自用缓存而言碰撞概率可以忽略
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                out.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
            }
            return out.toString()
        }
    }
}
