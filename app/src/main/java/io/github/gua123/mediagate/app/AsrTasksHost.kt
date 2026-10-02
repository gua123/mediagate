package io.github.gua123.mediagate.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.feature.tasks.TasksEnvironment
import io.github.gua123.mediagate.feature.tasks.TasksRoot
import io.github.gua123.mediagate.media.asr.AsrCandidate
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot

/**
 * 任务中心宿主（**M7-B / R19**）的 :app 实现：把 :feature:tasks 要的能力接到真实依赖上。
 *
 * - 队列：[AsrQueueController] 的快照与命令（真正干活的是 [AsrForegroundService]）；
 * - 目录：当前 [StorageBackend]（本地/远端同一套代码，列目录与查同名字幕都走它）；
 * - 模型：已安装档位 + 当前选择（模型目录 filesDir/models）；
 * - 让路：前台是否正在播放（音频来自 MediaController 状态，视频由
 *   [AppContainer.setVideoPlaybackActive] 喂进来）。
 *
 * @param backendProvider 当前后端（懒取值：换根目录/换连接自动跟随）。
 * @param controller 队列持有者。
 * @param browseRoot 选择来源的起点（跟随首页的根目录/当前连接变化）。
 * @param installedModels 已安装的模型档位 id。
 * @param selectedModel 当前选中的模型档位 id。
 * @param yieldingFlow 是否正在因播放让路。
 * @param onEnqueue 入队（由 AppContainer 落库并持有批次信息）。
 * @param appContext 拉起/停止前台服务用（Application Context）。
 */
class AsrTasksHost(
    private val appContext: Context,
    private val backendProvider: () -> StorageBackend?,
    private val controller: AsrQueueController,
    override val browseRoot: StateFlow<TasksRoot?>,
    override val installedModelIds: StateFlow<List<String>>,
    override val selectedModelId: StateFlow<String>,
    override val yielding: StateFlow<Boolean>,
    private val onEnqueue: (List<AsrCandidate>, String?) -> Unit,
) : TasksEnvironment {

    override val queue: StateFlow<AsrQueueSnapshot> get() = controller.snapshot

    override suspend fun list(dir: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        requireBackend().list(dir, null)
    }

    override suspend fun siblingNames(dir: String): Set<String> = withContext(Dispatchers.IO) {
        runCatching { requireBackend().list(dir, null).map { it.name }.toSet() }
            .onFailure { error -> AppLog.w(TAG, "列同名字幕失败：" + dir, error) }
            .getOrDefault(emptySet())
    }

    override fun enqueue(candidates: List<AsrCandidate>, batchName: String?) = onEnqueue(candidates, batchName)

    override fun start() {
        controller.resume()
        controller.start()
        controller.startService(appContext)
    }

    override fun pause() = controller.pause()

    override fun resume() {
        controller.resume()
        controller.startService(appContext)
    }

    override fun cancel(id: Long) = controller.cancel(id)

    override fun cancelAll() {
        controller.cancelAll()
        controller.stopService(appContext)
    }

    override fun moveUp(id: Long) = controller.moveUp(id)

    override fun moveDown(id: Long) = controller.moveDown(id)

    override fun retry(id: Long) = controller.retry(id)

    override fun retryAllFailed() {
        controller.retryAllFailed()
        controller.startService(appContext)
    }

    override fun setConcurrency(value: Int) = controller.setConcurrency(value, selectedModelId.value)

    override fun clearFinished() = controller.clearFinished()

    override fun removeTask(id: Long) = controller.remove(id)

    private fun requireBackend(): StorageBackend =
        backendProvider() ?: throw StorageException.AccessDenied("还没有可用的来源，请先在首页选择目录或连接")

    private companion object {
        const val TAG = "asr-tasks-host"
    }
}
