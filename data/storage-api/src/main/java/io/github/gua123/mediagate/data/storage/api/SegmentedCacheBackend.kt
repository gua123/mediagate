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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * **缓存命名空间**（2026-10-03 用户提问：「同一个文件在局域网留了缓存，切到公网还要重新缓存吗？」）。
     *
     * 默认用后端 id（含主机端口），也就是"按地址"分家——那样**局域网缓存到公网要重下**。
     * :app 现在传**连接 id**：同一个连接下的多个地址（局域网 / 公网）共用同一份缓存，
     * 在家用局域网缓存过的文件，出门切公网直接复用已缓存的部分。
     */
    private val namespace: String = delegate.id,
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
    /**
     * 正在下载的段（同一段只下一次；读协程**边下边读**）。
     *
     * 2026-10-20 用户反馈「nPlayer 加载快一倍、已看过的部分拖动不卡」之后改：
     * 以前是「整段 4 MB 下完才可见」，现在**每写一块读者就能读一块**——
     * 拖动落点只等第一块（64 KiB）而不是整段，这是"拖动后不卡"的关键。
     */
    private val inFlight = mutableMapOf<String, SegmentJob>()

    /**
     * 缓存代际：每次 [clearAll] +1（**2026-10-25 用户真机：清空缓存后播放一直转圈、网络不占用**）。
     *
     * 清空缓存时必须让**清空前启动的段下载任务**失效：旧任务可能还卡在一个已经没用的请求上，
     * 新播放如果 join 到它，就会一直等不到字节、又没有任何网络流量（看起来就是永远转圈）。
     * 任务在开始、写块、改名之前都核对代际；代际变了立刻作废。
     */
    @Volatile
    private var epoch: Long = 0L

    /** 一段正在下载的临时文件；[tempFile] 里的字节对读者可见（[onBytes] 累计已写入量）。 */
    private class SegmentJob(val finalFile: File, val epoch: Long) {
        /** 每个代际用自己的临时文件，旧任务即使还在写也不会和新任务抢同一个 .tmp。 */
        val tempFile: File = File(finalFile.parentFile, finalFile.name + "." + epoch + TMP_SUFFIX)

        private val lock = Object()
        private var written: Long = 0L
        private var finished: Boolean = false
        private var failure: Throwable? = null

        fun onBytes(total: Long) {
            synchronized(lock) {
                written = total
                lock.notifyAll()
            }
        }

        fun finish(error: Throwable?) {
            synchronized(lock) {
                failure = error
                finished = true
                lock.notifyAll()
            }
        }

        /** 等到至少 [target] 字节写好（或结束/出错）。 */
        fun awaitBytes(target: Long) {
            synchronized(lock) {
                while (!finished && failure == null && written < target) lock.wait(50)
            }
        }

        /** 等整段下完；返回失败原因（成功为 null）。 */
        fun awaitDone(): Throwable? {
            synchronized(lock) {
                while (!finished) lock.wait(50)
                return failure
            }
        }

        fun failureOrNull(): Throwable? = synchronized(lock) { failure }
    }

    /** 清空缓存时用来唤醒读者的内部失败（不是真的网络错误，调用方按"缓存已清空"处理）。 */
    private class CacheClearedException : RuntimeException("缓存已清空，旧的段下载任务已作废")

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
        val stamp = knownStamp(path)
        verifyStampOrDrop(path, stamp)
        val size = stamp?.size ?: -1L
        return CachedStream(path = path, start = offset, size = size, requestedLength = length)
    }

    /**
     * 文件大小（带内存缓存）。
     *
     * 为什么要缓存：播放器每 seek 一次就会重开数据源，而 [openRead] 需要文件长度来算流长度；
     * 每次都 `stat` 等于给每次拖拽多加一个网络往返。文件在同一会话里几乎不会变。
     */
    /**
     * 文件指纹：大小 + 修改时间 + ETag（**2026-10-03 用户提问后加固**）。
     *
     * @param size 字节数；-1 = 未知。
     * @param mtime 修改时间（Unix 毫秒）；0 = 未知。
     * @param etag 内容版本标识；协议不支持时为 null。
     */
    private data class FileStamp(val size: Long, val mtime: Long, val etag: String?) {

        /** 存进 `.size` 边车文件的一行文本（老版本只写大小，读取时按"仅比大小"兼容）。 */
        fun encode(): String = size.toString() + "|" + mtime + "|" + (etag ?: "")

        companion object {
            /** 解析边车内容；解析不出来返回 null（当作"没有记录"）。 */
            fun decode(raw: String): FileStamp? {
                val parts = raw.trim().split("|")
                val size = parts.getOrNull(0)?.toLongOrNull() ?: return null
                // 老格式（只有大小）：mtime/etag 视为未知 ⇒ 只比大小
                val mtime = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val etag = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                return FileStamp(size, mtime, etag)
            }
        }
    }

    /**
     * 取文件指纹（带缓存）。
     *
     * 只在第一次 `openRead` 时走一次 `stat`，之后同路径直接用缓存——
     * 所以"加指纹"这件事**不额外增加任何网络请求**（这也是它值得做的原因）。
     */
    private suspend fun knownStamp(path: String): FileStamp? {
        synchronized(stampCache) { stampCache[path] }?.let { return it }
        val entry = runCatching { delegate.stat(path) }.getOrNull() ?: return null
        if (entry.size < 0L) return null
        val stamp = FileStamp(size = entry.size, mtime = entry.mtime, etag = entry.etag)
        synchronized(stampCache) { stampCache[path] = stamp }
        return stamp
    }

    /** 文件指纹缓存（LRU 64 条，值里含上一次 stat 的结果）。 */
    private val stampCache = object : LinkedHashMap<String, FileStamp>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileStamp>?): Boolean = size > 64
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
    /**
     * 文件大小（给"预取尾段/按比例估算落点"用；走 [knownStamp] 的缓存，不额外发请求）。
     *
     * 拿不到（协议不支持 / stat 失败）返回 null——调用方据此**跳过**预取，别瞎猜偏移。
     */
    suspend fun knownSizeOf(path: String): Long? = knownStamp(path)?.size?.takeIf { it > 0L }

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

    /**
     * **并行预取**（**2026-10-14 用户要求**：「可否单文件多线程…在打开文件夹时自动进行缓存」）。
     *
     * 与 [prefetch] 的唯一区别：同一文件一次发 [concurrency] 个请求（按段分批），
     * 把"每次请求的往返延迟"藏起来——高延迟链路上这才是"更快"的来源（不是带宽）。
     *
     * **播放路径不受影响**：读数据仍旧是"要哪段取哪段、一个请求顺序写完"；
     * 而且因为**按段单飞**，预取正在下的段与播放要的段是同一个任务，不会重复下载。
     *
     * 失败只记日志、不抛出：预取永远不许把播放搞挂。
     */
    suspend fun prefetchParallel(
        path: String,
        offset: Long,
        length: Long = segmentBytes,
        concurrency: Int = SegmentBatches.DEFAULT_PREFETCH_CONCURRENCY,
    ) {
        val start = offset.coerceAtLeast(0L)
        val end = start + length.coerceAtLeast(1L)
        val firstIndex = start / segmentBytes
        val lastIndex = (end - 1L) / segmentBytes
        for (batch in SegmentBatches.batch(firstIndex, lastIndex, concurrency)) {
            try {
                coroutineScope {
                    batch.map { index -> async { ensureSegment(path, index) } }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "并行预取失败：$path @$batch", t)
                return
            }
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
        // ① 让清空前启动的段任务全部作废：先换代，再摘掉 in-flight 表并唤醒等待者。
        //    否则新播放会 join 到一个已经"不属于当前缓存"的旧任务上，一直等不到字节。
        epoch += 1L
        val staleJobs = inFlightLock.withLock {
            val snapshot = inFlight.values.toList()
            inFlight.clear()
            snapshot
        }
        staleJobs.forEach { it.finish(CacheClearedException()) }
        if (staleJobs.isNotEmpty()) {
            AppLog.i(TAG, "清空缓存：epoch=" + epoch + "，作废 " + staleJobs.size + " 个正在下载的段任务")
        }

        // ② 删正式段 + 临时文件（旧任务写到一半的 .tmp 也要清掉；新任务用新代际的临时文件）。
        val files = segmentFiles() + tempFiles()
        var freed = 0L
        for (file in files) {
            val length = file.length()
            if (file.delete()) freed += length
        }
        // 目录留着（下次取段直接用），只清内容
        activePath = null
        synchronized(stampCache) { stampCache.clear() }
        freed
    }

    /**
     * 一条「已缓存的文件」（**给界面看**，2026-10-15 用户要求「告诉我哪些缓存过，哪些没有」）。
     *
     * @param dirName 磁盘上的目录名（哈希）——**删除时用它**，所以名称未知的老缓存也能删掉；
     * @param path 原始路径；老版本缓存没写 .path 边车时为 null；
     * @param bytes 已缓存字节；@param segments 已缓存段数；@param totalBytes 文件总大小（有指纹时才有）。
     */
    data class CachedFileEntry(
        val dirName: String,
        val path: String?,
        val bytes: Long,
        val segments: Int,
        val totalBytes: Long?,
        /** 是不是**手动缓存**（固定，不被自动淘汰）；老缓存默认 false。 */
        val pinned: Boolean = false,
    ) {

        /** 已缓存比例（0..1）；总大小未知时为 null（界面显示「—」而不是瞎算）。 */
        val coverage: Float?
            get() {
                val total = totalBytes ?: return null
                if (total <= 0L) return null
                return (bytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }

        /** 界面显示名（路径末段；名称未知的老缓存如实说明）。 */
        val displayName: String
            get() = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "（旧版本缓存，名称未知）"
    }

    /**
     * 列出**当前缓存里的所有文件**（按已缓存字节从多到少）。
     *
     * 纯读盘、不发网络请求；读不出来的目录跳过（缓存是尽力而为的东西，不因为它报错）。
     */
    suspend fun cachedFiles(): List<CachedFileEntry> = withContext(io) {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.mapNotNull { dir ->
            runCatching {
                val segments = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SEGMENT_SUFFIX) }.orEmpty()
                if (segments.isEmpty()) return@runCatching null
                val path = File(dir, PATH_STAMP).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                val total = File(dir, SIZE_STAMP).takeIf { it.isFile }
                    ?.readText()?.let { FileStamp.decode(it) }?.size?.takeIf { it > 0L }
                CachedFileEntry(
                    dirName = dir.name,
                    path = path,
                    bytes = segments.sumOf { it.length() },
                    segments = segments.size,
                    totalBytes = total,
                    pinned = File(dir, PIN_STAMP).isFile,
                )
            }.getOrNull()
        }.sortedByDescending { it.bytes }
    }

    /**
     * 删掉**某一个文件**的全部缓存段（返回释放的字节数）。
     *
     * 按目录名删，所以连「名称未知」的老缓存也能清掉；同时把内存里的指纹缓存一起丢掉，
     * 否则下次打开同一个文件会以为指纹还在、跳过栅栏。
     */
    suspend fun removeCached(entry: CachedFileEntry): Long = withContext(io) {
        val dir = File(rootDir, entry.dirName)
        val freed = runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        runCatching { dir.deleteRecursively() }
        entry.path?.let { path -> synchronized(stampCache) { stampCache.remove(path) } }
        freed
    }

    /** 丢弃某个文件的全部缓存段（换根目录 / 文件已变时调用）。 */
    suspend fun clearFile(path: String) {
        withContext(io) {
            dirFor(path).deleteRecursively()
        }
    }

    private fun dirFor(path: String): File = File(rootDir, hash(namespace + "|" + path))

    /**
     * **过期缓存护栏**：同一个路径背后的文件可能被换掉（服务器上重新上传、同名不同内容）。
     *
     * 做法：首次见到某个路径时把**文件指纹**（大小 + 修改时间 + ETag）写进 `.size` 边车文件；
     * 以后每次打开都比对一次，指纹不一样就把该路径的段全部删掉重下
     * （宁可多下一次，也不能拿旧字节当新视频播）。
     *
     * 三条口径：
     * - **多比几个字段**：只比大小会漏掉"同名同大小但内容不同"（重新压制、换音轨）的情况；
     * - **未知字段不参与判定**（协议不给 mtime/etag 时就是 0/null），避免"比不了就当成变了"而反复重下；
     * - **老格式兼容**：边车里只有大小（旧版本写的）时，退化成只比大小。
     *
     * 代价：一次 `stat`（已在 [knownStamp] 缓存里，不额外发请求）+ 一次本地小文件读写。
     */
    private suspend fun verifyStampOrDrop(path: String, stamp: FileStamp?) {
        if (stamp == null || stamp.size < 0L) return
        val dir = dirFor(path)
        val stampFile = File(dir, SIZE_STAMP)
        val recorded = withContext(io) {
            runCatching {
                stampFile.takeIf { it.isFile }?.readText()?.let { FileStamp.decode(it) }
            }.getOrNull()
        }
        if (recorded == null) {
            writeStamp(dir, path, stamp)
            return
        }
        if (!differs(recorded, stamp)) return
        AppLog.w(TAG, "文件指纹变了（" + recorded.encode() + " → " + stamp.encode() + "），丢掉旧缓存：" + path)
        clearFile(path)
        writeStamp(dir, path, stamp)
    }

    /** 指纹是否"实质性不同"（未知字段不参与判定）。 */
    private fun differs(a: FileStamp, b: FileStamp): Boolean {
        if (a.size != b.size) return true
        if (a.mtime > 0L && b.mtime > 0L && a.mtime != b.mtime) return true
        if (!a.etag.isNullOrEmpty() && !b.etag.isNullOrEmpty() && a.etag != b.etag) return true
        return false
    }

    /**
     * 落盘指纹（best-effort：写不进去也不影响播放）。
     *
     * **顺带把原始路径写进 `.path` 边车**（2026-10-15 用户要求「增加一个可视化界面，告诉我哪些缓存过，哪些没有」）：
     * 缓存目录名是 `namespace|path` 的哈希，光看目录名根本认不出是哪个文件——没有这份边车，
     * 「缓存明细」就只能显示一堆哈希。老版本留下的目录没有这个文件，界面上如实显示"名称未知"，但照样能删。
     */
    private suspend fun writeStamp(dir: File, path: String, stamp: FileStamp) {
        withContext(io) {
            runCatching {
                dir.mkdirs()
                File(dir, SIZE_STAMP).writeText(stamp.encode())
                val pathFile = File(dir, PATH_STAMP)
                // 只在内容变化时写，别每次 openRead 都碰盘
                if (!pathFile.isFile || pathFile.readText() != path) pathFile.writeText(path)
            }
        }
    }

    private fun segmentFile(path: String, index: Long): File = File(dirFor(path), index.toString() + SEGMENT_SUFFIX)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 32)

    private fun segmentFiles(): List<File> =
        rootDir.listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
            .filter { it.isFile && it.name.endsWith(SEGMENT_SUFFIX) }

    /** 正在写/遗留的临时段文件。 */
    private fun tempFiles(): List<File> =
        rootDir.listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
            .filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }

    /**
     * 确保第 [index] 段**整段**在本地（预取 / 手动缓存用；播放路径不调它）。
     *
     * 播放路径走 [CachedStream] 的 readChunk：它不等整段，边下边读。
     */
    private suspend fun ensureSegment(path: String, index: Long): File {
        val file = segmentFile(path, index)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val job = ensureJob(path, index) ?: return file
        val error = withContext(io) { job.awaitDone() }
        if (error != null) throw error
        return file
    }

    /**
     * 拿到（必要时启动）第 [index] 段的下载任务；该段已经在本地时返回 null。
     *
     * 单飞：同一段只启动一个任务，播放、预取、多个读协程共享同一个 [SegmentJob]。
     */
    private suspend fun ensureJob(path: String, index: Long): SegmentJob? {
        val file = segmentFile(path, index)
        if (file.isFile && file.length() > 0L) return null
        val key = path + "#" + index
        val currentEpoch = epoch
        val existing = inFlightLock.withLock { inFlight[key] }
        if (existing != null && existing.epoch == currentEpoch) return existing
        // 旧代际的任务（清空缓存前启动的）不许复用：摘掉并唤醒它的等待者，重新开一个
        if (existing != null) {
            inFlightLock.withLock { if (inFlight[key] === existing) inFlight.remove(key) }
            existing.finish(CacheClearedException())
            AppLog.w(TAG, "丢弃清空缓存前的旧段任务：" + path + " #" + index)
        }
        val created = SegmentJob(file, currentEpoch)
        val winner = inFlightLock.withLock {
            val current = inFlight[key]
            if (current != null) {
                current
            } else {
                inFlight[key] = created
                created
            }
        }
        if (winner === created) {
            scopeForDownload().launch { runDownloadJob(path, index, key, created) }
        }
        return winner
    }

    /**
     * 下载一段：**一块一块写临时文件、写一块通知读者一块**，下完再原子改名成正式段文件。
     *
     * 失败时删临时文件、把异常交给所有等待者；LRU 淘汰只打扫正式段文件，不会碰正在写的临时文件。
     */
    private suspend fun runDownloadJob(path: String, index: Long, key: String, job: SegmentJob) {
        var failure: Throwable? = null
        try {
            if (job.epoch != epoch) throw CacheClearedException()
            job.finalFile.parentFile?.mkdirs()
            withContext(io) {
                val start = index * segmentBytes
                val length = segmentBytes.coerceAtLeast(1L)
                delegate.openRead(path, start, length).use { stream ->
                    job.tempFile.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (total < length) {
                            if (job.epoch != epoch) throw CacheClearedException()
                            val want = minOf(buffer.size.toLong(), length - total).toInt()
                            val read = stream.read(buffer, 0, want)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            output.flush()
                            total += read
                            job.onBytes(total)
                        }
                        if (total <= 0L) throw StorageException.Unknown("分段下载为空：" + path + " @" + start)
                    }
                }
                if (job.epoch != epoch) throw CacheClearedException()
                if (!job.tempFile.renameTo(job.finalFile)) {
                    job.tempFile.copyTo(job.finalFile, overwrite = true)
                    job.tempFile.delete()
                }
                // 改名和清空可能擦肩而过：清空之后写出来的段不能再留下（否则"清空"不彻底）
                if (job.epoch != epoch) {
                    job.finalFile.delete()
                    throw CacheClearedException()
                }
                job.finalFile.setLastModified(System.currentTimeMillis())
                evictIfNeeded()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            job.tempFile.delete()
            failure = e
            throw e
        } catch (t: Throwable) {
            job.tempFile.delete()
            failure = t
        } finally {
            inFlightLock.withLock { if (inFlight[key] === job) inFlight.remove(key) }
            // 清空发生在本任务结束前：等待者拿到的必须是"已作废"，不能是成功/旧失败
            job.finish(if (job.epoch != epoch) CacheClearedException() else failure)
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

    /**
     * 把某个文件的缓存标记为**手动缓存**（用户"预约下载"的那些，2026-10-15 用户要求
     * 「增加手动批量多个文件的缓存方式」）：**固定住，不被自动淘汰清掉**。
     *
     * 落一个空边车文件 `.pin` 在缓存目录里，所以重启后仍然有效。
     */
    suspend fun setPinned(path: String, pinned: Boolean): Unit = withContext(io) {
        runCatching {
            val dir = dirFor(path)
            val file = File(dir, PIN_STAMP)
            if (pinned) {
                dir.mkdirs()
                if (!file.isFile) file.writeText(System.currentTimeMillis().toString())
            } else {
                file.delete()
            }
        }
        Unit
    }

    /** 单文件已缓存字节（手动缓存的进度用；只读一个目录，不扫描整个缓存）。 */
    suspend fun cachedBytesOf(path: String): Long = withContext(io) {
        val dir = dirFor(path)
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(SEGMENT_SUFFIX) }?.sumOf { it.length() } ?: 0L
    }

    /**
     * **严格下载一段**（手动缓存队列用，2026-10-15）：与 [prefetch] 的区别是**失败会抛异常**——
     * 队列要把"这一段下不来"如实记进任务状态，而不是像顺手预取那样只记日志就算了。
     */
    suspend fun downloadSegmentStrict(path: String, offset: Long, length: Long) {
        val start = offset.coerceAtLeast(0L)
        val end = start + length.coerceAtLeast(1L)
        var index = start / segmentBytes
        val lastIndex = (end - 1L) / segmentBytes
        while (index <= lastIndex) {
            ensureSegment(path, index)
            index++
        }
    }

    /** 这个文件的缓存是不是"手动缓存的"（固定，不参与自动淘汰）。 */
    suspend fun isPinned(path: String): Boolean = withContext(io) {
        File(dirFor(path), PIN_STAMP).isFile
    }

    /** 所有固定目录的绝对路径（淘汰时按目录跳过硬保护）。 */
    private fun pinnedDirs(): Set<String> =
        rootDir.listFiles()
            ?.filter { it.isDirectory && File(it, PIN_STAMP).isFile }
            ?.map { it.absolutePath }
            ?.toSet()
            .orEmpty()

    /**
     * 超出上限就按最后访问时间淘汰（跳过正在被读的段由调用方保证：段是整段读入的）。
     *
     * 三轮（2026-10-15 起多了"手动缓存"这一档）：
     * ① 先淘汰「既不固定、也不是正在读」的；
     * ② 再淘汰「正在读的那个」（缓存必须有界，否则会吃满用户存储），**但固定的仍然不碰**；
     * ③ 如果只剩固定缓存还是超上限，**就不淘汰它们**，只记日志——固定的东西被悄悄清掉比"占空间"更糟
     *   （缓存明细页会提示"固定的缓存不受自动清理"，用户可以自己取消固定或调大上限）。
     */
    private suspend fun evictIfNeeded() {
        val files = segmentFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val pinned = pinnedDirs()
        // **优先保住"当前正在读的那个文件"**（2026-10-03 用户要求「保留单文件缓存，
        // 下次加载时可快速打开」）：第一轮只淘汰别的文件，这个文件的段留着，
        // 于是"退出去再进来"直接命中本地、秒开。
        val keepDir = activePath?.let { dirFor(it).absolutePath }
        total = evictPass(files, total, skipDirs = pinned + listOfNotNull(keepDir))
        if (total > maxBytes) total = evictPass(files, total, skipDirs = pinned)
        if (total > maxBytes) {
            AppLog.w(TAG, "缓存超出上限，但剩下的都是手动缓存（固定）：" + total + " / " + maxBytes)
        }
    }

    /**
     * 按 LRU 淘汰一轮，返回剩余总字节数。
     *
     * @param skipDirs 这些目录（绝对路径）下的段本轮跳过。
     */
    private fun evictPass(files: List<File>, startedTotal: Long, skipDirs: Set<String>): Long {
        var total = startedTotal
        for (file in files) {
            if (total <= maxBytes) break
            val dir = file.parentFile?.absolutePath
            if (dir != null && dir in skipDirs) continue
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

        /** 复用的文件句柄（不要每次 read 都 open/seek/close：那是顺序读与拖动的固定开销）。 */
        private var reader: java.io.RandomAccessFile? = null
        private var readerFile: File? = null

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
            // 跨段补齐：一次 read 可能横跨两个段，按段循环填满缓冲区
            while (written < want) {
                val absolute = start + current + written
                val index = absolute / segmentBytes
                val inSegment = (absolute % segmentBytes).toInt()
                val chunk = minOf((want - written).toLong(), segmentBytes - inSegment).toInt()
                val read = readChunk(index, inSegment, chunk, buf, off + written)
                if (read <= 0) break
                written += read
            }
            if (written <= 0) return -1
            mutex.withLock { cursor += written }
            // 读完这一段就顺便把后面几段拉起来（后台，失败只记日志）
            scheduleReadAhead(path, (start + cursor - 1L).coerceAtLeast(0L) / segmentBytes)
            return written
        }

        /**
         * 读第 [index] 段里的 [chunk] 字节（段内偏移 [inSegment]）。
         *
         * 已缓存的段直接读磁盘；没缓存的段**不等整段**，等"要读到的那个位置已经写出来"就返回
         * （nPlayer 那套块级可见的等价做法）。
         */
        private suspend fun readChunk(
            index: Long,
            inSegment: Int,
            chunk: Int,
            buf: ByteArray,
            off: Int,
        ): Int {
            val file = segmentFile(path, index)
            if (file.isFile && file.length() > 0L) {
                return readFrom(file, inSegment, chunk, buf, off)
            }
            val job = ensureJob(path, index)
            if (job == null) return readFrom(file, inSegment, chunk, buf, off)
            withContext(io) { job.awaitBytes(inSegment.toLong() + chunk) }
            job.failureOrNull()?.let { throw it }
            val readable = if (file.isFile && file.length() > 0L) file else job.tempFile
            val available = withContext(io) { readable.length() } - inSegment
            if (available <= 0L) return 0
            val toRead = minOf(chunk.toLong(), available).toInt()
            return readFrom(readable, inSegment, toRead, buf, off)
        }

        private suspend fun readFrom(file: File, position: Int, len: Int, buf: ByteArray, off: Int): Int {
            return withContext(io) {
                var current = reader
                if (current == null || readerFile != file) {
                    runCatching { current?.close() }
                    current = java.io.RandomAccessFile(file, "r")
                    reader = current
                    readerFile = file
                }
                current.seek(position.toLong())
                current.read(buf, off, len)
            }
        }

        override suspend fun seek(position: Long) {
            mutex.withLock { cursor = position.coerceAtLeast(0L) }
        }

        override fun position(): Long = cursor

        override fun close() {
            runCatching { reader?.close() }
            reader = null
            readerFile = null
            closed = true
        }
    }

    companion object {

        private const val TAG = "segmented-cache"

        /** 默认段大小：4 MB（plan 4.1「分段缓存」；一次请求够大又不至于拖太久）。 */
        const val DEFAULT_SEGMENT_BYTES: Long = 4L * 1024 * 1024

        /** 默认预读段数（读到某段后顺手拉后面几段）。 */
        const val DEFAULT_READ_AHEAD_SEGMENTS: Int = 4

        /** 记录"首次见到这个路径时的文件大小"的边车文件名（用于发现文件被换掉）。 */
        private const val SIZE_STAMP = ".size"

        /** 记录原始路径的边车（缓存明细要显示文件名；没有它的老缓存显示「名称未知」）。 */
        private const val PATH_STAMP = ".path"

        /** 「手动缓存」的固定标记边车：存在即固定，自动淘汰不碰它。 */
        private const val PIN_STAMP = ".pin"

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
