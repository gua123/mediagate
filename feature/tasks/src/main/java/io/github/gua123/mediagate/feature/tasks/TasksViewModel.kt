package io.github.gua123.mediagate.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.asr.AsrCandidate
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot
import io.github.gua123.mediagate.media.asr.AsrSelection

/**
 * 批量字幕任务中心的 ViewModel（**M7-B / R19**）。
 *
 * 职责边界（与 :feature:connections 同一套路）：
 * - 所有状态迁移都走 [TasksReduce.reduce]（纯函数，JVM 单测覆盖）；
 * - 所有 IO（列目录、查同名字幕、入队、队列命令）都在本类里，且只跑在 [io] 上；
 * - 组合函数只读 [state]，不做任何 IO。
 *
 * 队列本身不在这里：它活在 :app 的 AsrForegroundService 里（进程被杀还要能续跑），
 * 本类只是它的一个观察者 + 命令发起方。
 *
 * @param environment 宿主能力（队列 / 目录 / 模型 / 让路）。
 * @param io 调度器（单测注入）。
 */
class TasksViewModel(
    private val environment: TasksEnvironment,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(TasksUiState())

    /** 页面唯一状态源。 */
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            environment.queue.collect { queue -> dispatch(TasksEvent.QueueUpdated(queue)) }
        }
        viewModelScope.launch {
            environment.yielding.collect { value -> dispatch(TasksEvent.YieldingChanged(value)) }
        }
        viewModelScope.launch {
            environment.installedModelIds.collect { installed ->
                dispatch(TasksEvent.ModelsChanged(installed, environment.selectedModelId.value))
            }
        }
        viewModelScope.launch {
            environment.selectedModelId.collect { selected ->
                dispatch(TasksEvent.ModelsChanged(environment.installedModelIds.value, selected))
            }
        }
        viewModelScope.launch {
            environment.browseRoot.collect { root -> openRoot(root) }
        }
    }

    /** 打开来源根目录（首页选好目录/连接后自动进来）。 */
    fun openRoot(root: TasksRoot?) {
        if (root == null) {
            dispatch(TasksEvent.LoadFailed("还没有可用的来源，请先在首页选择目录或连接"))
            return
        }
        dispatch(TasksEvent.EnterDir(root.path, root.label))
        viewModelScope.launch { refresh(root.path, root.label) }
    }

    /** 重新列当前目录。 */
    fun refresh() {
        val current = _state.value
        viewModelScope.launch { refresh(current.currentDir, current.dirLabel) }
    }

    /** 进入子目录。 */
    fun enter(path: String, label: String) {
        dispatch(TasksEvent.EnterDir(path, label))
        viewModelScope.launch { refresh(path, label) }
    }

    /** 回到上一级。 */
    fun goUp() {
        val current = _state.value.currentDir
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "")
        val label = environment.browseRoot.value?.label.orEmpty()
        dispatch(TasksEvent.EnterDir(parent, label))
        viewModelScope.launch { refresh(parent, label) }
    }

    /** 勾选/取消一行。 */
    fun toggle(path: String) = dispatch(TasksEvent.SelectionToggled(path))

    /** 全选当前列表里的视频。 */
    fun selectAll() = dispatch(TasksEvent.SelectionAll)

    /** 清空勾选。 */
    fun clearSelection() = dispatch(TasksEvent.SelectionCleared)

    /** 切换选择方式。 */
    fun setSourceKind(kind: TaskSourceKind) = dispatch(TasksEvent.SourceKindChanged(kind))

    /** 勾选「含子文件夹」。 */
    fun setRecursive(value: Boolean) = dispatch(TasksEvent.RecursiveChanged(value))

    /** 勾选「跳过已有字幕」。 */
    fun setSkipExisting(value: Boolean) = dispatch(TasksEvent.SkipExistingChanged(value))

    /** 关掉提示条。 */
    fun dismissNotice() = dispatch(TasksEvent.Notice(null))

    /** 入队（**R19 的核心动作**）。 */
    fun enqueue() {
        val current = _state.value
        if (!current.canEnqueue) {
            dispatch(
                TasksEvent.Notice(
                    if (!current.modelReady) current.modelHint else "请先勾选要生成字幕的视频",
                ),
            )
            return
        }
        viewModelScope.launch {
            dispatch(TasksEvent.Loading)
            val resolved = withContext(io) { resolveSelection(current) }
            // 去重：已经在队列里没结束的文件不再重复入队（2026-10-03 真机截图：同一文件排了三遍）
            val queued = current.queue.items
                .filterNot { it.state.isTerminal }
                .mapTo(HashSet()) { it.path }
            val planned = TasksPlan.plan(resolved, current.skipExisting, alreadyQueued = queued)
            if (planned.accepted.isEmpty()) {
                dispatch(TasksEvent.Notice("没有需要生成字幕的视频（已自动过滤非视频与已有字幕的文件）"))
                dispatch(TasksEvent.Loaded(current.currentDir, current.dirLabel, current.entries))
                return@launch
            }
            environment.enqueue(planned.accepted, batchNameOf(current))
            environment.start()
            dispatch(TasksEvent.Loaded(current.currentDir, current.dirLabel, current.entries))
            dispatch(TasksEvent.Notice(noticeOf(planned)))
        }
    }

    // ------------------------------------------------------------ 队列命令（直接转给宿主）

    /** 暂停。 */
    fun pause() = environment.pause()

    /** 继续。 */
    fun resume() {
        environment.resume()
        environment.start()
    }

    /** 取消单条。 */
    fun cancel(id: Long) = environment.cancel(id)

    /** 取消全部。 */
    fun cancelAll() = environment.cancelAll()

    /** 上移一位。 */
    fun moveUp(id: Long) = environment.moveUp(id)

    /** 下移一位。 */
    fun moveDown(id: Long) = environment.moveDown(id)

    /** 单条重试。 */
    fun retry(id: Long) = environment.retry(id)

    /** 一键重试全部失败项。 */
    fun retryAllFailed() {
        environment.retryAllFailed()
        environment.start()
    }

    /** 改并发（1 或 2）。 */
    fun setConcurrency(value: Int) = environment.setConcurrency(value)

    /** 清空已落定的任务。 */
    fun clearFinished() = environment.clearFinished()

    private fun dispatch(event: TasksEvent) {
        _state.update { TasksReduce.reduce(it, event) }
    }

    private suspend fun refresh(dir: String, label: String) {
        dispatch(TasksEvent.Loading)
        val entries = runCatching { loadEntries(dir) }
            .onFailure { error ->
                dispatch(TasksEvent.LoadFailed(friendlyMessage(error)))
            }
            .getOrNull() ?: return
        dispatch(TasksEvent.Loaded(dir, label, entries))
    }

    /** 列目录 + 查同名字幕（都在 IO 上）。 */
    private suspend fun loadEntries(dir: String): List<TaskEntryUi> {
        val children = environment.list(dir)
        val names = environment.siblingNames(dir)
        return children.map { entry -> entry.toUi(names, depth = 0) }
    }

    /**
     * 把勾选展开成候选文件（**目录在这里才递归**：勾目录 = 整目录入队）。
     *
     * 返回的每个候选都带上了「同目录是否已有它的字幕」，供 [AsrSelection.plan] 判定跳过。
     */
    private suspend fun resolveSelection(state: TasksUiState): List<AsrCandidate> {
        val result = ArrayList<AsrCandidate>()
        for (entry in state.selectedEntries) {
            if (!entry.isDirectory) {
                result += AsrCandidate(entry.path, entry.name, entry.size, entry.hasSubtitle)
                continue
            }
            result += expandDirectory(entry.path, state.recursive)
        }
        return result
    }

    /** 展开一个目录（按需递归）。 */
    private suspend fun expandDirectory(dir: String, recursive: Boolean): List<AsrCandidate> {
        val children = runCatching { environment.list(dir) }.getOrDefault(emptyList())
        val names = runCatching { environment.siblingNames(dir) }.getOrDefault(emptySet())
        val out = ArrayList<AsrCandidate>()
        for (child in children) {
            if (child.isDirectory) {
                if (recursive) out += expandDirectory(child.path, true)
                continue
            }
            if (!AsrSelection.isVideoEntry(child)) continue
            out += AsrCandidate(
                path = child.path,
                name = child.name,
                size = child.size,
                hasSubtitle = AsrSelection.hasExistingSubtitle(child.path, names),
            )
        }
        return out
    }

    private fun batchNameOf(state: TasksUiState): String =
        (state.dirLabel.ifBlank { state.rootLabel }).ifBlank { "批量字幕" }

    private fun noticeOf(planned: io.github.gua123.mediagate.media.asr.AsrSelectionResult): String = buildString {
        append("已入队 ").append(planned.accepted.size).append(" 项")
        if (planned.skipped.isNotEmpty()) append("，跳过已有字幕 ").append(planned.skipped.size).append(" 项")
        if (planned.filteredOut > 0) append("，过滤非视频 ").append(planned.filteredOut).append(" 项")
        if (planned.duplicate.isNotEmpty()) append("，跳过重复 ").append(planned.duplicate.size).append(" 项")
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is io.github.gua123.mediagate.data.storage.api.StorageException.AccessDenied -> "没有访问权限，请先在首页授权目录"
        is io.github.gua123.mediagate.data.storage.api.StorageException.NotFound -> "目录不存在或已被移走"
        is io.github.gua123.mediagate.data.storage.api.StorageException.Network -> "网络不可用，连不上远端目录"
        else -> "列目录失败：" + ErrorText.of(error, "详情见诊断日志")
    }

    private fun RemoteEntry.toUi(names: Set<String>, depth: Int): TaskEntryUi = TaskEntryUi(
        path = path,
        name = name,
        size = size,
        isDirectory = isDirectory,
        hasSubtitle = !isDirectory && AsrSelection.hasExistingSubtitle(path, names),
        depth = depth,
    )

    /** 队列快照的只读视图（界面提示「当前文件」用）。 */
    val queueSnapshot: AsrQueueSnapshot get() = _state.value.queue
}
