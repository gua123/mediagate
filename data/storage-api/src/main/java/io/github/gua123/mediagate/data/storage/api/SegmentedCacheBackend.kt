package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 分段缓存（**plan 4.1 降级链的第二级**）：把远端文件按固定大小的段落到本地，
 * 之后的随机读（拖拽 seek、抽帧、ASR）先看本地、缺段再拉。
 *
 * 为什么需要它：
 * - 有些后端**根本不支持随机读**（FTP 没有 REST、WebDAV 忽略 Range）——直接播只能从头顺读，
 *   一拖拽就废；分段缓存让这类后端也能"跳着读"（代价是先把目标段拉下来）；
 * - 支持随机读的后端也受益：同一次播放里 seek 来 seek 去，重复的段不必反复过网。
 *
 * 关键设计：
 * - **段是最小单位**（默认 4 MB）：拉一段就缓存一段，段内偏移随意读；
 * - **写穿式落盘**：段文件先写 `.tmp` 再改名，避免半截段被当成完整段；
 * - **LRU 淘汰**：按段文件的最后访问时间淘汰到 [maxBytes] 以内；
 * - **随机读能力**：本包装器声明 `randomAccess = true`——这正是降级链的意义
 *   （上层不需要知道底下那个后端会不会 seek）；
 * - **失败不缓存**：拉段失败就抛出去，绝不留下"看起来有、其实半截"的段。
 *
 * @param delegate 被包装的后端。
 * @param rootDir 段文件根目录（App 里传 cacheDir 下的子目录）。
 * @param segmentBytes 段大小；越大越省请求，越小越省磁盘与流量。
 * @param maxBytes 缓存总上限（超出按 LRU 淘汰）。
 * @param io 磁盘与网络 IO 的调度器。
 */
class SegmentedCacheBackend(
    private val delegate: StorageBackend,
    private val rootDir: File,
    private val segmentBytes: Long = DEFAULT_SEGMENT_BYTES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 读到某段后**顺手预读**后面几段（0 = 关闭）。 */
    private val readAheadSegments: Int = DEFAULT_READ_AHEAD_SEGMENTS,
    /** 预读用的作用域；为 null 时不做预读（单测与其它调用方不受影响）。 */
    private val readAheadScope: CoroutineScope? = null,
) : StorageBackend {

    init {
        require(segmentBytes > 0L) { "segmentBytes 必须为正：$segmentBytes" }
        require(maxBytes >= segmentBytes) { "maxBytes 不能小于一个段：$maxBytes < $segmentBytes" }
    }

    /** 正在下载的段（单飞：同一段不会被下两次，不同段可以同时下——预读才有意义）。 */
    private val inFlight = mutableMapOf<String, Deferred<File>>()

    /** 保护 [inFlight] 的锁（只在取放 map 时持有，**不覆盖下载过程**）。 */
    private val inFlightLock = Mutex()

    /** 没给预读作用域时的兜底（下载任务挂在它上面；随进程结束）。 */
    private val fallbackScope = CoroutineScope(SupervisorJob() + io)

    override val id: String get() = delegate.id

    /**
     * 能力声明：对外**支持随机读**（内部按段补齐），并行度沿用被包装后端。
     */
    override val caps: Caps get() = delegate.caps.copy(randomAccess = true)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = delegate.list(dir, page)

    override suspend fun stat(path: String): RemoteEntry = delegate.stat(path)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        // 记住"当前在读哪个文件"：淘汰时保护它的段（用户要求：保留单文件缓存，下次打开更快）
        activePath = path
        val size = knownSize(path)
        return CachedStream(path = path, start = offset, size = size, requestedLength = length)
    }

    /**
     * 文件大小（带内存缓存）。
     *
     * 为什么要缓存：播放器每 seek 一次就会重开数据源，而 [openRead] 需要文件长度来算流长度；
     * 每次都 `stat` 等于给每次拖拽多加一个网络往返。文件在同一会话里几乎不会变。
     */
    private suspend fun knownSize(path: String): Long {
        synchronized(sizeCache) { sizeCache[path] }?.let { return it }
        val size = delegate.stat(path).size
        if (size >= 0L) synchronized(sizeCache) { sizeCache[path] = size }
        return size
    }

    /** 文件大小缓存（LRU 64 条）。 */
    private val sizeCache = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
    }

    override suspend fun write(path: String, data: InputStream) = delegate.write(path, data)

    override suspend fun probe(): ProbeReport = delegate.probe()

    override fun close() {
        delegate.close()
    }

    // ------------------------------------------------------------ 缓存管理

    /**
     * 预取一段数据（**R4 / R6**：拖拽落点已知时先把目标段拉进缓存）。
     *
     * best-effort：失败只记日志——预取失败不该影响真正的播放读取。
     *
     * @param offset 起始字节；调用方通常用 TS 索引算出的关键帧偏移。
     */
    suspend fun prefetch(path: String, offset: Long, length: Long = segmentBytes) {
        val start = offset.coerceAtLeast(0L)
        val end = start + length.coerceAtLeast(1L)
        var index = start / segmentBytes
        val lastIndex = (end - 1L) / segmentBytes
        while (index <= lastIndex) {
            try {
                ensureSegment(path, index)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "预取分段失败：$path #$index", t)
                return
            }
            index++
        }
    }

    /** 已缓存的字节数（诊断/单测用）。 */
    suspend fun cachedBytes(): Long = withContext(io) {
        segmentFiles().sumOf { it.length() }
    }

    /**
     * **清空所有缓存段**（2026-10-03 设置页新增「缓存上限」时一并给的出口：
     * 用户把上限调小、或想立刻释放空间时，不必去系统设置里清 App 数据）。
     *
     * @return 实际删掉的字节数。
     */
    suspend fun clearAll(): Long = withContext(io) {
        val files = segmentFiles()
        var freed = 0L
        for (file in files) {
            val length = file.length()
            if (file.delete()) freed += length
        }
        // 目录留着（下次取段直接用），只清内容
        synchronized(sizeCache) { sizeCache.clear() }
        freed
    }

    /** 丢弃某个文件的全部缓存段（换根目录 / 文件已变时调用）。 */
    suspend fun clearFile(path: String) {
        withContext(io) {
            dirFor(path).deleteRecursively()
        }
    }

    private fun dirFor(path: String): File = File(rootDir, hash(delegate.id + "|" + path))

    private fun segmentFile(path: String, index: Long): File = File(dirFor(path), index.toString() + SEGMENT_SUFFIX)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 32)

    private fun segmentFiles(): List<File> =
        rootDir.listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
            .filter { it.isFile && it.name.endsWith(SEGMENT_SUFFIX) }

    /**
     * 确保第 [index] 段在本地（有就直接返回）。
     *
     * 并发：同一条视频可能有好几个读协程（Media3 的探测 + 播放），用互斥锁串行化段写入，
     * 避免同一段被下两次、也避免 LRU 淘汰与写入打架。
     */
    private suspend fun ensureSegment(path: String, index: Long): File {
        val file = segmentFile(path, index)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            scheduleReadAhead(path, index)
            return file
        }
        // 单飞：同一段只下一次（不同段可并行 → 预读才有意义）
        val key = path + "#" + index
        val existing = inFlightLock.withLock { inFlight[key] }
        if (existing != null) return existing.await()
        val job = scopeForDownload().async { downloadSegment(path, index, file) }
        inFlightLock.withLock { inFlight[key] = job }
        try {
            val result = job.await()
            scheduleReadAhead(path, index)
            return result
        } finally {
            inFlightLock.withLock { inFlight.remove(key) }
        }
    }

    /**
     * **当前正在读的文件**（2026-10-03 用户要求：「保留单文件缓存，下次加载时可快速打开」）。
     *
     * 淘汰时**跳过它的段**：正在播放/浏览的这个文件的缓存不许被别的文件挤掉，
     * 这样"退出去再进来"就是直接命中本地，秒开。
     */
    @Volatile
    private var activePath: String? = null

    /**
     * 按"当前并发数"取块：[limit] 由用户随时可改，所以不能用固定容量的信号量直接表达。
     *
     * 做法：**每块占 1 个硬许可**（[chunkPermits] 容量 = 上限，兜住"别开太多连接"），
     * 再用在飞计数 + 短轮询执行"当前并发数"这个软限制。
     *
     * 为什么可以轮询：块下载本身是**秒级**的网络传输，10 ms 的检查间隔开销可以忽略；
     * 反过来（用固定容量信号量直接当限制用）会导致"一个块抢走全部许可、其余块排队"，
     * 也就是并发退化成串行——那是错的（已在单测里暴露过）。
     */
    /**
     * 下载一个段：**一个请求、顺序写完**（2026-10-03 用户要求「把并发也去掉」）。
     *
     * 原来的"段内拆块并发"在高延迟链路上收益有限（瓶颈是往返次数而不是连接数），
     * 却引入了并发限制、许可池、随机写入这一堆复杂度——现在回到单请求顺序下载，
     * 行为更好预测，也少一层出错的可能。
     */
    private suspend fun downloadSegment(path: String, index: Long, file: File): File {
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
            val start = index * segmentBytes
            val length = segmentBytes.coerceAtLeast(1L)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + TMP_SUFFIX)
            try {
                val written = withContext(io) {
                    delegate.openRead(path, start, length).use { stream ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var total = 0L
                            while (total < length) {
                                val want = minOf(buffer.size.toLong(), length - total).toInt()
                                val read = stream.read(buffer, 0, want)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                total += read
                            }
                            output.flush()
                            total
                        }
                    }
                }
                if (written <= 0L) throw StorageException.Unknown("分段下载为空：$path @$start")
                withContext(io) {
                    if (!temporary.renameTo(file)) {
                        temporary.copyTo(file, overwrite = true)
                        temporary.delete()
                    }
                    file.setLastModified(System.currentTimeMillis())
                    evictIfNeeded()
                }
            } catch (e: CancellationException) {
                temporary.delete()
                throw e
            } catch (t: Throwable) {
                temporary.delete()
                throw t
            }
        return file
    }

    /** 预读：当前段读完后，顺手把后面几段也拉进缓存（上限内并行）。 */
    private fun scheduleReadAhead(path: String, index: Long) {
        val scope = readAheadScope ?: return
        val ahead = readAheadSegments
        if (ahead <= 0) return
        for (step in 1..ahead) {
            val target = index + step
            val file = segmentFile(path, target)
            if (file.isFile && file.length() > 0L) continue
            scope.launch {
                runCatching { ensureSegment(path, target) }
                    .onFailure { AppLog.d(TAG, "预读失败（不影响播放）：$path 第 $target 段 — ${it.message}") }
            }
        }
    }

    /** 段下载用的作用域（[readAheadScope] 没给就临时起一个，保证单飞 map 生命周期正确）。 */
    private fun scopeForDownload(): CoroutineScope = readAheadScope ?: fallbackScope

    /** 超出上限就按最后访问时间淘汰（跳过正在被读的段由调用方保证：段是整段读入的）。 */
    private suspend fun evictIfNeeded() {
        val files = segmentFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        // **优先保住"当前正在读的那个文件"**（2026-10-03 用户要求「保留单文件缓存，
        // 下次加载时可快速打开」）：第一轮只淘汰别的文件，这个文件的段留着，
        // 于是"退出去再进来"直接命中本地、秒开。
        val keepDir = activePath?.let { dirFor(it).absolutePath }
        total = evictPass(files, total, skipDir = keepDir)
        // 第二轮**不再保护**：如果光淘汰别的文件还是超上限（比如就一个超大文件在放），
        // 那就连它最旧的段一起淘汰——缓存必须有界，否则会吃满用户存储。
        if (total > maxBytes) evictPass(files, total, skipDir = null)
    }

    /**
     * 按 LRU 淘汰一轮，返回剩余总字节数。
     *
     * @param skipDir 该目录下的段本轮跳过；传 null 表示不跳过任何目录。
     */
    private fun evictPass(files: List<File>, startedTotal: Long, skipDir: String?): Long {
        var total = startedTotal
        for (file in files) {
            if (total <= maxBytes) break
            if (skipDir != null && file.parentFile?.absolutePath == skipDir) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
        return total
    }

    // ------------------------------------------------------------ 流实现

    /**
     * 基于分段缓存的随机读流。
     *
     * 语义与 [RangeStream] 一致：`position` 相对 [start]，[length] 为可读字节数。
     */
    private inner class CachedStream(
        private val path: String,
        private val start: Long,
        private val size: Long,
        requestedLength: Long,
    ) : RangeStream {

        private val mutex = Mutex()

        @Volatile
        private var cursor = 0L

        @Volatile
        private var closed = false

        override val length: Long =
            if (size < 0L) -1L
            else if (requestedLength < 0L) (size - start).coerceAtLeast(0L)
            else requestedLength.coerceAtMost((size - start).coerceAtLeast(0L))

        override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len <= 0) return 0
            val current = cursor
            if (length >= 0L && current >= length) return -1
            val remaining = if (length >= 0L) length - current else Long.MAX_VALUE
            val want = minOf(len.toLong(), remaining).toInt()
            var written = 0
            // 跨段补齐：一次 read 可能横跨两个段，按段循环填满缓冲区（与文件流的行为一致，
            // 免得调用方拿到一堆 8 字节的短读）
            while (written < want) {
                val absolute = start + current + written
                val index = absolute / segmentBytes
                val inSegment = (absolute % segmentBytes).toInt()
                val file = ensureSegment(path, index)
                val chunk = minOf((want - written).toLong(), segmentBytes - inSegment).toInt()
                val read = withContext(io) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(inSegment.toLong())
                        raf.read(buf, off + written, chunk)
                    }
                }
                if (read <= 0) break
                written += read
            }
            if (written <= 0) return -1
            mutex.withLock { cursor += written }
            return written
        }

        override suspend fun seek(position: Long) {
            mutex.withLock { cursor = position.coerceAtLeast(0L) }
        }

        override fun position(): Long = cursor

        override fun close() {
            closed = true
        }
    }


    companion object {

        private const val TAG = "segmented-cache"

        /** 默认段大小：4 MB（plan 4.1「分段缓存」；一次请求够大又不至于拖太久）。 */
        const val DEFAULT_SEGMENT_BYTES: Long = 4L * 1024 * 1024

        /** 默认预读段数（读到某段后顺手拉后面几段）。 */
        const val DEFAULT_READ_AHEAD_SEGMENTS: Int = 2

        /** 默认上限：1 GB（手机上比 plan 里的 4 GB 保守，避免挤爆缓存分区）。 */
        const val DEFAULT_MAX_BYTES: Long = 1024L * 1024 * 1024

        /** 段与临时文件后缀。 */
        const val SEGMENT_SUFFIX = ".seg"
        const val TMP_SUFFIX = ".tmp"

        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** 等待"并发槽位"的轮询间隔（毫秒）；块下载是秒级，这点开销可忽略。 */
        private const val CHUNK_SLOT_POLL_MS = 10L
    }
}
