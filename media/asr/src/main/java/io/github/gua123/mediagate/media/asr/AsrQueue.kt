package io.github.gua123.mediagate.media.asr

import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 单个字幕任务的阶段（**M7-B / R19**：排队 → 识别中 → 写入中 → 成功/失败/跳过/已中断）。
 *
 * [INTERRUPTED] 专门为「进程被杀后重启」准备：它**带着已识别进度**，续跑时从断点继续，
 * 而不是从头再来（plan 4.12「未完成任务标为已中断、可续跑」）。
 */
enum class AsrItemState(val zhText: String) {

    /** 排队中。 */
    QUEUED("排队中"),

    /** 识别中（whisper 正在跑）。 */
    RUNNING("识别中"),

    /** 写入中（合并时间轴 + 写字幕文件）。 */
    WRITING("写入中"),

    /** 成功（字幕已与视频同名同目录，或按规则落到 App 私有目录）。 */
    SUCCEEDED("成功"),

    /** 失败（原因见 [AsrItem.failure]）。 */
    FAILED("失败"),

    /** 跳过（已有字幕 / 用户取消 / 非视频）。 */
    SKIPPED("跳过"),

    /** 已中断、可续跑（进程被杀）。 */
    INTERRUPTED("已中断，可续跑"),

    /** 用户取消。 */
    CANCELLED("已取消"),
    ;

    /** 是否正在占用识别资源。 */
    val isActive: Boolean get() = this == RUNNING || this == WRITING

    /** 是否已经落定（不会再自己变）。 */
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED

    /** 是否还能被重试。 */
    val isRetryable: Boolean get() = this == FAILED || this == CANCELLED || this == INTERRUPTED
}

/** 队列整体状态（**R19**：暂停 / 继续 / 取消）。 */
enum class AsrQueueState(val zhText: String) {

    /** 空转（没有任务或全跑完了）。 */
    IDLE("空闲"),

    /** 正在跑。 */
    RUNNING("进行中"),

    /** 已暂停：不再启动新任务，正在跑的那条在下个窗口边界让位。 */
    PAUSED("已暂停"),

    /** 已取消/已停止。 */
    STOPPED("已停止"),
}

/**
 * 失败原因分类（**R19 要求「失败项给原因」**：无写权限、无音轨、模型未下载、网络中断、解码失败）。
 *
 * [of] 把异常翻成分类，[ofMessage] 兜住只有一句话没有异常的场景。
 * 分类是**纯函数**，所以「什么异常算哪一类」能在 JVM 单测里穷举。
 */
enum class AsrFailureKind(val zhText: String) {

    /** 无写权限（远端只读 / SAF 未授权）——会落 App 私有目录并提示，不算彻底失败。 */
    NO_WRITE_PERMISSION("无写入权限"),

    /** 文件里没有音轨。 */
    NO_AUDIO_TRACK("文件里没有音轨"),

    /** 模型还没下载。 */
    MODEL_MISSING("模型未下载"),

    /** 网络中断（远端取音/写回）。 */
    NETWORK("网络中断"),

    /** 音频解码失败（FFmpeg 解不出 PCM）。 */
    DECODE_FAILED("音频解码失败"),

    /** 识别引擎不可用（.so 没加载 / 模型损坏）。 */
    ENGINE_UNAVAILABLE("识别引擎不可用"),

    /** 识别过程本身失败。 */
    NATIVE_ERROR("识别失败"),

    /** 文件不存在（列出后被删/被移走）。 */
    NOT_FOUND("文件不存在"),

    /** 其它。 */
    UNKNOWN("未知错误"),
    ;

    companion object {

        /** 异常 → 分类（**纯函数**）。 */
        fun of(throwable: Throwable?): AsrFailureKind = when (throwable) {
            null -> UNKNOWN
            is StorageException.AccessDenied -> NO_WRITE_PERMISSION
            is StorageException.Network, is UnknownHostException, is SocketTimeoutException -> NETWORK
            is StorageException.NotFound, is FileNotFoundException -> NOT_FOUND
            is WhisperException -> when (throwable.code) {
                WhisperNative.ERR_UNAVAILABLE, WhisperNative.ERR_INIT_FAILED, WhisperNative.ERR_BAD_MODEL ->
                    ENGINE_UNAVAILABLE
                else -> NATIVE_ERROR
            }
            is IOException -> NETWORK
            else -> ofMessage(throwable.message)
        }

        /** 一句话 → 分类（**纯函数**）：异常链里没有类型信息时的兜底。 */
        fun ofMessage(message: String?): AsrFailureKind {
            val text = message.orEmpty()
            if (text.isEmpty()) return UNKNOWN
            return when {
                text.contains("音轨") || text.contains("没有音频") || text.contains("no audio") -> NO_AUDIO_TRACK
                text.contains("模型") && (text.contains("未下载") || text.contains("不存在")) -> MODEL_MISSING
                text.contains("权限") || text.contains("只读") || text.contains("read-only") -> NO_WRITE_PERMISSION
                text.contains("网络") || text.contains("超时") || text.contains("连接") -> NETWORK
                text.contains("解码") || text.contains("decode") -> DECODE_FAILED
                text.contains("引擎") || text.contains("native") -> ENGINE_UNAVAILABLE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 队列里的一条字幕任务（**M7-B / R19**，对应 plan 第 7 章的 asr_task 表）。
 *
 * @property id 任务 id（入库后由数据库给；纯逻辑测试里自己定）。
 * @property path 视频在后端内的路径。
 * @property name 文件名（展示用）。
 * @property durationMs 音轨时长；0 = 未知（进度就只能按"已识别时长"显示）。
 * @property progress 逐项进度。
 * @property outputPath 成功后的字幕落点（可能是 App 私有目录的绝对路径）。
 * @property failure 失败分类；[AsrItemState.FAILED] 时非空。
 * @property errorMessage 原始错误文本（诊断用，界面只显示 [failure] 的中文）。
 * @property skipReason 跳过原因（已有字幕 / 用户取消…）。
 * @property retryCount 重试次数（一键重试会累加）。
 * @property batchId 所属批次（R19 的 asr_batch）；null = 散装任务。
 */
data class AsrItem(
    val id: Long,
    val path: String,
    val name: String,
    val durationMs: Long = 0L,
    val state: AsrItemState = AsrItemState.QUEUED,
    val progress: AsrProgress = AsrProgress.EMPTY,
    val outputPath: String? = null,
    val failure: AsrFailureKind? = null,
    val errorMessage: String? = null,
    val skipReason: String? = null,
    val retryCount: Int = 0,
    val batchId: Long? = null,
) {
    /** 给总进度贡献的份额（0..1）：终态算 1，可续跑的算已识别进度，排队算 0。 */
    internal val progressShare: Double
        get() = when (state) {
            AsrItemState.SUCCEEDED, AsrItemState.SKIPPED, AsrItemState.FAILED, AsrItemState.CANCELLED -> 1.0
            AsrItemState.RUNNING, AsrItemState.WRITING, AsrItemState.INTERRUPTED -> progress.fraction.toDouble()
            AsrItemState.QUEUED -> 0.0
        }
}

/** 结果汇总（**R19 的「成功 N / 失败 M / 跳过 K」**）。 */
data class AsrSummary(
    val total: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val cancelled: Int = 0,
    val interrupted: Int = 0,
    val pending: Int = 0,
) {
    /** 是否还有没跑完的（含可续跑）。 */
    val hasUnfinished: Boolean get() = pending > 0 || interrupted > 0
}

/**
 * 任务中心的整页状态（**R19**，纯数据；界面直接渲染它）。
 *
 * [items] 的顺序就是**队列顺序**，重排只动这个列表。
 */
data class AsrQueueSnapshot(
    val state: AsrQueueState = AsrQueueState.IDLE,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val requestedThreads: Int = WhisperNative.DEFAULT_THREADS,
    val items: List<AsrItem> = emptyList(),
) {

    /** 正在跑的任务（并发 1 时最多一条）。 */
    val activeItems: List<AsrItem> get() = items.filter { it.state.isActive }

    /** 当前任务（通知栏「当前文件」用）。 */
    val currentItem: AsrItem? get() = activeItems.firstOrNull()

    /** 总进度 0..1（所有任务的平均份额）。 */
    val totalProgress: Float
        get() = if (items.isEmpty()) 0f else (items.sumOf { it.progressShare } / items.size).toFloat()

    /** 总进度百分比（通知栏直接用）。 */
    val totalPercent: Int get() = (totalProgress * 100f).toInt().coerceIn(0, 100)

    /** 结果汇总。 */
    val summary: AsrSummary
        get() = AsrSummary(
            total = items.size,
            succeeded = items.count { it.state == AsrItemState.SUCCEEDED },
            failed = items.count { it.state == AsrItemState.FAILED },
            skipped = items.count { it.state == AsrItemState.SKIPPED },
            cancelled = items.count { it.state == AsrItemState.CANCELLED },
            interrupted = items.count { it.state == AsrItemState.INTERRUPTED },
            pending = items.count { it.state == AsrItemState.QUEUED },
        )

    /** 是否全部落定（没有排队 / 在跑 / 可续跑的）。 */
    val isFinished: Boolean
        get() = items.isNotEmpty() && items.none {
            it.state == AsrItemState.QUEUED || it.state.isActive || it.state == AsrItemState.INTERRUPTED
        }

    /** 还有没有活干。 */
    val isEmpty: Boolean get() = items.isEmpty()

    /** 按 id 找任务。 */
    fun item(id: Long): AsrItem? = items.firstOrNull { it.id == id }

    /** 还能再启动几条（并发额度 - 正在跑的；暂停/停止时恒为 0）。 */
    fun canStartMore(): Int {
        if (state == AsrQueueState.PAUSED || state == AsrQueueState.STOPPED) return 0
        val free = concurrency - activeItems.size
        if (free <= 0) return 0
        return minOf(free, items.count { it.state == AsrItemState.QUEUED })
    }

    companion object {

        /** plan 4.12：默认串行。 */
        const val DEFAULT_CONCURRENCY = 1

        /** plan 4.12：最多 2（再多会和播放抢 CPU）。 */
        const val MAX_CONCURRENCY = 2
    }
}

/** [AsrQueue.startNext] 的结果：新状态 + 本次真正启动的任务。 */
data class AsrStart(
    val queue: AsrQueueSnapshot,
    val started: List<AsrItem>,
)

/**
 * 批量字幕队列的**纯状态机**（**M7-B / R19**）。
 *
 * 为什么把状态机单独抽出来（不在 Service 里直接改状态）：
 * - 所有分支（暂停/继续/取消/重排/单条重试/一键重试/进程被杀恢复）都能在 JVM 上穷举，
 *   真机上只剩「谁在跑、跑完调哪个函数」；
 * - 界面（:feature:tasks）只读 [AsrQueueSnapshot]，不持有可变状态；
 * - 状态与 plan 第 7 章的 asr_task 表一一对应，落库/恢复是它的直接投影。
 *
 * 约定：所有函数都是**纯函数**（输入快照 → 输出新快照），不改原对象。
 */
object AsrQueue {

    /** 构造初始快照（进程重启后从库里恢复也用这个）。 */
    fun of(
        items: List<AsrItem> = emptyList(),
        concurrency: Int = AsrQueueSnapshot.DEFAULT_CONCURRENCY,
        requestedThreads: Int = WhisperNative.DEFAULT_THREADS,
        state: AsrQueueState = AsrQueueState.IDLE,
    ): AsrQueueSnapshot = AsrQueueSnapshot(
        state = state,
        concurrency = clampConcurrency(concurrency),
        requestedThreads = WhisperNative.clampThreads(requestedThreads),
        items = items,
    )

    /** 并发数夹到 1..2（plan 4.12）。 */
    fun clampConcurrency(value: Int): Int = value.coerceIn(1, AsrQueueSnapshot.MAX_CONCURRENCY)

    /** 入队（追加到队尾，保持既有任务的位置）。 */
    fun enqueue(queue: AsrQueueSnapshot, items: List<AsrItem>): AsrQueueSnapshot {
        if (items.isEmpty()) return queue
        val merged = queue.items + items.map { it.copy(state = AsrItemState.QUEUED) }
        return queue.copy(items = merged, state = if (queue.state == AsrQueueState.STOPPED) AsrQueueState.IDLE else queue.state)
    }

    /** 把已在库里的任务整体装进队列（恢复用，不改状态）。 */
    fun restore(queue: AsrQueueSnapshot, items: List<AsrItem>): AsrQueueSnapshot = queue.copy(items = items)

    /**
     * 启动接下来的任务（按队列顺序补满并发额度）。
     *
     * 暂停 / 停止状态、没有空位、没有排队任务时原样返回（[AsrStart.started] 为空）。
     */
    fun startNext(queue: AsrQueueSnapshot): AsrStart {
        if (queue.state == AsrQueueState.PAUSED || queue.state == AsrQueueState.STOPPED) {
            return AsrStart(queue, emptyList())
        }
        var free = queue.canStartMore()
        if (free <= 0) return AsrStart(queue, emptyList())
        val started = ArrayList<AsrItem>(free)
        val items = queue.items.map { item ->
            if (free > 0 && item.state == AsrItemState.QUEUED) {
                free--
                val running = item.copy(state = AsrItemState.RUNNING, failure = null, errorMessage = null)
                started += running
                running
            } else {
                item
            }
        }
        if (started.isEmpty()) return AsrStart(queue, emptyList())
        return AsrStart(queue.copy(items = items, state = AsrQueueState.RUNNING), started)
    }

    /** 更新逐项进度（识别中每识别完一个窗口调一次）。 */
    fun onProgress(queue: AsrQueueSnapshot, id: Long, recognizedMs: Long, totalMs: Long): AsrQueueSnapshot =
        update(queue, id) { item ->
            item.copy(progress = AsrPipeline.progressOf(recognizedMs, totalMs), durationMs = if (totalMs > 0) totalMs else item.durationMs)
        }

    /** 进入写入阶段（合并时间轴 + 写字幕文件）。 */
    fun markWriting(queue: AsrQueueSnapshot, id: Long): AsrQueueSnapshot =
        update(queue, id) { it.copy(state = AsrItemState.WRITING, progress = it.progress.copy()) }

    /** 成功。 */
    fun succeed(queue: AsrQueueSnapshot, id: Long, outputPath: String?): AsrQueueSnapshot =
        update(queue, id) { it.copy(state = AsrItemState.SUCCEEDED, outputPath = outputPath, failure = null, errorMessage = null) }

    /** 失败（带分类与原始信息）。 */
    fun fail(queue: AsrQueueSnapshot, id: Long, kind: AsrFailureKind, message: String?): AsrQueueSnapshot =
        update(queue, id) { it.copy(state = AsrItemState.FAILED, failure = kind, errorMessage = message) }

    /** 失败（从异常自动分类）。 */
    fun fail(queue: AsrQueueSnapshot, id: Long, error: Throwable?): AsrQueueSnapshot =
        fail(queue, id, AsrFailureKind.of(error), error?.message)

    /** 跳过（已有字幕等）。 */
    fun skip(queue: AsrQueueSnapshot, id: Long, reason: String): AsrQueueSnapshot =
        update(queue, id) { it.copy(state = AsrItemState.SKIPPED, skipReason = reason) }

    /** 暂停：不再启动新任务（正在跑的那条由执行者在下个窗口边界让位）。 */
    fun pause(queue: AsrQueueSnapshot): AsrQueueSnapshot =
        if (queue.state == AsrQueueState.STOPPED) queue else queue.copy(state = AsrQueueState.PAUSED)

    /** 继续：恢复成「有活就 RUNNING」的状态，由调用方接着 [startNext]。 */
    fun resume(queue: AsrQueueSnapshot): AsrQueueSnapshot = when (queue.state) {
        // 「取消全部」之后队列是 STOPPED：以前这里原样返回，而入队按钮也被 STOPPED 拦着，
        // 用户就**彻底没有出路**（2026-10-03 真机截图：勾了 1 项但「加入队列并开始」是灰的）。
        // 现在把它当"空转"复活——被取消的那些仍是终态、不会被跑；用户随后可以重新入队或重试失败项。
        AsrQueueState.STOPPED -> queue.copy(
            state = if (queue.activeItems.isEmpty()) AsrQueueState.IDLE else AsrQueueState.RUNNING,
        )

        AsrQueueState.PAUSED -> queue.copy(state = if (queue.activeItems.isEmpty()) AsrQueueState.IDLE else AsrQueueState.RUNNING)
        else -> queue
    }

    /** 取消单条（排队中/可续跑的直接作废；跑着的由执行者收到取消信号后置 CANCELLED）。 */
    fun cancel(queue: AsrQueueSnapshot, id: Long): AsrQueueSnapshot =
        update(queue, id) { item ->
            if (item.state.isTerminal) item else item.copy(state = AsrItemState.CANCELLED, failure = null, errorMessage = null)
        }

    /** 取消全部（队列进入 STOPPED）。 */
    fun cancelAll(queue: AsrQueueSnapshot): AsrQueueSnapshot = queue.copy(
        items = queue.items.map { if (it.state.isTerminal) it else it.copy(state = AsrItemState.CANCELLED) },
        state = AsrQueueState.STOPPED,
    )

    /** 上移一位（与上一个**排队中**的任务交换位置；已经在最前就原样返回）。 */
    fun moveUp(queue: AsrQueueSnapshot, id: Long): AsrQueueSnapshot = move(queue, id, -1)

    /** 下移一位（与下一个**排队中**的任务交换位置）。 */
    fun moveDown(queue: AsrQueueSnapshot, id: Long): AsrQueueSnapshot = move(queue, id, +1)

    /**
     * 移动到指定位置（**R19 的「调整顺序」**）。
     *
     * 目标位置是**排队任务序列里的下标**：越界会被夹到两端；非排队中的任务不动。
     */
    fun moveTo(queue: AsrQueueSnapshot, id: Long, targetIndex: Int): AsrQueueSnapshot {
        val item = queue.item(id) ?: return queue
        if (item.state != AsrItemState.QUEUED) return queue
        val pending = queue.items.filter { it.state == AsrItemState.QUEUED }
        val from = pending.indexOfFirst { it.id == id }
        if (from < 0) return queue
        val to = targetIndex.coerceIn(0, pending.size - 1)
        if (from == to) return queue
        val reordered = pending.toMutableList().apply { add(to, removeAt(from)) }
        var cursor = 0
        val items = queue.items.map { current ->
            if (current.state == AsrItemState.QUEUED) reordered[cursor++] else current
        }
        return queue.copy(items = items)
    }

    /**
     * 单条重试：失败/取消/可续跑 → 回到排队。
     *
     * [AsrItemState.INTERRUPTED] 保留已识别进度（**续跑**）；[AsrItemState.FAILED] 与
     * [AsrItemState.CANCELLED] 清零进度（重头识别，避免拿着半截 PCM 的错位进度）。
     */
    fun retry(queue: AsrQueueSnapshot, id: Long): AsrQueueSnapshot = reviveIfStopped(
        update(queue, id) { item ->
            if (!item.state.isRetryable) {
                item
            } else {
                val keepProgress = item.state == AsrItemState.INTERRUPTED
                item.copy(
                    state = AsrItemState.QUEUED,
                    progress = if (keepProgress) item.progress else AsrProgress.EMPTY,
                    failure = null,
                    errorMessage = null,
                    skipReason = null,
                    retryCount = item.retryCount + 1,
                )
            }
        },
    )

    /**
     * 队列被「取消全部」停掉（[AsrQueueState.STOPPED]）之后，只要又出现排队中的任务就复活成空转。
     *
     * 与 [enqueue] 同一口径：STOPPED 只表示"这一批被取消了"，不该变成一道再也出不去的门。
     */
    private fun reviveIfStopped(queue: AsrQueueSnapshot): AsrQueueSnapshot =
        if (queue.state == AsrQueueState.STOPPED && queue.items.any { it.state == AsrItemState.QUEUED }) {
            queue.copy(state = AsrQueueState.IDLE)
        } else {
            queue
        }

    /**
     * 一键重试全部失败项（**R19**）。
     *
     * 覆盖 [AsrItemState.FAILED]（清零进度）与 [AsrItemState.INTERRUPTED]（保留进度续跑）；
     * **不动**用户主动取消的 [AsrItemState.CANCELLED]。
     *
     * @return 新快照与被重试的任务 id（界面据此提示「已重新排队 N 项」）。
     */
    fun retryAllFailed(queue: AsrQueueSnapshot): Pair<AsrQueueSnapshot, List<Long>> {
        val targets = queue.items.filter { it.state == AsrItemState.FAILED || it.state == AsrItemState.INTERRUPTED }
        if (targets.isEmpty()) return queue to emptyList()
        var next = queue
        for (item in targets) next = retry(next, item.id)
        return next to targets.map { it.id }
    }

    /**
     * 进程被杀后重启的恢复（**R19：进程被杀后可续跑**）。
     *
     * 库里读出来时，RUNNING / WRITING 的任务其实已经死了：一律标成
     * [AsrItemState.INTERRUPTED]（**保留已识别进度**），队列回到 [AsrQueueState.PAUSED]，
     * 等用户点「继续」再跑——不做「一进 App 就自己跑起来抢 CPU」这种事。
     */
    fun markInterrupted(queue: AsrQueueSnapshot): AsrQueueSnapshot = queue.copy(
        items = queue.items.map { item ->
            if (item.state.isActive) item.copy(state = AsrItemState.INTERRUPTED) else item
        },
        state = if (queue.items.any { it.state == AsrItemState.QUEUED || it.state.isActive }) {
            AsrQueueState.PAUSED
        } else {
            queue.state
        },
    )

    /** 改并发（1 或 2）。 */
    fun setConcurrency(queue: AsrQueueSnapshot, value: Int): AsrQueueSnapshot =
        queue.copy(concurrency = clampConcurrency(value))

    /** 改请求线程数（播放让路时执行者会临时用 1，这里存的是用户设置）。 */
    fun setRequestedThreads(queue: AsrQueueSnapshot, value: Int): AsrQueueSnapshot =
        queue.copy(requestedThreads = WhisperNative.clampThreads(value))

    /** 移除已经落定的任务（界面「清空已完成」）。 */
    fun clearFinished(queue: AsrQueueSnapshot): AsrQueueSnapshot =
        queue.copy(items = queue.items.filterNot { it.state.isTerminal })

    private fun update(queue: AsrQueueSnapshot, id: Long, transform: (AsrItem) -> AsrItem): AsrQueueSnapshot {
        var changed = false
        val items = queue.items.map { item ->
            if (item.id == id) {
                val next = transform(item)
                if (next != item) changed = true
                next
            } else {
                item
            }
        }
        if (!changed) return queue
        val state = when {
            queue.state == AsrQueueState.STOPPED -> AsrQueueState.STOPPED
            queue.state == AsrQueueState.PAUSED -> AsrQueueState.PAUSED
            items.any { it.state.isActive } -> AsrQueueState.RUNNING
            else -> AsrQueueState.IDLE
        }
        return queue.copy(items = items, state = state)
    }

    private fun move(queue: AsrQueueSnapshot, id: Long, delta: Int): AsrQueueSnapshot {
        val index = queue.items.indexOfFirst { it.id == id }
        if (index < 0 || queue.items[index].state != AsrItemState.QUEUED) return queue
        var neighbour = index + delta
        while (neighbour in queue.items.indices && queue.items[neighbour].state != AsrItemState.QUEUED) {
            neighbour += delta
        }
        if (neighbour !in queue.items.indices) return queue
        val items = queue.items.toMutableList()
        val tmp = items[index]
        items[index] = items[neighbour]
        items[neighbour] = tmp
        return queue.copy(items = items)
    }
}
