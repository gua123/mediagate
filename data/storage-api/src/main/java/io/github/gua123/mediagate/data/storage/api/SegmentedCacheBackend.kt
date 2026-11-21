package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.redactPath
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val io: CoroutineDispatcher = StorageDispatchers.io,
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
    /**
     * **实际预读段数**（默认 = [readAheadSegments]，单连接时代的老行为不变）。
     *
     * 2026-11-20「SFTP 多连接加速」：并发段下载数决定同时占几条 SSH 连接，
     * 只预读 4 段的话 20 条连接里最多只有 4-5 条在干活，带宽仍然上不去。
     * :app 按后端 caps.maxParallelReads 传更大的值（SFTP 多连接 → 20 段），
     * 单会话后端（WebDAV / FTP）沿用小值。
     */
    private val readAheadWindow: Int = readAheadSegments,
    /** 预读用的作用域；为 null 时不做预读（单测与其它调用方不受影响）。 */
    private val readAheadScope: CoroutineScope? = null,
) : StorageBackend {

    init {
        require(segmentBytes > 0L) { "segmentBytes 必须为正：$segmentBytes" }
        require(maxBytes >= segmentBytes) { "maxBytes 不能小于一个段：$maxBytes < $segmentBytes" }
        require(readAheadWindow >= 0) { "预读段数不能为负：$readAheadWindow" }
    }

    /** 当前实际预读段数（设置页诊断卡展示用；多会话后端会大于用户设置）。 */
    val readAheadWindowSegments: Int get() = readAheadWindow

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

    /**
     * 段访问顺序（**LRU 修正，2026-10-25**）：以前只看段文件 mtime，而命中缓存时从不更新 mtime
     * （只有下载时更新），于是"刚看过的段"和"很久没看的段"在 LRU 眼里一样旧，热缓存可能被误淘汰。
     *
     * 这里用进程内单调序号记录最近访问；重启后没有记录的段按"最旧"处理（合理：这一轮还没读过它）。
     */
    private val accessSeq = AtomicLong(0L)
    private val accessTimes = ConcurrentHashMap<String, Long>()

    /** 诊断计数（只在内存里，重启归零；不影响缓存内容）。 */
    private val statHits = AtomicLong(0L)
    private val statMisses = AtomicLong(0L)
    private val statDownloadRequests = AtomicLong(0L)
    private val statDownloadedBytes = AtomicLong(0L)
    @Volatile
    private var recentThroughputBytesPerSec: Long = 0L

    /**
     * 一段正在下载的临时文件；[tempFile] 里的字节对读者可见（[onBytes] 累计已写入量）。
     *
     * **2026-11-21：等待从 `Object.wait(50)` 阻塞轮询换成 [MutableStateFlow] 挂起等待。**
     *
     * 旧实现（`synchronized(lock) { while (...) lock.wait(50) }`）是**阻塞**的，所以调用方必须先
     * `withContext(io)` 借一个存储线程去等——每段预读因此多吃一个 io 线程（段下载 1 个 + 等它的
     * 读者 1 个），20 段 = 40 > 32；池一满就落到"在提交线程内联执行阻塞体"那条死路上
     * （见 [StorageDispatchers] 的注释）。现在等待是真正的挂起：不占线程、可取消、`withTimeout` 照常生效。
     */
    private class SegmentJob(val finalFile: File, val epoch: Long) {
        /** 每个代际用自己的临时文件，旧任务即使还在写也不会和新任务抢同一个 .tmp。 */
        val tempFile: File = File(finalFile.parentFile, finalFile.name + "." + epoch + TMP_SUFFIX)

        /** 进度 + 结束状态；写入方在下载线程上（任意线程安全），读者用 `first { ... }` 挂起等。 */
        private val progress = MutableStateFlow(Progress())

        fun onBytes(total: Long) {
            progress.update { it.copy(written = total) }
        }

        fun finish(error: Throwable?) {
            progress.update { it.copy(finished = true, failure = error) }
        }

        /** 等到至少 [target] 字节写好（或结束/出错）；**挂起等待，不占线程**。 */
        suspend fun awaitBytes(target: Long) {
            progress.first { it.finished || it.failure != null || it.written >= target }
        }

        /** 等整段下完；返回失败原因（成功为 null）。 */
        suspend fun awaitDone(): Throwable? = progress.first { it.finished }.failure

        fun failureOrNull(): Throwable? = progress.value.failure

        /** 一次进度快照（StateFlow 会自动合并相同值，读侧只关心"够不够/结没结束"）。 */
        private data class Progress(
            val written: Long = 0L,
            val finished: Boolean = false,
            val failure: Throwable? = null,
        )
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

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> =
        delegate.list(dir, page).also { entries -> rememberSizes(entries) }

    override suspend fun stat(path: String): RemoteEntry =
        delegate.stat(path).also { entry -> rememberSizes(listOf(entry)) }

    /** 代理可以不联网直接拿到的文件大小（列目录/stat/openRead 时缓存）。 */
    override fun cachedSize(path: String): Long? =
        synchronized(stampCache) { stampCache[path]?.size }

    private fun rememberSizes(entries: List<RemoteEntry>) {
        for (entry in entries) {
            if (entry.isDirectory || entry.size < 0L) continue
            synchronized(stampCache) {
                stampCache[entry.path] = FileStamp(entry.size, entry.mtime, entry.etag)
            }
        }
    }

    /**
     * **绕过分段缓存直读**（Media3 兼容兜底用，2026-11-15）。
     *
     * 当 LibVLC 在远端卡住、切到 Media3 后又 join 到同一个卡死的 SegmentJob 时，Media3 也会停在 0:00。
     * 这个方法让 Media3 直接读 delegate（仍然有 TimeoutStorageBackend 的硬截止），用来看/绕过缓存层。
     */
    suspend fun openReadDirect(path: String, offset: Long, length: Long): RangeStream =
        delegate.openRead(path, offset, length)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
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
     * 文件指纹缓存的**协程锁**。
     * 2026-11-18：原用 synchronized，但 knownStamp 是 suspend 函数——协程可能在持锁期间挂起
     * （网络 IO / 线程切换 / 被取消），一旦挂起锁就不释放，其它协程永久等待。改用 Mutex。
     */
    private val stampMutex = Mutex()

    /** 预读失败退避表：key = path#index，value = 失败时刻（2026-11-18 事故后加） */
    private val failedSegments = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val prefetchBackoffMs = 5_000L

    /**
     * **每个文件最近一次已经扇出过预读的段号**（2026-11-21 第三轮审计 P1：派发风暴）。
     *
     * 为什么需要它：[CachedStream.read] 每读一块就调一次 [scheduleReadAhead]，而"块"是
     * 代理的 64 KiB 泵 / Media3 的 buffer——**4 MB 段按 64 KiB 读 = 同一段里会进来 64 次 read**，
     * 每次都扇出 `readAheadWindow`（多连接 SFTP 是 20）个 `launch`：一个段 = 1280 次派发，
     * 全是重复的（每个都只是去 `ensureSegment` 里撞单飞表）。按段号去重后同一段只扇出一次。
     */
    private val lastAheadIndex = ConcurrentHashMap<String, Long>()

    /**
     * 取文件指纹（带缓存）。
     *
     * 只在第一次 openRead 时走一次 stat，之后同路径直接用缓存——
     * 所以"加指纹"这件事**不额外增加任何网络请求**（这也是它值得做的原因）。
     */
    private suspend fun knownStamp(path: String): FileStamp? {
        stampMutex.withLock { stampCache[path] }?.let { return it }
        val entry = runCatching { delegate.stat(path) }.getOrNull() ?: return null
        if (entry.size < 0L) return null
        val stamp = FileStamp(size = entry.size, mtime = entry.mtime, etag = entry.etag)
        stampMutex.withLock { stampCache[path] = stamp }
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
                AppLog.w(TAG, "预取分段失败：" + redactPath(path) + " #" + index, t)
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
        // 预取必须给播放让路：至少留 1 条通道给起播/续播的读取。
        // 否则预取会把 SFTP 通道池占满，播放的 openRead 永远等不到许可（2026-11-17 真机实证）。
        val reservedForPlayback = (delegate.caps.maxParallelReads - 1).coerceAtLeast(1)
        val effectiveConcurrency = concurrency.coerceAtMost(reservedForPlayback)
        val start = offset.coerceAtLeast(0L)
        val end = start + length.coerceAtLeast(1L)
        val firstIndex = start / segmentBytes
        val lastIndex = (end - 1L) / segmentBytes
        for (batch in SegmentBatches.batch(firstIndex, lastIndex, effectiveConcurrency)) {
            try {
                coroutineScope {
                    batch.map { index -> async { ensureSegment(path, index) } }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "并行预取失败：" + redactPath(path) + " @" + batch, t)
                return
            }
        }
    }

    /** 已缓存的字节数（诊断/单测用）。 */
    suspend fun cachedBytes(): Long = withContext(io) {
        segmentFiles().sumOf { it.length() }
    }

    /** 运行统计（诊断卡显示；只读内存计数，不扫盘）。 */
    fun stats(): CacheStats = CacheStats(
        segmentHits = statHits.get(),
        segmentMisses = statMisses.get(),
        downloadRequests = statDownloadRequests.get(),
        downloadedBytes = statDownloadedBytes.get(),
        recentThroughputBytesPerSec = recentThroughputBytesPerSec,
    )

    /** 最近一次成功下载的吞吐（自适应预读用；0 = 还没有样本）。 */
    fun recentThroughputBytesPerSec(): Long = recentThroughputBytesPerSec

    /** 记一次"读到这个段"（LRU 用；命中与下载落盘都算）。 */
    private fun touch(file: File) {
        accessTimes[file.absolutePath] = accessSeq.incrementAndGet()
    }

    /** 段的最近访问序号；这一轮还没读过的段按 0（最旧）处理。 */
    private fun lastAccessOf(file: File): Long = accessTimes[file.absolutePath] ?: 0L

    private fun forgetAccess(file: File) {
        accessTimes.remove(file.absolutePath)
    }

    /** 丢掉某个目录下所有段的访问记录（删文件/清目录时用）。 */
    private fun forgetAccessUnder(dir: File) {
        val prefix = dir.absolutePath + File.separator
        accessTimes.keys.removeIf { it.startsWith(prefix) }
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
        accessTimes.clear()
        lastAheadIndex.clear()
        statHits.set(0L)
        statMisses.set(0L)
        statDownloadRequests.set(0L)
        statDownloadedBytes.set(0L)
        recentThroughputBytesPerSec = 0L
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
        forgetAccessUnder(dir)
        entry.path?.let { path -> synchronized(stampCache) { stampCache.remove(path) } }
        freed
    }

    /** 丢弃某个文件的全部缓存段（换根目录 / 文件已变时调用）。 */
    suspend fun clearFile(path: String) {
        withContext(io) {
            val dir = dirFor(path)
            dir.deleteRecursively()
            forgetAccessUnder(dir)
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
        AppLog.w(TAG, "文件指纹变了（" + recorded.encode() + " → " + stamp.encode() + "），丢掉旧缓存：" + redactPath(path))
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
            touch(file)
            statHits.incrementAndGet()
            return file
        }
        val job = ensureJob(path, index) ?: return file
        statMisses.incrementAndGet()
        // 挂起等（不再 withContext(io)：等待不需要线程，占了就是白吃一个许可）
        val error = job.awaitDone()
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
            AppLog.w(TAG, "丢弃清空缓存前的旧段任务：" + redactPath(path) + " #" + index)
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
        var downloaded = 0L
        val startedNanos = System.nanoTime()
        statDownloadRequests.incrementAndGet()
        try {
            if (job.epoch != epoch) throw CacheClearedException()
            job.finalFile.parentFile?.mkdirs()
            // **落盘循环不进许可闸门**（2026-11-21 第三轮审计修正理由）。
            // 许可的语义是"一段不中断的执行片"，不是"被整个任务持有"（见 StorageDispatchers 的类注释）：
            // 循环里的 `stream.read` 一挂起就还许可、醒来再要一格，所以这里**不是**"一次下载同时吃
            // 2 个许可"，也不是"许可被下载任务长期占着"。
            // 真正的收益是：**别让磁盘写（输出流 / flush / 改名 / 淘汰扫描）去占 32 格许可里的一格**。
            // 一次下载的落盘要横跨整段传输（公网段几十秒），占着一格就等于把那段时间里网络读的
            // 并发硬生生挤掉一格。文件落盘是本地 IO，放应用 IO 池即可（这一层不占存储闸门，
            // 只有 `stream.read` 真正读远端时才占）。
            withContext(downloadDispatcher) {
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
                        downloaded = total
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
                touch(job.finalFile)
                if (downloaded > 0L) {
                    statDownloadedBytes.addAndGet(downloaded)
                    val elapsedMs = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(1L)
                    val sample = downloaded * 1000L / elapsedMs
                    synchronized(this@SegmentedCacheBackend) {
                        recentThroughputBytesPerSec = if (recentThroughputBytesPerSec <= 0L) {
                            sample
                        } else {
                            (recentThroughputBytesPerSec * 3L + sample) / 4L
                        }
                    }
                }
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
    /**
     * 预读：当前段读完后，顺手把后面几段也拉进缓存（上限内并行）。
     *
     * 这里是「多连接能不能吃满」的闸门：并发段数 = 同时在飞的 SSH 通道数。
     */
    private fun scheduleReadAhead(path: String, index: Long) {
        val scope = readAheadScope ?: return
        val ahead = readAheadWindow
        if (ahead <= 0) return
        // 段号去重：同一个段只扇出一次（否则 4 MB 段按 64 KiB 读会触发 64 次 × 20 段 = 1280 次派发）
        if (!markAheadScheduled(path, index)) return
        for (step in 1..ahead) {
            val target = index + step
            val file = segmentFile(path, target)
            if (file.isFile && file.length() > 0L) continue
            val failKey = path + "#" + target
            val failedAt = failedSegments[failKey]
            if (failedAt != null && System.currentTimeMillis() - failedAt < prefetchBackoffMs) continue
            scope.launch {
                runCatching { ensureSegment(path, target) }
                .onSuccess { failedSegments.remove(failKey) }
                .onFailure { e ->
                    failedSegments[failKey] = System.currentTimeMillis()
                    AppLog.d(TAG, "预读失败（不影响播放，已退避 5s）：" + redactPath(path) + " 第 " + target + " 段 — " + e.message)
                }
            }
        }
    }

    /**
     * [scheduleReadAhead] 的去重闸门：返回 true 表示"这个段号还没扇出过"，由它继续做预读。
     *
     * 用 `compute` 而不是 `put`：并发读同一个文件的两个协程会同时进来，`compute` 按 key 串行
     * （同一段只会有**一个**调用拿到 true）。回退 seek 到别的段时"段号变了"就重新扇出一轮——
     * 那一轮里已经下好的段会被 `isFile` 跳过、正在下的段会撞 `ensureJob` 的单飞表，不会重复过网。
     */
    private fun markAheadScheduled(path: String, index: Long): Boolean {
        var fresh = false
        lastAheadIndex.compute(path) { _, previous ->
            if (previous != null && previous == index) {
                previous
            } else {
                fresh = true
                index
            }
        }
        return fresh
    }

    /** 段下载用的作用域（[readAheadScope] 没给就临时起一个，保证单飞 map 生命周期正确）。 */
    private fun scopeForDownload(): CoroutineScope = readAheadScope ?: fallbackScope

    /**
     * 段下载"落盘循环"的调度器（**故意不是 [io]，也故意不是裸 [Dispatchers.IO]**）。
     *
     * **为什么不是 [io]**：落盘要横跨整段传输，占着一格存储许可就等于把整段时间里网络读的并发
     * 挤掉一格（许可语义见 [StorageDispatchers] 的类注释："一段不中断的执行片"，
     * 不是"下载吃 2 个许可"）。
     *
     * **为什么还要在 IO 池里再收一层 [DOWNLOAD_PARALLELISM]**（2026-11-21 第三轮审计 P1）：
     * 裸 `Dispatchers.IO` 是**进程级共享的 64 线程预算**——DataStore / Room / 缩略图 / 各后端的
     * 阻塞调用全挤在里面。段下载是**长阻塞**（公网段几十秒占住一个线程），20 段预读一起上就能
     * 吃掉 64 格里的三分之一，把别人的短任务挤到排队（表现为界面/设置页偶发卡顿）。
     * `limitedParallelism(10)` 给它自己一道子闸门：最多 10 个落盘同时跑，其余在 IO 池里排队
     * （排队是挂起，不占线程）。10 = 历史 IO_CORE_THREADS 的一半，够 20 段预读轮转。
     */
    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(DOWNLOAD_PARALLELISM)

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
        // 先按本轮访问序号排（访问过的段越大越新），没访问记录的老缓存按文件 mtime 排在更前（先淘汰）。
        val files = segmentFiles().sortedWith(
            compareBy<File> { lastAccessOf(it) }.thenBy { it.lastModified() },
        )
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
            if (file.delete()) {
                total -= length
                forgetAccess(file)
            }
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
                touch(file)
                statHits.incrementAndGet()
                return readFrom(file, inSegment, chunk, buf, off)
            }
            val job = ensureJob(path, index)
            if (job == null) {
                touch(file)
                statHits.incrementAndGet()
                return readFrom(file, inSegment, chunk, buf, off)
            }
            statMisses.incrementAndGet()
            // 挂起等字节（同上：等待不占 io 许可）
            job.awaitBytes(inSegment.toLong() + chunk)
            job.failureOrNull()?.let { throw it }
            val readable = if (file.isFile && file.length() > 0L) file else job.tempFile
            if (readable === file) touch(file)
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

        /**
         * 段下载落盘的最大并发（[downloadDispatcher] 的子闸门）。
         *
         * 与 [StorageDispatchers.IO_CORE_THREADS] 同量级：段下载是长阻塞，不能让它把
         * 进程级 64 线程的 IO 预算吃完（见 [downloadDispatcher] 的注释）。
         */
        const val DOWNLOAD_PARALLELISM: Int = 10

        /** 等待"并发槽位"的轮询间隔（毫秒）；块下载是秒级，这点开销可忽略。 */
        private const val CHUNK_SLOT_POLL_MS = 10L
    }
}

// 存储层线程池已移到同包的 StorageDispatchers.kt（2026-11-21：恢复有上限，
// 真机 SIGABRT 见那里的注释）。本文件只保留"用哪个调度器"的引用。
