package io.github.gua123.mediagate.feature.tasks

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.asr.AsrCandidate
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot

/**
 * 任务中心看到的「来源」（**M7-B / R19** 的选择来源：当前连接 / 目录）。
 *
 * @property label 展示名（连接名或根目录名，全中文）。
 * @property path 从哪里开始浏览（后端内路径，空串 = 根）。
 */
data class TasksRoot(
    val label: String,
    val path: String = "",
)

/**
 * 批量字幕任务中心的宿主能力（**M7-B / R19**）。
 *
 * 沿用本项目的「手写 DI」口径：:feature:tasks 不反向依赖 :app，页面依赖由 :app 在导航宿主处
 * 用 [LocalTasksEnvironment] 注入。**接口只给能力，不给实现**：
 *
 * - [queue]：队列状态机（:media:asr 的 AsrQueue 快照）——页面只读它渲染；
 * - 队列命令（[enqueue] / [pause] / …）：由 :app 转给真正持有队列的 AsrForegroundService；
 * - [list] / [subtitleNames]：列目录与「同目录已有字幕」判定（走当前 StorageBackend）；
 * - [yielding] / [installedModelIds] / [selectedModelId]：让路状态与模型管理状态。
 */
interface TasksEnvironment {

    /** 队列快照（R19 的总进度 + 逐项进度都从这里来）。 */
    val queue: StateFlow<AsrQueueSnapshot>

    /** 是否因前台播放而让路（界面提示「播放中，已降速」）。 */
    val yielding: StateFlow<Boolean>

    /** 已安装的模型档位 id。 */
    val installedModelIds: StateFlow<List<String>>

    /** 当前选用的模型档位 id（默认 small）。 */
    val selectedModelId: StateFlow<String>

    /** 选择来源的起点；null = 还没有可用后端（首页还没选目录/连接）。 */
    val browseRoot: StateFlow<TasksRoot?>

    /** 列 [dir] 下的直接子项（不递归）。 */
    suspend fun list(dir: String): List<RemoteEntry>

    /** [dir] 下已有的文件名集合（用于「跳过已有字幕」判定）。 */
    suspend fun siblingNames(dir: String): Set<String>

    /** 把候选入队（含批次信息落库，R19）。 */
    fun enqueue(candidates: List<AsrCandidate>, batchName: String?)

    /** 开始/继续跑队列。 */
    fun start()

    /** 暂停（当前文件会在下个窗口边界让位）。 */
    fun pause()

    /** 继续。 */
    fun resume()

    /** 取消单条。 */
    fun cancel(id: Long)

    /** 取消全部。 */
    fun cancelAll()

    /** 上移一位。 */
    fun moveUp(id: Long)

    /** 下移一位。 */
    fun moveDown(id: Long)

    /** 单条重试。 */
    fun retry(id: Long)

    /** 一键重试全部失败项。 */
    fun retryAllFailed()

    /** 改并发（1 或 2）。 */
    fun setConcurrency(value: Int)

    /** 清空已落定的任务。 */
    fun clearFinished()

    /**
     * 移除单条已结束的任务（**2026-10-03 用户问"已取消能不能去掉"**）。
     *
     * 只对终态有效：正在跑/排队的要先取消，避免"还在跑却被删掉"的错觉。
     */
    fun removeTask(id: Long)
}

/**
 * :app 在导航宿主处提供的任务中心依赖（手写 DI 的注入点）。
 *
 * 取值失败说明忘了在 MediaGateApp() 里
 * CompositionLocalProvider(LocalTasksEnvironment provides container.tasksEnvironment)。
 */
val LocalTasksEnvironment = staticCompositionLocalOf<TasksEnvironment> {
    error("LocalTasksEnvironment 未注入：请在 :app 的 MediaGateApp() 里用 AppContainer 提供（R19）")
}
