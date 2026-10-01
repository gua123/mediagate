package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 两级缩略图缓存 + 失败负缓存（R5 缩略图，plan 4.4 / 4.10）。
 *
 * 结构对齐 plan 4.4「两级缓存（内存 LruCache + 磁盘 webp，默认 512 MB LRU）+ 失败负缓存」：
 * - **内存层**：自己实现的 LRU（[LinkedHashMap] accessOrder=true + 计数上限），命中即刷新最近使用；
 *   刻意不用 `android.util.LruCache`，这样本文件与它的单测都能在纯 JVM 上跑；
 * - **磁盘层**：[ThumbnailKey.relativePath] 决定的
 *   `<cacheDir>/<kind>/<hash前2位>/<hash>.<扩展名>`（视频帧/音频封面是 `.webp`，
 *   图片缩略图按实际编码格式，见 [ThumbnailKey.extension]），
 *   写入后按 lastModified（命中会刷新）做 LRU 淘汰，直到总量回到 [diskCapacityBytes] 以内；
 * - **负缓存**：记录「这个 key 抽帧失败了」与失败时间戳，[negativeTtlMs] 内直接判定失败，
 *   避免对同一个坏文件反复发起远端读（plan 4.4「失败负缓存」）。
 *
 * 并发与原子性：
 * - 写盘一律「先写唯一 `.tmp-*` 再 rename」，读者永远只会看到「没有文件」或「完整文件」，
 *   同一 key 被多个协程同时写入也不会产生半截文件；
 * - 内存层与磁盘淘汰各自加锁，可安全地在多个协程/线程间共享。
 *
 * 线程模型：[get]/[put]/[sizeBytes]/[clear] 是挂起函数，内部切到 [io]（默认
 * [Dispatchers.IO]），不会阻塞调用方线程；负缓存的读写是纯内存操作，保持同步 API。
 *
 * 本类不依赖任何 Android API（只用 java.io / java.util），可直接 JVM 单测。
 *
 * @param rootDir 缓存根目录（App 里传 `context.cacheDir/thumbnails`）；不存在会自动创建。
 * @param memoryCapacity 内存层容量，单位**条数**（plan 未规定，默认 256 条；<=0 表示禁用内存层）。
 * @param diskCapacityBytes 磁盘层容量上限，默认 512 MB（plan 4.10）。
 * @param negativeTtlMs 负缓存有效期，默认 10 分钟（<=0 表示不启用负缓存）。
 * @param clock 时间源，默认 [System.currentTimeMillis]；单测注入假时钟以验证 TTL。
 * @param io 磁盘 IO 调度器。
 */
class ThumbnailCache(
    val rootDir: File,
    val memoryCapacity: Int = DEFAULT_MEMORY_CAPACITY,
    val diskCapacityBytes: Long = DEFAULT_DISK_CAPACITY_BYTES,
    val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 内存层：accessOrder=true 的 LinkedHashMap 本身就是「命中即刷新」的 LRU。 */
    private val memory = LinkedHashMap<ThumbnailKey, ByteArray>(16, 0.75f, true)
    private val memoryLock = Any()

    /** 负缓存：key → 失败时间戳（毫秒）。 */
    private val negative = ConcurrentHashMap<ThumbnailKey, Long>()

    /** 磁盘层的淘汰/清理串行化，避免两个协程同时算容量互相踩。 */
    private val diskLock = ReentrantLock()

    private val tmpSeq = AtomicLong(0)

    /**
     * 依次查内存层与磁盘层。
     *
     * 磁盘命中会顺带刷新 lastModified（磁盘 LRU 的「最近使用」）并回填内存层。
     *
     * @return 缩略图字节；两层都没有返回 null。
     */
    suspend fun get(key: ThumbnailKey): ByteArray? = withContext(io) {
        memoryGet(key) ?: diskGet(key)
    }

    /**
     * 写入两级缓存并触发磁盘淘汰。
     *
     * 磁盘写失败（无空间、目录不可写）只记日志不抛异常：缩略图是**可再生的缓存**，
     * 拿不到缓存不应该让调用方的图片流断掉。内存层仍会写入。
     */
    suspend fun put(key: ThumbnailKey, bytes: ByteArray) = withContext(io) {
        memoryPut(key, bytes)
        diskPut(key, bytes)
        evictToCapacityInternal()
    }

    /** 当前磁盘缓存占用（字节），统计所有已落盘的缓存条目（不限扩展名），不含写入中的 .tmp 文件。 */
    suspend fun sizeBytes(): Long = withContext(io) { diskFiles().sumOf { it.length() } }

    /** 按 [diskCapacityBytes] 淘汰最久未使用的条目（写入后自动调用；也可由清理任务手动触发）。 */
    suspend fun evictToCapacity() = withContext(io) { evictToCapacityInternal() }

    /** 清空两级缓存与负缓存（设置页「清理缩略图缓存」用）。 */
    suspend fun clear() = withContext(io) {
        synchronized(memoryLock) { memory.clear() }
        negative.clear()
        diskLock.withLock { rootDir.deleteRecursively() }
    }

    /** [key] 对应的磁盘文件（无论是否存在），供诊断与图片路径（Coil Fetcher）复用。 */
    fun fileFor(key: ThumbnailKey): File = File(rootDir, key.relativePath)

    /**
     * [key] 是否处于失败负缓存有效期内（TTL 到了会顺手清掉过期项）。
     *
     * 调用方（[ThumbnailRepository]）据此在 TTL 内直接返回失败，不再打远端。
     */
    fun isNegative(key: ThumbnailKey): Boolean {
        if (negativeTtlMs <= 0) return false
        val failedAt = negative[key] ?: return false
        if (clock() - failedAt >= negativeTtlMs) {
            negative.remove(key)
            return false
        }
        return true
    }

    /** 记一次失败（抽帧/读取失败时调用），TTL 内该 key 直接判失败。 */
    fun markFailed(key: ThumbnailKey) {
        if (negativeTtlMs <= 0) return
        pruneNegative()
        negative[key] = clock()
    }

    /** 手动清除某个 key 的失败记录（例如用户点了「重试」）。 */
    fun clearNegative(key: ThumbnailKey) {
        negative.remove(key)
    }

    /** 内存层当前条数（诊断/单测用）。 */
    fun memorySize(): Int = synchronized(memoryLock) { memory.size }

    /** 负缓存当前条数（诊断用）。 */
    fun negativeSize(): Int = negative.size

    // ---------------------------------------------------------------- 内存层

    private fun memoryGet(key: ThumbnailKey): ByteArray? = synchronized(memoryLock) { memory[key] }

    private fun memoryPut(key: ThumbnailKey, bytes: ByteArray) {
        if (memoryCapacity <= 0) return
        synchronized(memoryLock) {
            memory[key] = bytes
            // accessOrder 迭代顺序 = 最久未用 → 最近使用，从头淘汰即可
            val iterator = memory.entries.iterator()
            while (memory.size > memoryCapacity && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    // ---------------------------------------------------------------- 磁盘层

    private fun diskGet(key: ThumbnailKey): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            AppLog.w(TAG, "读取缩略图缓存失败：${file.path}", e)
            return null
        }
        if (bytes.isEmpty()) {
            // 空文件只可能来自异常中断，直接丢掉让下次重新抽帧
            file.delete()
            return null
        }
        file.setLastModified(clock())
        memoryPut(key, bytes)
        return bytes
    }

    private fun diskPut(key: ThumbnailKey, bytes: ByteArray) {
        val target = fileFor(key)
        val parent = target.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) {
            AppLog.w(TAG, "创建缩略图缓存目录失败：${parent.path}")
            return
        }
        // 唯一临时名，保证同一 key 的并发写互不覆盖
        val tmp = File(parent, "${target.name}$TMP_MARKER${tmpSeq.incrementAndGet()}-${UUID.randomUUID()}")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // 同目录 rename 正常都成功；极端情况（Windows/异常文件系统）退化为先删再改名
                target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
            }
            target.setLastModified(clock())
        } catch (e: IOException) {
            AppLog.w(TAG, "写入缩略图缓存失败：${target.path}", e)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 遍历缓存目录下所有已落盘的条目（`.tmp-` 写入中的文件不算容量）。
     *
     * M1-F 起缓存不再只有 `.webp`：图片缩略图按实际编码格式落 `.jpg` / `.png` / `.webp`
     * （见 [ThumbnailKey.extension]），所以这里按「非临时文件」判定，而不是按扩展名白名单——
     * 否则非 webp 的条目会永远不参与容量统计与淘汰。
     */
    private fun diskFiles(): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) walk(child)
                else if (!child.name.contains(TMP_MARKER)) out += child
            }
        }
        walk(rootDir)
        return out
    }

    private fun evictToCapacityInternal() {
        if (diskCapacityBytes <= 0) return
        diskLock.withLock {
            val files = diskFiles()
            var total = files.sumOf { it.length() }
            if (total <= diskCapacityBytes) return
            // lastModified 升序 = 最久未使用优先；同毫秒时用文件名兜底保证顺序稳定
            val victims = files.sortedWith(compareBy({ it.lastModified() }, { it.name }))
            for (victim in victims) {
                if (total <= diskCapacityBytes) break
                val length = victim.length()
                if (victim.delete()) {
                    total -= length
                    AppLog.d(TAG, "缩略图缓存淘汰：${victim.name}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 负缓存

    /** 负缓存上限保护：先清过期项，仍然超限就按时间戳丢最旧的。 */
    private fun pruneNegative() {
        if (negative.size < MAX_NEGATIVE_ENTRIES) return
        val now = clock()
        negative.entries.removeAll { now - it.value >= negativeTtlMs }
        if (negative.size >= MAX_NEGATIVE_ENTRIES) {
            val overflow = negative.size - MAX_NEGATIVE_ENTRIES + 1
            negative.entries.sortedBy { it.value }.take(overflow).forEach { negative.remove(it.key) }
        }
    }

    companion object {

        private const val TAG = "thumbnail-cache"

        /** 内存层默认容量（条数）。 */
        const val DEFAULT_MEMORY_CAPACITY = 256

        /** 磁盘层默认容量：512 MB（plan 4.10）。 */
        const val DEFAULT_DISK_CAPACITY_BYTES = 512L * 1024 * 1024

        /** 负缓存默认有效期：10 分钟。 */
        const val DEFAULT_NEGATIVE_TTL_MS = 10 * 60 * 1000L

        /** 负缓存条目上限，防止长时间运行后无限增长。 */
        private const val MAX_NEGATIVE_ENTRIES = 4096

        /** 写入中临时文件的名字标记（[diskFiles] 靠它把半成品排除在容量统计之外）。 */
        private const val TMP_MARKER = ".tmp-"
    }
}
