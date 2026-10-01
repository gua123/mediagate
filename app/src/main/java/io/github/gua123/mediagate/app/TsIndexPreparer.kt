package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.tsext.TsIndex
import io.github.gua123.mediagate.media.tsext.TsIndexKey
import io.github.gua123.mediagate.media.tsext.TsIndexStore
import io.github.gua123.mediagate.media.tsext.TsIndexer
import io.github.gua123.mediagate.media.tsext.TsScanUpdate
import io.github.gua123.mediagate.media.tsext.openRandomAccessSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TS 索引的准备与取用（**R3 / R4**）。
 *
 * 为什么需要它：`:media:tsext` 的索引/扫描器此前**只活在单测里**，App 一次都没用过
 * （验收记录里"R4 索引尚未接进播放器"）。这一层把索引真正接进来，做两件**能落地**的事：
 *
 * 1. **R3 的"无 PCR 提示"**：扫描时统计到的 PCR 个数是 0，说明这个 TS 没有时间基准，
 *    拖拽一定不准——界面要如实提示，并把「修复时间戳」这条出口指给用户；
 * 2. **R4 的拖拽预取**：用索引把"时间"换算成"关键帧字节偏移"，
 *    提前把落点那一段读进 [io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend]，
 *    减少拖拽后第一帧的等待。
 *
 * **诚实的边界**：这里**没有**替换 Media3 的 seek 实现（那要 fork TsExtractor 的 SeekMap）。
 * Media3 仍然用它自己的二分查找定位，索引只是把落点附近的数据预热好。所以对外**不宣称**
 * "毫秒级拖拽"——真正的收益是"落点这段不用等网络"。
 *
 * @param rootDir 索引缓存目录（App 里传 cacheDir 下的子目录）。
 * @param maxScanBytes 单次扫描的字节上限：超过就先用手上的部分索引（绝不为了索引把整部电影读完）。
 */
class TsIndexPreparer(
    private val rootDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxScanBytes: Long = DEFAULT_MAX_SCAN_BYTES,
) {

    private val store = TsIndexStore(rootDir)

    /** 进程内已备好的索引（按后端路径），省掉重复读盘/重扫。 */
    private val prepared = ConcurrentHashMap<String, Prepared>()

    /** 一次准备的结果。 */
    data class Prepared(
        /** 扫出来的（可能是部分的）索引。 */
        val index: TsIndex,
        /** 文件里有没有 PCR（false = R3 的"无 PCR 样本"，拖拽会不准）。 */
        val hasPcr: Boolean,
        /** 是否扫到文件尾。 */
        val complete: Boolean,
    )

    /** 已经准备好的索引（拖拽预取时用）；没有返回 null。 */
    fun indexOf(path: String): TsIndex? = prepared[path]?.index

    /**
     * 准备 [path] 的 TS 索引：内存 → 落盘缓存 → 现场扫描（有字节上限）。
     *
     * @return null = 不是 TS / 打不开 / 扫描失败（调用方按"没有索引"处理即可，不影响播放）。
     */
    suspend fun prepare(backend: StorageBackend, path: String): Prepared? {
        prepared[path]?.let { return it }
        if (!isTs(path)) return null
        val entry = try {
            withContext(io) { backend.stat(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "TS 索引：stat 失败 " + path, t)
            return null
        }
        val key = TsIndexKey(
            backendId = backend.id,
            path = path,
            size = entry.size,
            mtime = entry.mtime,
        )
        val summary = readSummary(key)
        val cached = withContext(io) { store.load(key) }
        if (cached != null && summary != null) {
            return Prepared(cached, hasPcr = summary, complete = cached.complete).also { prepared[path] = it }
        }

        val scanned = scan(backend, path) ?: return null
        withContext(io) {
            runCatching { store.save(key, scanned.index) }
                .onFailure { AppLog.w(TAG, "TS 索引写盘失败：" + path, it) }
        }
        writeSummary(key, scanned.hasPcr)
        prepared[path] = scanned
        return scanned
    }

    /** 现场扫描（带上限；到上限就用部分索引——够用来预取，不值得把整部片子读完）。 */
    private suspend fun scan(backend: StorageBackend, path: String): Prepared? {
        val source = try {
            withContext(io) { backend.openRandomAccessSource(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "TS 索引：打开数据源失败 " + path, t)
            return null
        }
        return source.use {
            val indexer = TsIndexer(it, io)
            var partial: Prepared? = null
            try {
                indexer.scan().collect { update ->
                    when (update) {
                        is TsScanUpdate.Progress -> {
                            if (update.bytesScanned >= maxScanBytes) {
                                // 到上限：停下，用已经拿到的关键帧（下面从 checkpoint 里取）
                                throw ScanBudgetReached
                            }
                        }

                        is TsScanUpdate.Keyframe -> {
                            partial = Prepared(
                                index = TsIndex(
                                    points = update.checkpoint.points,
                                    videoPid = update.checkpoint.videoPid,
                                    durationMs = update.point.timeMs.takeIf { it > 0L },
                                    scannedBytes = update.point.byteOffset,
                                    complete = false,
                                ),
                                hasPcr = update.checkpoint.stats.pcrSamples > 0L,
                                complete = false,
                            )
                        }

                        is TsScanUpdate.Completed -> {
                            partial = Prepared(
                                index = update.index,
                                hasPcr = update.stats.pcrSamples > 0L,
                                complete = true,
                            )
                        }

                        is TsScanUpdate.Failed -> {
                            // collect 的 lambda 是 crossinline，不能非局部 return——用异常跳出（下面统一接住）
                            AppLog.w(TAG, "TS 索引扫描失败：" + path + " → " + update.error.message)
                            throw ScanStopped
                        }
                    }
                }
            } catch (e: ScanBudgetReached) {
                // 到达字节上限：正常收工，用手上的部分索引
            } catch (e: ScanStopped) {
                // 扫描器明确报失败：能拿到多少算多少
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "TS 索引扫描异常：" + path, t)
            }
            partial
        }
    }

    // ------------------------------------------------------------ 摘要（PCR 标记）

    /** 记录"这份索引对应的文件有没有 PCR"（索引文件本身不存统计量）。 */
    private fun summaryFile(key: TsIndexKey): File = File(rootDir, key.hash + SUMMARY_SUFFIX)

    private suspend fun readSummary(key: TsIndexKey): Boolean? = withContext(io) {
        val file = summaryFile(key)
        if (!file.isFile) return@withContext null
        runCatching { file.readText().trim() == HAS_PCR }.getOrNull()
    }

    private suspend fun writeSummary(key: TsIndexKey, hasPcr: Boolean) = withContext(io) {
        runCatching {
            rootDir.mkdirs()
            summaryFile(key).writeText(if (hasPcr) HAS_PCR else NO_PCR)
        }.onFailure { AppLog.w(TAG, "TS 索引摘要写盘失败", it) }
    }

    /** 是不是 TS 家族（只有这些才值得建索引）。 */
    private fun isTs(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in TS_EXTENSIONS

    /** 扫描到达字节上限（内部控制流）。 */
    private object ScanBudgetReached : RuntimeException(null, null, false, false)

    /** 扫描器报了失败（内部控制流，避免在 crossinline lambda 里非局部 return）。 */
    private object ScanStopped : RuntimeException(null, null, false, false)

    companion object {

        private const val TAG = "ts-index-app"

        /** 单次扫描的字节上限：128 MB（大约前几分钟的内容，足够预取与判断有没有 PCR）。 */
        const val DEFAULT_MAX_SCAN_BYTES: Long = 128L * 1024 * 1024

        /** 摘要文件后缀与内容。 */
        const val SUMMARY_SUFFIX = ".pcr"
        const val HAS_PCR = "pcr"
        const val NO_PCR = "no-pcr"

        /** 认得的 TS 家族扩展名。 */
        val TS_EXTENSIONS: Set<String> = setOf("ts", "m2ts", "mts", "tp", "trp")
    }
}
