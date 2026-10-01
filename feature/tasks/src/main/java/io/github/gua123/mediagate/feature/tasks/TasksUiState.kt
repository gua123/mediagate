package io.github.gua123.mediagate.feature.tasks

import io.github.gua123.mediagate.media.asr.AsrCandidate
import io.github.gua123.mediagate.media.asr.AsrItemState
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot
import io.github.gua123.mediagate.media.asr.AsrQueueState
import io.github.gua123.mediagate.media.asr.AsrSelection
import io.github.gua123.mediagate.media.asr.AsrSelectionResult
import io.github.gua123.mediagate.media.asr.WhisperModel

/** 批量选择的方式（**R19**）：挑几个文件，或者整个文件夹一起入队。 */
enum class TaskSourceKind(val zhText: String) {

    /** 多选文件。 */
    FILES("多选文件"),

    /** 整个文件夹（可勾选含子文件夹递归）。 */
    FOLDER("整个文件夹"),
}

/**
 * 目录列表里的一行（**R19**）。
 *
 * @property hasSubtitle 同目录已经有它的字幕（「跳过已有字幕」的依据）。
 * @property depth 递归展开时的层级（0 = 当前目录直接子项）。
 */
data class TaskEntryUi(
    val path: String,
    val name: String,
    val size: Long = -1L,
    val isDirectory: Boolean = false,
    val hasSubtitle: Boolean = false,
    val selected: Boolean = false,
    val depth: Int = 0,
) {
    /** 能不能被勾选：文件必须在视频白名单里；目录在 FOLDER 模式下可勾。 */
    fun selectable(mode: TaskSourceKind): Boolean =
        if (isDirectory) mode == TaskSourceKind.FOLDER else AsrSelection.isVideoEntry(name, false, size)
}

/** 页面事件（**R19**，全部经 [TasksReduce.reduce] 这个纯函数改状态）。 */
sealed interface TasksEvent {

    /** 开始列目录。 */
    data object Loading : TasksEvent

    /** 列目录成功。 */
    data class Loaded(val dir: String, val label: String, val entries: List<TaskEntryUi>) : TasksEvent

    /** 列目录失败（无权限 / 网络…）。 */
    data class LoadFailed(val message: String) : TasksEvent

    /** 勾选/取消一行。 */
    data class SelectionToggled(val path: String) : TasksEvent

    /** 全选当前列表里的视频。 */
    data object SelectionAll : TasksEvent

    /** 清空勾选。 */
    data object SelectionCleared : TasksEvent

    /** 切换「多选文件 / 整个文件夹」。 */
    data class SourceKindChanged(val kind: TaskSourceKind) : TasksEvent

    /** 勾选「含子文件夹」。 */
    data class RecursiveChanged(val value: Boolean) : TasksEvent

    /** 勾选「跳过已有字幕的文件」。 */
    data class SkipExistingChanged(val value: Boolean) : TasksEvent

    /** 进入某个子目录。 */
    data class EnterDir(val path: String, val label: String) : TasksEvent

    /** 队列快照更新（来自 :app 的 AsrQueue）。 */
    data class QueueUpdated(val queue: AsrQueueSnapshot) : TasksEvent

    /** 让路状态更新。 */
    data class YieldingChanged(val value: Boolean) : TasksEvent

    /** 模型状态更新。 */
    data class ModelsChanged(val installedIds: List<String>, val selectedId: String) : TasksEvent

    /** 一句中文提示（入队结果等）。 */
    data class Notice(val message: String?) : TasksEvent
}

/**
 * 任务中心的整页状态（**M7-B / R19**）。
 *
 * 它只做两件事：把「选择区」的用户操作收成纯数据，把 [queue] 的队列快照投影成
 * 界面要显示的那几个字符串与百分比。真正的识别、写文件、让路都在 :media:asr 与 :app 里。
 */
data class TasksUiState(
    val loading: Boolean = false,
    val sourceKind: TaskSourceKind = TaskSourceKind.FILES,
    val rootLabel: String = "",
    val currentDir: String = "",
    val dirLabel: String = "",
    val entries: List<TaskEntryUi> = emptyList(),
    val recursive: Boolean = false,
    val skipExisting: Boolean = true,
    val errorMessage: String? = null,
    val notice: String? = null,
    val queue: AsrQueueSnapshot = AsrQueueSnapshot(),
    val yielding: Boolean = false,
    val installedModelIds: List<String> = emptyList(),
    val selectedModelId: String = WhisperModel.DEFAULT.id,
) {

    /** 当前勾选的条目。 */
    val selectedEntries: List<TaskEntryUi> get() = entries.filter { it.selected }

    /** 当前列表里能勾的条目数。 */
    val selectableCount: Int get() = entries.count { it.selectable(sourceKind) }

    /** 当前列表里的视频数（含目录，按模式算可选项）。 */
    val videoCount: Int get() = entries.count { !it.isDirectory && AsrSelection.isVideoEntry(it.name, false, it.size) }

    /** 选中的模型档位是否已经装好。 */
    val modelReady: Boolean get() = selectedModelId in installedModelIds

    /** 模型没装时的中文提示（界面据此引导去下载）；装好了是 null。 */
    val modelHint: String?
        get() = if (modelReady) null else "还没下载 " + selectedModelId + " 模型，去「设置 → 语音识别模型」下载后再入队"

    /**
     * 能不能入队：有勾选 + 模型可用。
     *
     * **2026-10-03 真机截图修**：这里原先还要求 `queue.state != STOPPED`——而「取消全部」正是把队列置成
     * STOPPED，于是按钮变灰，加上当时 `resume` 对 STOPPED 也是空转，用户**彻底没有出路**。
     * 现在入队本身会把 STOPPED 复活成空转（见 `AsrQueue.enqueue`），这道门就不需要了。
     */
    val canEnqueue: Boolean
        get() = selectedEntries.isNotEmpty() && modelReady

    /**
     * 按钮点不了时的原因；能点就是 null。
     *
     * 灰按钮不解释原因 = 用户只能干瞪眼（真机反馈"勾了 1 项却点不动"就是这么来的）。
     * 界面把它显示在按钮上方（红色小字）。
     */
    val enqueueHint: String?
        get() = when {
            !modelReady -> modelHint
            selectedEntries.isEmpty() -> "先勾选要生成字幕的视频（也可以点「全选视频」）"
            else -> null
        }

    /** 总进度百分比（0..100）。 */
    val progressPercent: Int get() = queue.totalPercent

    /** 当前正在识别的文件名（没有则为 null）。 */
    val currentFileName: String? get() = queue.currentItem?.name

    /** 界面顶部的汇总文案（全中文，R16）。 */
    val summaryText: String
        get() {
            val summary = queue.summary
            if (summary.total == 0) return "还没有任务"
            return "共 " + summary.total + " 项 · 成功 " + summary.succeeded + " · 失败 " + summary.failed +
                " · 跳过 " + summary.skipped + " · 待处理 " + (summary.pending + summary.interrupted)
        }

    /** 队列状态文案。 */
    val queueStateText: String get() = queue.state.zhText

    /** 有没有可重试的失败项（一键重试按钮的可用性）。 */
    val hasRetryable: Boolean
        get() = queue.items.any { it.state == AsrItemState.FAILED || it.state == AsrItemState.INTERRUPTED }

    /** 能不能暂停：正在跑且没暂停。 */
    val canPause: Boolean get() = queue.state == AsrQueueState.RUNNING

    /**
     * 能不能继续：暂停了，或者"没在跑但还有排队任务"（含被「取消全部」停掉的情况）。
     *
     * **STOPPED 也算**（2026-10-03 修）：[AsrQueue.resume] 现在会把 STOPPED 变回空转，
     * 所以它也是一个正当的"复活"出口。
     */
    val canResume: Boolean
        get() = queue.state == AsrQueueState.PAUSED ||
            (
                (queue.state == AsrQueueState.IDLE || queue.state == AsrQueueState.STOPPED) &&
                    queue.items.any { it.state == AsrItemState.QUEUED }
                )

    /** 能不能取消全部：还有没落定的任务。 */
    val canCancelAll: Boolean get() = queue.items.any { !it.state.isTerminal }

    /** 还能不能上移（队列里有排队中的任务）。 */
    val canReorder: Boolean get() = queue.items.count { it.state == AsrItemState.QUEUED } > 1
}

/**
 * 任务中心的状态归约（**M7-B / R19**，纯函数）。
 *
 * 所有界面操作都先变成 [TasksEvent]，再由 [reduce] 算出新状态；组合函数只读结果，
 * 因此「勾选、全选、切模式、队列进度投影」这些分支都能在 JVM 单测里穷举。
 */
object TasksReduce {

    /** 事件 → 新状态（纯函数，不改原对象）。 */
    fun reduce(state: TasksUiState, event: TasksEvent): TasksUiState = when (event) {
        TasksEvent.Loading -> state.copy(loading = true, errorMessage = null)

        is TasksEvent.Loaded -> state.copy(
            loading = false,
            errorMessage = null,
            currentDir = event.dir,
            dirLabel = event.label,
            entries = sortEntries(event.entries),
        )

        is TasksEvent.LoadFailed -> state.copy(loading = false, errorMessage = event.message, entries = emptyList())

        is TasksEvent.SelectionToggled -> state.copy(
            entries = state.entries.map { entry ->
                if (entry.path == event.path && entry.selectable(state.sourceKind)) {
                    entry.copy(selected = !entry.selected)
                } else {
                    entry
                }
            },
            notice = null,
        )

        TasksEvent.SelectionAll -> state.copy(
            entries = state.entries.map { entry ->
                if (entry.selectable(state.sourceKind)) entry.copy(selected = true) else entry
            },
            notice = null,
        )

        TasksEvent.SelectionCleared -> state.copy(
            entries = state.entries.map { it.copy(selected = false) },
            notice = null,
        )

        is TasksEvent.SourceKindChanged -> state.copy(
            sourceKind = event.kind,
            // 换模式时清空勾选：文件模式勾不了目录，留着会造成"看不见的选中"
            entries = state.entries.map { it.copy(selected = false) },
            notice = null,
        )

        is TasksEvent.RecursiveChanged -> state.copy(recursive = event.value)

        is TasksEvent.SkipExistingChanged -> state.copy(skipExisting = event.value)

        is TasksEvent.EnterDir -> state.copy(
            currentDir = event.path,
            dirLabel = event.label,
            entries = emptyList(),
            notice = null,
        )

        is TasksEvent.QueueUpdated -> state.copy(queue = event.queue)

        is TasksEvent.YieldingChanged -> state.copy(yielding = event.value)

        is TasksEvent.ModelsChanged -> state.copy(
            installedModelIds = event.installedIds,
            selectedModelId = event.selectedId,
        )

        is TasksEvent.Notice -> state.copy(notice = event.message)
    }

    /** 列表排序（**纯函数**）：目录在前，然后按文件名（与浏览页一致的口径）。 */
    fun sortEntries(entries: List<TaskEntryUi>): List<TaskEntryUi> =
        entries.sortedWith(compareByDescending<TaskEntryUi> { it.isDirectory }.thenBy { it.name.lowercase() })
}

/**
 * 入队计划的纯逻辑（**R19**：自动过滤非视频 + 可跳过已有字幕）。
 *
 * 目录递归展开、列目录这些 IO 在 ViewModel 里；这里只负责「给一批解析好的候选，
 * 算出谁入队、谁跳过、丢了几条」，判据全部复用 :media:asr 的 [AsrSelection]。
 */
object TasksPlan {

    /** 勾选的行 → 候选（目录由调用方先展开成文件行）。 */
    fun candidatesOf(entries: List<TaskEntryUi>): List<AsrCandidate> = entries
        .filter { !it.isDirectory }
        .map { AsrCandidate(path = it.path, name = it.name, size = it.size, hasSubtitle = it.hasSubtitle) }

    /** 勾选 + 展开后的候选 → 入队/跳过结果。 */
    fun plan(resolved: List<AsrCandidate>, skipExisting: Boolean): AsrSelectionResult =
        AsrSelection.plan(resolved, skipExisting)

    /** 一行是不是视频（目录不算；界面用它决定要不要显示「视频」徽标）。 */
    fun isVideo(name: String, size: Long = -1L): Boolean = AsrSelection.isVideoEntry(name, false, size)
}
