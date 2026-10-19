package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.core.common.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一条缓存任务的状态。 */
enum class CacheTaskStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * 一条**预约缓存**任务（2026-10-15 用户要求「手动批量缓存」→ 2026-10-20 改成只温开头）。
 *
 * 与"打开即预取"的区别：那是顺手的（首段/尾段/落点），这是**用户点名、排队、看得见进度**的；
 * 但两者目标一致——把**开头那一段**先拿到本地，让初次打开快起来，而不是整份囤下来。
 * 目标字节数由 [CacheHeadPlan] 算（大文件 12 MB，小文件整份）。
 */
data class CacheTask(
    val path: String,
    val name: String,
    val status: CacheTaskStatus = CacheTaskStatus.QUEUED,
    /** 文件总大小（界面显示用）；未知（stat 没拿到）时为 null。 */
    val totalBytes: Long? = null,
    /** 这条任务要缓存的字节数（**只温开头**；小文件＝整份）；还没开始算时为 null。 */
    val targetBytes: Long? = null,
    /** 已下载字节（含之前已经缓存过的段）。 */
    val cachedBytes: Long = 0L,
    val error: String? = null,
) {

    /** 进度 0..1（相对"要缓存的那一段"，不是整个文件）；目标未知时为 null（不编百分比）。 */
    val progress: Float?
        get() {
            val target = targetBytes ?: return null
            if (target <= 0L) return null
            return (cachedBytes.toDouble() / target.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    val finished: Boolean get() = status == CacheTaskStatus.DONE
}

/** 队列快照（界面用）。 */
data class CacheQueueState(
    val tasks: List<CacheTask> = emptyList(),
    val paused: Boolean = false,
) {

    /** 还没结束的任务（排队 + 进行中）。 */
    val pending: Int get() = tasks.count { it.status == CacheTaskStatus.QUEUED || it.status == CacheTaskStatus.RUNNING }

    val running: CacheTask? get() = tasks.firstOrNull { it.status == CacheTaskStatus.RUNNING }

    val done: Int get() = tasks.count { it.status == CacheTaskStatus.DONE }

    val failed: Int get() = tasks.count { it.status == CacheTaskStatus.FAILED }

    /** 有没有值得在界面上显示的任务（全空时缓存明细页不显示这一块）。 */
    val hasAnything: Boolean get() = tasks.isNotEmpty()
}

/**
 * **手动缓存队列**（单并发、可暂停、可取消、**播放时自动让路**）。
 *
 * 为什么单并发：用户是"预约下载"，不该抢正在播放的带宽；段落级单飞又保证它和播放要的段不会重复下载。
 * 让路的实现：播放活跃时**不开始下一段**（已经下到一半的段会写完，不会撕裂），等播放停了继续。
 *
 * 线程：状态变更都在 [scope] 上；[enqueue] 可任意线程调用。
 */
class CacheQueueController(
    private val scope: CoroutineScope,
    /** 段大小（跟着用户的网络设置走；每次取当前值）。 */
    private val segmentBytes: () -> Long,
    /** 文件总大小（拿不到返回 null）。 */
    private val knownSize: suspend (String) -> Long?,
    /** 已缓存字节（用于进度与"跳过已缓存部分"）。 */
    private val cachedBytesOf: suspend (String) -> Long,
    /** 下一段（失败请抛异常，队列会记下来并继续下一个任务）。 */
    private val downloadSegment: suspend (path: String, offset: Long, length: Long) -> Unit,
    /** 是否正在播放（播放时让路：不下新段）。 */
    private val playbackActive: StateFlow<Boolean>,
) {

    private val _state = MutableStateFlow(CacheQueueState())

    val state: StateFlow<CacheQueueState> = _state.asStateFlow()

    private val lock = Mutex()

    private var worker: Job? = null

    /**
     * 加入一批文件（**批量**：浏览页长按文件夹一次可能几十个）。
     *
     * 去重规则：同一路径已经在队列里（排队或正在下）就不再加入；已经下完的会重新排队
     * （用户可能想"再确认一次"它还在缓存里——重新跑一遍只会瞬间命中已缓存段）。
     */
    fun enqueue(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        _state.update { current ->
            val known = current.tasks.filter { it.status == CacheTaskStatus.QUEUED || it.status == CacheTaskStatus.RUNNING }
                .map { it.path }.toSet()
            val added = entries.filter { it.first !in known }.map { (path, name) -> CacheTask(path = path, name = name) }
            current.copy(tasks = current.tasks + added)
        }
        startWorkerIfNeeded()
    }

    /** 暂停（正在下的那段会写完，之后不再开始新段）。 */
    fun pause() {
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        _state.update { it.copy(paused = false) }
        startWorkerIfNeeded()
    }

    /** 取消一条任务（排队中的直接标记；正在下的会在当前段结束后停下）。 */
    fun cancel(path: String) {
        _state.update { current ->
            current.copy(
                tasks = current.tasks.map { task ->
                    if (task.path == path && !task.finished) task.copy(status = CacheTaskStatus.CANCELLED) else task
                },
            )
        }
    }

    /** 清掉已结束的任务条目（界面按钮）。 */
    fun clearFinished() {
        _state.update { current ->
            current.copy(
                tasks = current.tasks.filter {
                    it.status == CacheTaskStatus.QUEUED || it.status == CacheTaskStatus.RUNNING
                },
            )
        }
    }

    private fun startWorkerIfNeeded() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _state.value.tasks.firstOrNull { it.status == CacheTaskStatus.QUEUED } ?: break
                if (_state.value.paused) break
                runTask(next)
            }
            worker = null
        }
    }

    private suspend fun runTask(task: CacheTask) {
        update(task.path) { it.copy(status = CacheTaskStatus.RUNNING, error = null) }
        val total = runCatching { knownSize(task.path) }.getOrNull()
        val target = CacheHeadPlan.targetBytes(total)
        val already = runCatching { cachedBytesOf(task.path) }.getOrDefault(0L)
        update(task.path) { it.copy(totalBytes = total, targetBytes = target, cachedBytes = already) }
        if (already >= target) {
            // 开头那几段已经在缓存里（可能来自播放/预取）：直接算完成，不重复下载
            update(task.path) { it.copy(status = CacheTaskStatus.DONE) }
            return
        }
        val segment = segmentBytes().coerceAtLeast(1L)
        var offset = already
        while (offset < target) {
            val current = _state.value.tasks.firstOrNull { it.path == task.path }
            if (current == null || current.status == CacheTaskStatus.CANCELLED) return
            if (_state.value.paused) {
                update(task.path) { it.copy(status = CacheTaskStatus.QUEUED) }
                return
            }
            // **让路**：播放中不下新段（等播放停下来；每 2 秒看一眼）
            if (playbackActive.value) {
                delay(YIELD_POLL_MS)
                // 让路期间被暂停 ⇒ 立刻收工（别在后台空转；任务退回"排队中"，继续时接着下）
                if (_state.value.paused) {
                    update(task.path) { it.copy(status = CacheTaskStatus.QUEUED) }
                    return
                }
                continue
            }
            val length = minOf(segment, target - offset)
            val ok = runCatching { downloadSegment(task.path, offset, length) }
            if (ok.isFailure) {
                val error = ok.exceptionOrNull()
                AppLog.w(TAG, "缓存开头失败：" + task.path, error)
                update(task.path) {
                    it.copy(
                        status = CacheTaskStatus.FAILED,
                        error = error?.message ?: error?.javaClass?.simpleName ?: "未知原因",
                    )
                }
                return
            }
            offset += length
            val done = runCatching { cachedBytesOf(task.path) }.getOrDefault(offset)
            update(task.path) { it.copy(cachedBytes = done) }
        }
        update(task.path) { it.copy(status = CacheTaskStatus.DONE) }
    }

    private fun update(path: String, transform: (CacheTask) -> CacheTask) {
        _state.update { current ->
            current.copy(tasks = current.tasks.map { if (it.path == path) transform(it) else it })
        }
    }

    companion object {

        private const val TAG = "cache-queue"

        /** 播放中让路的轮询间隔。 */
        const val YIELD_POLL_MS = 2_000L
    }
}
