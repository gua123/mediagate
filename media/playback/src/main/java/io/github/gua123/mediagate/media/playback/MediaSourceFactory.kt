package io.github.gua123.mediagate.media.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend

/**
 * 由「后端 + 条目」造 Media3 播放对象的工厂（R1 音频 / R4 拖拽 / 本地与远端同一套）。
 *
 * 关键点：**本地与远端走完全相同的代码路径**——地址永远是 [MediaUri] 伪 URI，
 * 真正的字节从 [BackendDataSource] 取，所以本地文件、WebDAV、SFTP、FTP 在播放层没有分支。
 *
 * 音频用 [ProgressiveMediaSource]（mp3/flac/ogg/m4a… 的通用选择）；
 * HLS 与视频的抽帧/软解分支属于 M2，届时在这里按类型分派即可。
 */
@UnstableApi
object MediaSourceFactory {

    /** 给某个后端造数据源工厂（Media3 的 Player.setMediaSourceFactory 用它）。 */
    fun dataSourceFactory(backend: StorageBackend): DataSource.Factory = BackendDataSourceFactory(backend)

    /** 目录项 → MediaItem（标题用文件名，媒体 id 用路径）。 */
    fun mediaItem(entry: RemoteEntry, backend: StorageBackend): MediaItem =
        mediaItem(path = entry.path, backend = backend, title = entry.name, mimeType = entry.mimeType)

    /**
     * 路径 → MediaItem。
     *
     * @param path 后端内路径。
     * @param backend 条目所在后端（后端 id 会编进伪 URI）。
     * @param title 通知栏/锁屏显示的名字；null 时取文件名。
     * @param mimeType 有就用（容器不给时长、扩展名又缺失时有用）。
     * @param artwork 通知栏 / 锁屏封面（R18）；null = 没有（系统显示默认图标）。
     */
    fun mediaItem(
        path: String,
        backend: StorageBackend,
        title: String? = null,
        mimeType: String? = null,
        artwork: ByteArray? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title ?: fileNameOf(path))
            .setIsBrowsable(false)
            .setIsPlayable(true)
        if (artwork != null && artwork.isNotEmpty()) {
            metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val builder = MediaItem.Builder()
            .setUri(MediaUri.format(backend.id, path))
            .setMediaId(path)
            .setMediaMetadata(metadata.build())
        if (!mimeType.isNullOrEmpty()) builder.setMimeType(mimeType)
        return builder.build()
    }

    /** MediaItem → 渐进式 MediaSource（音频主线）。 */
    fun progressiveSource(item: MediaItem, dataSourceFactory: DataSource.Factory): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)

    /** 从 MediaItem 里取回后端 id（断点续播的 key 之一）；取不到返回 null。 */
    fun backendIdOf(item: MediaItem): String? = uriOf(item)?.let { MediaUri.fromUri(it.toString())?.backendId }

    /** 从 MediaItem 里取回后端内路径；取不到返回 null。 */
    fun pathOf(item: MediaItem): String? {
        if (item.mediaId.isNotEmpty()) return item.mediaId
        val uri = uriOf(item) ?: return null
        return MediaUri.fromUri(uri.toString())?.path
    }

    /**
     * 条目的 URI。
     *
     * 优先看 localConfiguration（本进程内直接 setMediaItems 时它在），
     * 退化到 requestMetadata（跨进程/经 MediaSession 传输后只剩它）。
     */
    private fun uriOf(item: MediaItem): android.net.Uri? =
        item.localConfiguration?.uri ?: item.requestMetadata.mediaUri

    /** 取路径最后一段当标题。 */
    private fun fileNameOf(path: String): String = path.substringAfterLast('/')
}
