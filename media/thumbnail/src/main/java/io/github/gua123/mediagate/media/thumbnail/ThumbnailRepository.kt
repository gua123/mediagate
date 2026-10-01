package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.asRandomAccessSource

/**
 * 缩略图流水线（R5 缩略图，plan 4.4 / 4.10）。
 *
 * 一次请求的顺序固定为：
 *
 *     内存 → 磁盘 → 负缓存 → 抽帧（主策略 MMR，按 10% → 1% → 25% 换位重试）→ FFmpeg 兜底 → null + 负缓存
 *
 * 命中任何一层缓存都不碰远端；负缓存命中说明「刚失败过」，[ThumbnailCache.negativeTtlMs] 内直接返回失败，
 * 避免列表滚动时对同一个坏文件反复发请求（plan 4.4「失败负缓存」）。
 *
 * 并发上限（plan 4.4）：默认 [DEFAULT_PARALLELISM] = 3；SFTP/FTP 这类单会话后端降到
 * [SLOW_PARALLELISM] = 2——判定依据是 [StorageBackend.caps] 的 `maxParallelReads <= 2`
 * 或后端 id 前缀（sftp/ftp/ftps）。限流用 [Semaphore] 实现，等待许可本身是可取消的，
 * 配合流水线里的 `ensureActive()`，离屏/回收时能立刻停下来（plan 4.4「离屏取消」）。
 *
 * 按媒体类型分三条流水线（**M1-F**，plan 4.4 三行策略，选择逻辑见 [ThumbnailPipeline.select]）：
 * - 视频 → [ThumbnailPipeline.FRAME]：主策略换位重试 + FFmpeg 兜底；
 * - 音频 → [ThumbnailPipeline.AUDIO_ARTWORK]：[audioArtwork]（MMR 内嵌封面），**不走抽帧**；
 * - 图片 → [ThumbnailPipeline.IMAGE_PREVIEW]：[imagePreview]（解码 → 缩放 → 重编码），
 *   并让缓存 key 带上 [ThumbnailVariant.IMAGE_PREVIEW] 与实际编码扩展名。
 * 两条新分支失败同样写失败负缓存，语义与视频一致。
 *
 * 可替换性：所有提取器都从构造参数注入，真机用 [MediaMetadataRetrieverFrameExtractor] +
 * [FfmpegFrameExtractor] + [EmbeddedArtworkExtractor] + [ImageThumbnailExtractor]，
 * 单测用假实现；时长探测同样可注入（[durationProbe]）。
 *
 * 图片路径（Coil Fetcher）也可以复用同一套 key 与缓存：
 * [keyFor] 拿 key，[cached] 读缓存，写回直接调 [ThumbnailCache.put]。
 *
 * @param cache 两级缓存 + 负缓存。
 * @param primary 主策略抽帧器（MMR）。
 * @param fallback 兜底抽帧器（FFmpeg）；传 null 表示没有兜底，主策略失败即失败。
 * @param durationProbe 时长探测；默认复用 [primary] 若它实现了 [MediaDurationProbe]。
 * @param targetWidth 目标宽度（像素），同时参与 key 的构成。
 * @param parallelism 普通后端并发上限。
 * @param slowParallelism SFTP/FTP 等单会话后端的并发上限。
 * @param io 打开数据源等 IO 操作所在调度器。
 */
class ThumbnailRepository(
    private val cache: ThumbnailCache,
    private val primary: FrameExtractor,
    private val fallback: FrameExtractor? = null,
    private val durationProbe: MediaDurationProbe? = primary as? MediaDurationProbe,
    val targetWidth: Int = DEFAULT_TARGET_WIDTH,
    parallelism: Int = DEFAULT_PARALLELISM,
    slowParallelism: Int = SLOW_PARALLELISM,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 音频内嵌封面提取器（M1-F，plan 4.4 音频行）；null = 未接线，音频退回抽帧分支。 */
    private val audioArtwork: FrameExtractor? = null,
    /** 图片缩略图流水线（M1-F，plan 4.4 图片行）；null = 未接线，图片退回抽帧分支。 */
    private val imagePreview: ImagePreviewPipeline? = null,
) {

    /** 普通后端限流器（本地/WebDAV：多连接可并行）。 */
    private val fastLimiter = Semaphore(parallelism.coerceAtLeast(1))

    /** 单会话后端限流器（SFTP/FTP：并发高了会互相拖慢甚至被服务端掐掉）。 */
    private val slowLimiter = Semaphore(slowParallelism.coerceAtLeast(1))

    /**
     * 取本次请求的缓存 key（图片 / 音频路径也可以用它复用同一份缓存）。
     *
     * key 的用途维度与扩展名由 [pipelineFor] 决定（M1-F）：图片缩略图用
     * [ThumbnailVariant.IMAGE_PREVIEW] + [ImagePreviewPipeline.format] 的扩展名，
     * 视频帧 / 音频封面用 [ThumbnailVariant.FRAME]（沿用既有 .webp 命名口径）。
     */
    fun keyFor(entry: RemoteEntry, backend: StorageBackend): ThumbnailKey {
        val pipeline = pipelineFor(entry)
        val isImagePreview = pipeline == ThumbnailPipeline.IMAGE_PREVIEW
        return ThumbnailKey.of(
            backendId = backend.id,
            entry = entry,
            targetWidth = targetWidth,
            kind = MediaKindGuesser.guess(entry.name),
            variant = if (isImagePreview) ThumbnailVariant.IMAGE_PREVIEW else ThumbnailVariant.FRAME,
            format = if (isImagePreview) imagePreview?.format else null,
        )
    }

    /**
     * 只查缓存（内存 → 磁盘），**不触发抽帧**。
     *
     * 给图片路径（Coil Fetcher 自己解码、自己回写）复用同一个缓存用：先看有没有，
     * 没有就自己解码，然后 [ThumbnailCache.put] 回来。
     */
    suspend fun cached(entry: RemoteEntry, backend: StorageBackend): ByteArray? =
        cache.get(keyFor(entry, backend))

    /**
     * 取缩略图字节（按 [entry] 的媒体类型自动分流：视频抽帧 / 音频内嵌封面 / 图片缩略图）。
     *
     * @param entry 目录项（size/mtime 决定缓存 key，所以必须是列目录时的真实值）。
     * @param backend 该条目所在的后端。
     * @param positionRatio 首选帧位置（相对时长，默认 0.1 = 10%，plan 4.4）；只对视频抽帧有意义。
     * @return 缩略图字节；成功会写回两级缓存，彻底失败返回 null 并写负缓存。
     */
    suspend fun thumbnail(
        entry: RemoteEntry,
        backend: StorageBackend,
        positionRatio: Double = DEFAULT_POSITION_RATIO,
    ): ByteArray? {
        if (entry.isDirectory) return null
        val key = keyFor(entry, backend)

        // 快路径：两级缓存命中就不必抢并发许可
        cache.get(key)?.let { return it }
        if (cache.isNegative(key)) return null

        return limiterFor(backend).withPermit {
            // 等许可期间可能已经有别的协程把同一个 key 填好了，拿许可后再确认一次
            cache.get(key)?.let { return@withPermit it }
            if (cache.isNegative(key)) return@withPermit null

            val bytes = runCatchingExtract(entry, backend, positionRatio)
            if (bytes == null || bytes.isEmpty()) {
                cache.markFailed(key)
                null
            } else {
                try {
                    cache.put(key, bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // 缓存写不进去不影响本次结果
                    AppLog.w(TAG, "写缩略图缓存失败：${entry.path}", t)
                }
                bytes
            }
        }
    }

    // ------------------------------------------------------------ 抽帧策略

    private suspend fun runCatchingExtract(
        entry: RemoteEntry,
        backend: StorageBackend,
        positionRatio: Double,
    ): ByteArray? = try {
        // 整段抽帧编排统一切到 IO：抽帧器内部也会切，但打开数据源/探测时长同样不该占用调用方线程
        withContext(io) { extract(entry, backend, positionRatio) }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.w(TAG, "抽帧流程异常：${entry.path}", t)
        null
    }

    /** 打开数据源 → 按媒体类型选流水线 → 出字节。全程只打开一次数据源。 */
    private suspend fun extract(
        entry: RemoteEntry,
        backend: StorageBackend,
        positionRatio: Double,
    ): ByteArray? {
        val stream = try {
            backend.openRead(entry.path)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "打开数据源失败：${entry.path}", t)
            return null
        }

        return stream.use { rangeStream ->
            val source = rangeStream.asRandomAccessSource(
                knownSize = if (entry.size > 0) entry.size else rangeStream.length,
            )
            when (pipelineFor(entry)) {
                // 音频（M1-F）：只走内嵌封面。抽帧分支不适用于音频，失败即失败，交给负缓存。
                ThumbnailPipeline.AUDIO_ARTWORK -> {
                    val extractor = audioArtwork ?: return null
                    val bytes = runExtractor(extractor, "音频内嵌封面", source, entry.mimeType, ANY_FRAME_MS)
                    if (bytes == null || bytes.isEmpty()) {
                        AppLog.w(TAG, "音频没有可用的内嵌封面：${entry.path}")
                        null
                    } else {
                        bytes
                    }
                }

                // 图片（M1-F）：解码 → 按目标宽度重编码；同样不做换位重试。
                ThumbnailPipeline.IMAGE_PREVIEW -> {
                    val pipeline = imagePreview ?: return null
                    val bytes = runExtractor(pipeline.extractor, "图片缩略图", source, entry.mimeType, ANY_FRAME_MS)
                    if (bytes == null || bytes.isEmpty()) {
                        AppLog.w(TAG, "图片缩略图生成失败：${entry.path}")
                        null
                    } else {
                        bytes
                    }
                }

                ThumbnailPipeline.FRAME -> framePipeline(source, entry, positionRatio)
            }
        }
    }

    /** 视频抽帧：探时长 → 主策略 10% / 1% / 25% 换位重试 → FFmpeg 兜底。 */
    private suspend fun framePipeline(
        source: RandomAccessSource,
        entry: RemoteEntry,
        positionRatio: Double,
    ): ByteArray? {
        val durationMs = probeDuration(source, entry.mimeType)
        val positions = positionsFor(durationMs, positionRatio)

        for ((index, positionMs) in positions.withIndex()) {
            currentCoroutineContext().ensureActive()
            val bytes = runExtractor(primary, "主策略", source, entry.mimeType, positionMs)
            if (bytes != null && bytes.isNotEmpty()) return bytes
            if (index < positions.lastIndex) {
                AppLog.d(TAG, "主策略取帧失败，换位重试：${entry.path} @${positionMs}ms")
            }
        }

        val fallbackExtractor = fallback ?: return null
        currentCoroutineContext().ensureActive()
        val bytes = runExtractor(fallbackExtractor, "FFmpeg 兜底", source, entry.mimeType, positions.first())
        if (bytes != null && bytes.isNotEmpty()) {
            AppLog.i(TAG, "FFmpeg 兜底抽帧成功：${entry.path}")
            return bytes
        }
        AppLog.w(TAG, "缩略图抽帧全部失败：${entry.path}（duration=$durationMs）")
        return null
    }

    /** 本次请求该走哪条流水线（纯逻辑，见 [ThumbnailPipeline.select]）。 */
    private fun pipelineFor(entry: RemoteEntry): ThumbnailPipeline = ThumbnailPipeline.select(
        kind = MediaKindGuesser.guess(entry.name),
        audioArtworkAvailable = audioArtwork != null,
        imagePreviewAvailable = imagePreview != null,
    )

    private suspend fun runExtractor(
        extractor: FrameExtractor,
        label: String,
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
    ): ByteArray? = try {
        extractor.extract(source, mimeHint, positionMs, targetWidth)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // 抽帧器约定不抛异常，这里再兜一层：缩略图失败不该影响列表渲染
        AppLog.w(TAG, "$label 抽帧异常：positionMs=$positionMs ${t.message}", t)
        null
    }

    private suspend fun probeDuration(source: RandomAccessSource, mimeHint: String?): Long? {
        val probe = durationProbe ?: return null
        return try {
            probe.durationMs(source, mimeHint)?.takeIf { it > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "探测媒体时长失败：${t.message}", t)
            null
        }
    }

    /**
     * 计算候选帧位置（plan 4.4：取 10% 帧，失败换 1% / 25%）。
     *
     * 拿不到时长时（例如容器没写时长、远端只给了部分数据）退化为固定 1 秒探一次，
     * 再试「任意一帧」（负数，MMR 的约定语义）。
     */
    private fun positionsFor(durationMs: Long?, positionRatio: Double): List<Long> {
        if (durationMs != null && durationMs > 0) {
            return listOf(
                positionRatio.coerceIn(0.0, 1.0),
                RETRY_RATIO_EARLY,
                RETRY_RATIO_LATE,
            ).map { (durationMs * it).toLong() }.distinct()
        }
        return listOf(DEFAULT_POSITION_MS, ANY_FRAME_MS)
    }

    /**
     * 选限流器：SFTP/FTP 这类「单会话 + 通道少」的后端要给播放让路，降到 2；
     * 判定同时看能力声明与 id 前缀（plan 4.4）。
     */
    private fun limiterFor(backend: StorageBackend): Semaphore =
        if (isSlowBackend(backend)) slowLimiter else fastLimiter

    /** 是否属于需要降并发的后端（能力声明 maxParallelReads <= 2，或 id 前缀是 sftp/ftp/ftps）。 */
    fun isSlowBackend(backend: StorageBackend): Boolean {
        if (backend.caps.maxParallelReads <= SLOW_MAX_PARALLEL_READS) return true
        val id = backend.id.lowercase()
        return SLOW_ID_PREFIXES.any { id.startsWith(it) }
    }

    companion object {

        private const val TAG = "thumbnail"

        /** 首选帧位置：10% 处（plan 4.4）。 */
        const val DEFAULT_POSITION_RATIO = 0.1

        /** 重试位置①：1%。 */
        const val RETRY_RATIO_EARLY = 0.01

        /** 重试位置②：25%。 */
        const val RETRY_RATIO_LATE = 0.25

        /** 拿不到时长时的固定探测位置（毫秒）。 */
        const val DEFAULT_POSITION_MS = 1_000L

        /** 负数 = 任意一帧（MMR 约定）。 */
        const val ANY_FRAME_MS = -1L

        /** 列表缩略图默认宽度（像素）。 */
        const val DEFAULT_TARGET_WIDTH = 256

        /** 默认并发上限（plan 4.4）。 */
        const val DEFAULT_PARALLELISM = 3

        /** SFTP/FTP 并发上限（plan 4.4）。 */
        const val SLOW_PARALLELISM = 2

        /** 能力声明里低于等于该值即视为单会话后端。 */
        const val SLOW_MAX_PARALLEL_READS = 2

        private val SLOW_ID_PREFIXES = listOf("sftp", "ftps", "ftp")
    }
}
