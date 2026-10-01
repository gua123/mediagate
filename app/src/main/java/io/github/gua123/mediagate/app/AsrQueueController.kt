package io.github.gua123.mediagate.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.database.AsrDao
import io.github.gua123.mediagate.core.database.AsrTaskEntity
import io.github.gua123.mediagate.media.asr.AsrCandidate
import io.github.gua123.mediagate.media.asr.AsrFailureKind
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.AsrItemState
import io.github.gua123.mediagate.media.asr.AsrProgress
import io.github.gua123.mediagate.media.asr.AsrQueue
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot

/**
 * [AsrItem] 与 asr_task 表之间的映射（**M7-B / R19**，纯函数，便于单测与审阅）。
 *
 * 状态一律存枚举的 name（大写英文），读回时认不出就当 QUEUED——**绝不因为一条脏数据把队列炸掉**。
 */
object AsrTaskMapper {

    /** 队列项 → 数据库行。 */
    fun toEntity(item: AsrItem, queueOrder: Int, connectionId: Long? = null, modelId: String = "small"): AsrTaskEntity =
        AsrTaskEntity(
            id = item.id,
            batchId = item.batchId,
            connectionId = connectionId,
            path = item.path,
            name = item.name,
            model = modelId,
            state = item.state.name,
            progressMs = item.progress.recognizedMs,
            durationMs = if (item.progress.totalMs > 0L) item.progress.totalMs else item.durationMs,
            outputPath = item.outputPath,
            error = item.errorMessage,
            failure = item.failure?.name,
            skipReason = item.skipReason,
            retryCount = item.retryCount,
            queueOrder = queueOrder,
            createdAt = 0L,
        )

    /** 数据库行 → 队列项。 */
    fun toItem(entity: AsrTaskEntity): AsrItem = AsrItem(
        id = entity.id,
        path = entity.path,
        name = entity.name,
        durationMs = entity.durationMs,
        state = stateOf(entity.state),
        progress = AsrProgress(entity.progressMs, entity.durationMs),
        outputPath = entity.outputPath,
        failure = entity.failure?.let { name -> runCatching { AsrFailureKind.valueOf(name) }.getOrNull() },
        errorMessage = entity.error,
        skipReason = entity.skipReason,
        retryCount = entity.retryCount,
        batchId = entity.batchId,
    )

    /** 状态字符串 → 枚举；认不出按排队处理。 */
    fun stateOf(raw: String): AsrItemState =
        runCatching { AsrItemState.valueOf(raw) }.getOrDefault(AsrItemState.QUEUED)
}

/**
 * 批量字幕队列的持有者（**M7-B / R19**，:app 单例）。
 *
 * 为什么要有它，而不是把状态放在 Service 或 ViewModel 里：
 * - ViewModel 随页面销毁，队列却要跨页面、跨后台存活；
 * - Service 由系统创建/销毁，界面拿不到它的实例；
 * - 于是「唯一真相」放在 [AppContainer] 持有的本对象里，界面读 [snapshot]、命令走本对象，
 *   真正干活的 [AsrForegroundService] 也通过 [MediaGateApplication] 找到本对象。
 *
 * **落库**：[AsrDao] 是纯加法新增的 asr_task / asr_batch 两张表（MIGRATION 2 → 3）。
 * 选 Room 而不是 DataStore 的理由写在 docs 与交付报告里：队列是「多行、要按状态/顺序查询、
 * 要能只更新一行的进度」的关系型数据，DataStore 的整块 JSON 每次写全量，进程被杀时反而更容易丢。
 *
 * @param dao 任务表 DAO。
 * @param scope 应用级协程作用域（落库不阻塞调用方）。
 */
class AsrQueueController(
    private val dao: AsrDao,
    private val scope: CoroutineScope,
) {

    private val _snapshot = MutableStateFlow(AsrQueueSnapshot())

    /** 队列快照（界面与通知都读它）。 */
    val snapshot: StateFlow<AsrQueueSnapshot> = _snapshot.asStateFlow()

    /** 是否已经做过一次「进程被杀」恢复（幂等）。 */
    private var restored = false

    /**
     * 从库里恢复队列（**R19：进程被杀后可续跑**）。
     *
     * 上一轮跑着的（RUNNING/WRITING）在库里其实已经死了：先把它们标成 INTERRUPTED，
     * 再读回来，队列停在「已暂停」，等用户点继续——不做「一进 App 就自己抢 CPU」那种事。
     */
    suspend fun restore() {
        if (restored) return
        restored = true
        runCatching {
            val interrupted = dao.markInterrupted()
            val items = dao.tasks().map { AsrTaskMapper.toItem(it) }
            if (items.isEmpty()) return
            _snapshot.value = AsrQueue.of(items, state = io.github.gua123.mediagate.media.asr.AsrQueueState.PAUSED)
            AppLog.i(TAG, "恢复字幕队列 " + items.size + " 项（上次中断 " + interrupted + " 项）")
        }.onFailure { AppLog.w(TAG, "恢复字幕队列失败", it) }
    }

    /** 入队一批候选（R19 的「入队」）。 */
    fun enqueue(candidates: List<AsrCandidate>, batchName: String?, modelId: String) {
        if (candidates.isEmpty()) return
        val base = _snapshot.value.items.maxOfOrNull { it.id } ?: 0L
        val items = candidates.mapIndexed { index, candidate ->
            AsrItem(
                id = base + index + 1,
                path = candidate.path,
                name = candidate.name,
                state = AsrItemState.QUEUED,
            )
        }
        val next = AsrQueue.setRequestedThreads(AsrQueue.enqueue(_snapshot.value, items), _snapshot.value.requestedThreads)
        publish(next, modelId)
        scope.launch {
            runCatching {
                val batchId = batchName?.let { name ->
                    dao.insertBatch(
                        io.github.gua123.mediagate.core.database.AsrBatchEntity(
                            name = name,
                            sourceKind = "FILES",
                            rootPath = candidateRoot(candidates),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                dao.upsertTasks(next.items.mapIndexed { index, item -> AsrTaskMapper.toEntity(item, index, modelId = modelId) })
            }.onFailure { AppLog.w(TAG, "字幕任务落库失败", it) }
        }
    }

    /** 启动（补满并发额度）。返回本次真正开跑的任务。 */
    fun start(): List<AsrItem> {
        val result = AsrQueue.startNext(_snapshot.value)
        if (result.started.isEmpty()) return emptyList()
        publish(result.queue, null)
        return result.started
    }

    fun pause() = publish(AsrQueue.pause(_snapshot.value), null)

    fun resume() = publish(AsrQueue.resume(_snapshot.value), null)

    fun cancel(id: Long) = publish(AsrQueue.cancel(_snapshot.value, id), null)

    fun cancelAll() = publish(AsrQueue.cancelAll(_snapshot.value), null)

    fun moveUp(id: Long) = publish(AsrQueue.moveUp(_snapshot.value, id), null)

    fun moveDown(id: Long) = publish(AsrQueue.moveDown(_snapshot.value, id), null)

    fun retry(id: Long) = publish(AsrQueue.retry(_snapshot.value, id), null)

    fun retryAllFailed() = publish(AsrQueue.retryAllFailed(_snapshot.value).first, null)

    fun setConcurrency(value: Int, modelId: String) =
        publish(AsrQueue.setConcurrency(_snapshot.value, value), modelId)

    fun setRequestedThreads(value: Int) = publish(AsrQueue.setRequestedThreads(_snapshot.value, value), null)

    fun onProgress(id: Long, recognizedMs: Long, totalMs: Long) =
        publish(AsrQueue.onProgress(_snapshot.value, id, recognizedMs, totalMs), null)

    fun markWriting(id: Long) = publish(AsrQueue.markWriting(_snapshot.value, id), null)

    fun succeed(id: Long, outputPath: String?) = publish(AsrQueue.succeed(_snapshot.value, id, outputPath), null)

    fun fail(id: Long, error: Throwable?) = publish(AsrQueue.fail(_snapshot.value, id, error), null)

    fun fail(id: Long, kind: AsrFailureKind, message: String?) = publish(AsrQueue.fail(_snapshot.value, id, kind, message), null)

    fun skip(id: Long, reason: String) = publish(AsrQueue.skip(_snapshot.value, id, reason), null)

    fun clearFinished() = publish(AsrQueue.clearFinished(_snapshot.value), null)

    /** 队列里还有没有活（服务据此决定要不要继续跑 / 停自己）。 */
    val hasWork: Boolean
        get() = _snapshot.value.items.any { it.state == AsrItemState.QUEUED || it.state.isActive }

    /** 拉起前台服务（R19：后台生成）。 */
    fun startService(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AsrForegroundService::class.java).setAction(AsrForegroundService.ACTION_START))
        }.onFailure { AppLog.w(TAG, "拉起字幕前台服务失败", it) }
    }

    /** 通知前台服务停手（队列已空/已取消）。 */
    fun stopService(context: Context) {
        runCatching { context.stopService(Intent(context, AsrForegroundService::class.java)) }
    }

    /** 应用最新快照并落库（modelId 非空时顺带记模型档位）。 */
    private fun publish(next: AsrQueueSnapshot, modelId: String?) {
        val previous = _snapshot.value
        if (next == previous) return
        _snapshot.value = next
        val changed = next.items.filter { item -> previous.item(item.id) != item }
        if (changed.isEmpty()) return
        scope.launch {
            runCatching {
                val orders = next.items.withIndex().associate { (index, item) -> item.id to index }
                val model = modelId ?: currentModelId
                dao.upsertTasks(
                    changed.map { item -> AsrTaskMapper.toEntity(item, orders[item.id] ?: 0, modelId = model) },
                )
            }.onFailure { AppLog.w(TAG, "字幕任务状态落库失败", it) }
        }
    }

    /** 当前模型档位（由 :app 在装配时写入；落库用）。 */
    var currentModelId: String = "small"

    private fun candidateRoot(candidates: List<AsrCandidate>): String =
        candidates.firstOrNull()?.path?.substringBeforeLast('/', "") ?: ""

    private companion object {
        const val TAG = "asr-queue"
    }
}
