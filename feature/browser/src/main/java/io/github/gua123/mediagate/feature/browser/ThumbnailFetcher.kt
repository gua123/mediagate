package io.github.gua123.mediagate.feature.browser

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.CachePolicy
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository

/**
 * 交给 Coil 的缩略图请求（R5 缩略图，plan 4.4）。
 *
 * 之所以单独做这个 data 对象，而不是直接把 `RemoteEntry` 丢给 Coil：
 * 1. 缩略图必须知道**哪个后端**（缓存 key 的一部分，也决定走 File 还是 SAF 读）；
 * 2. 它自带内容相等语义（data class），可直接当 Coil 内存缓存 key 的输入；
 * 3. 组合函数里只做「构造对象」，真正的 IO 交给 Coil 的 Fetcher 在自己的协程上下文里跑。
 *
 * @property entry 目录项（size/mtime 参与缓存 key，必须是列目录时的真实值）。
 * @property backend 条目所在的后端。
 * @property kind 媒体大类，决定走抽帧还是原图解码。
 * @property positionRatio 视频取帧位置（相对时长，plan 4.4 默认 10%）。
 */
data class ThumbnailRequest(
    val entry: RemoteEntry,
    val backend: StorageBackend,
    val kind: MediaKind = MediaKindGuesser.guess(entry.name),
    val positionRatio: Double = ThumbnailRepository.DEFAULT_POSITION_RATIO,
) {

    /** Coil 内存缓存 key（口径与 [io.github.gua123.mediagate.media.thumbnail.ThumbnailKey] 保持一致）。 */
    val memoryCacheKey: String
        get() = buildString {
            append(backend.id).append('|')
            append(entry.path).append('|')
            append(entry.size).append('|')
            append(entry.mtime).append('|')
            append(kind.name).append('|')
            append(positionRatio)
        }
}

/**
 * 缩略图 Fetcher（R5，plan 4.4）：把 [ThumbnailRequest] 变成 Coil 能解码的字节流。
 *
 * 分工：视频 / 图片 / 音频统一走 [ThumbnailRepository.thumbnail] 按 `MediaKind` 选流水线
 * （视频抽帧 + FFmpeg 兜底；图片采样解码重编码；音频取 MMR 内嵌封面），
 * 仓库内部自带两级缓存、并发上限、负缓存与离屏取消，**绝不在组合函数里同步抽帧**；
 * 其余类型（字幕 / 未知）返回 null，由界面显示类型图标，不报错、不崩。
 *
 * 失败语义：抛异常 → Coil 进入 error 状态 → [androidx.compose.foundation.Image] 位置留给底下的类型图标。
 */
class ThumbnailFetcher private constructor(
    private val request: ThumbnailRequest,
    private val repository: ThumbnailRepository,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = load() ?: throw IllegalStateException("缩略图不可用")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        when (request.kind) {
            // 视频（抽帧）/ 图片（预览重编码）/ 音频（内嵌封面）分别走各自流水线，
            // 缓存、并发限流、负缓存、失败兜底都在仓库里，这里只负责调用
            MediaKind.VIDEO, MediaKind.IMAGE, MediaKind.AUDIO ->
                repository.thumbnail(request.entry, request.backend, request.positionRatio)
            // 字幕 / 未知类型没有缩略图概念，交给界面显示类型图标
            MediaKind.SUBTITLE, MediaKind.OTHER -> null
        }
    }

    /** Coil 的 [Fetcher.Factory]：把 [ThumbnailRequest] 认领下来。 */
    class Factory(private val repository: ThumbnailRepository) : Fetcher.Factory<ThumbnailRequest> {

        override fun create(data: ThumbnailRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, repository, options)
    }

}

/** Coil 内存缓存 key：同一后端 + 同一路径 + 同一 size/mtime + 同一类型 = 同一张图。 */
object ThumbnailRequestKeyer : Keyer<ThumbnailRequest> {

    override fun key(data: ThumbnailRequest, options: Options): String = data.memoryCacheKey
}

/**
 * 建一个**只服务缩略图**的 Coil [ImageLoader]（R5）。
 *
 * 为什么不用全局单例：缩略图 Fetcher 需要注入 [ThumbnailRepository]（由 :app 的 AppContainer 持有），
 * 而 :app 不引 Coil；把 loader 建在页面里，模块边界最干净。Coil 自身按组合生命周期取消请求，
 * 列表滚出屏幕即取消（配合仓库里的并发限流实现 plan 4.4 的「离屏取消」）。
 *
 * 磁盘缓存关掉：视频缩略图已经由两级 [io.github.gua123.mediagate.media.thumbnail.ThumbnailCache] 落盘，
 * 再存一份纯属浪费（图片原图也由系统/后端自身的缓存兜着）。
 *
 * @param thumbnails :app 注入的缩略图仓库。
 */
@Composable
fun rememberThumbnailImageLoader(thumbnails: ThumbnailRepository): ImageLoader {
    val context: Context = LocalPlatformContext.current
    val loader = remember(context, thumbnails) {
        ImageLoader.Builder(context)
            .components {
                add(ThumbnailFetcher.Factory(thumbnails))
                add(ThumbnailRequestKeyer)
            }
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    DisposableEffect(loader) {
        onDispose { loader.shutdown() }
    }
    return loader
}
