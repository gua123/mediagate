package io.github.gua123.mediagate.media.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * [AsrQueue] 状态机的 JVM 单测（**M7-B / R19**）。
 *
 * 覆盖：入队 / 启动与并发额度 / 进度 / 暂停继续 / 取消 / 重排 / 单条重试 / 一键重试全部失败项 /
 * 进程被杀恢复 / 总进度汇总 / 失败分类。
 */
class AsrQueueTest {

    private fun item(id: Long, state: AsrItemState = AsrItemState.QUEUED, name: String = "v$id.mkv") =
        AsrItem(id = id, path = "/movies/$name", name = name, state = state)

    private fun queue(vararg items: AsrItem, concurrency: Int = 1) =
        AsrQueue.of(items.toList(), concurrency = concurrency)

    // ---------------------------------------------------------------- 清理已结束（2026-10-03 用户问"已取消能不能去掉"）

    @Test
    fun removedIds_listsWhatGotDroppedSoTheRowsCanBeDeleted() {
        val before = queue(
            item(1, state = AsrItemState.SUCCEEDED),
            item(2, state = AsrItemState.CANCELLED),
            item(3, state = AsrItemState.QUEUED),
        )
        val after = AsrQueue.clearFinished(before)
        assertEquals(listOf(3L), after.items.map { it.id })
        // 关键：清掉的那两条要能被识别出来（否则只清了内存、重启又回来）
        assertEquals(listOf(1L, 2L), AsrQueue.removedIds(before, after))
    }

    @Test
    fun removedIds_isEmptyWhenNothingWasRemoved() {
        val before = queue(item(1, state = AsrItemState.QUEUED))
        assertEquals(emptyList<Long>(), AsrQueue.removedIds(before, before))
    }

    // ---------------------------------------------------------------- 入队去重（2026-10-03 真机：同一文件排了三遍）

    @Test
    fun enqueue_ignoresDuplicatesOfUnfinishedTasks() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1, name = "a.mp4")))
        // 同一个路径再来一次：忽略（队列里已有未结束的同名任务）
        state = AsrQueue.enqueue(state, listOf(item(2, name = "a.mp4")))
        assertEquals(1, state.items.size)

        // 一批里自己重复也会被去掉
        state = AsrQueue.enqueue(state, listOf(item(3, name = "b.mp4"), item(4, name = "b.mp4")))
        assertEquals(2, state.items.size)
        assertEquals(listOf("a.mp4", "b.mp4"), state.items.map { it.name })
    }

    @Test
    fun enqueue_allowsRerunningFinishedFiles() {
        val done = queue(item(1, state = AsrItemState.SUCCEEDED, name = "a.mp4"))
        val next = AsrQueue.enqueue(done, listOf(item(2, name = "a.mp4")))
        // 已成功的不拦：用户就是故意重跑
        assertEquals(2, next.items.size)
    }

    // ---------------------------------------------------------------- 入队与启动

    @Test
    fun enqueue_appendsAndKeepsOrder() {
        val state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3)))
        assertEquals(listOf(1L, 2L, 3L), state.items.map { it.id })
        assertTrue(state.items.all { it.state == AsrItemState.QUEUED })
        assertEquals(3, state.summary.pending)
    }

    @Test
    fun startNext_startsOnlyOneWhenConcurrencyIsOne() {
        val state = AsrQueue.enqueue(queue(), listOf(item(1), item(2)))
        val started = AsrQueue.startNext(state)
        assertEquals(1, started.started.size)
        assertEquals(1L, started.started.single().id)
        assertEquals(AsrQueueState.RUNNING, started.queue.state)
        assertEquals(1, started.queue.activeItems.size)
        assertEquals(AsrItemState.QUEUED, started.queue.item(2)!!.state)
    }

    @Test
    fun startNext_fillsTwoSlotsWhenConcurrencyIsTwo() {
        val state = AsrQueue.enqueue(queue(concurrency = 2), listOf(item(1), item(2), item(3)))
        val started = AsrQueue.startNext(state)
        assertEquals(listOf(1L, 2L), started.started.map { it.id })
        assertEquals(2, started.queue.activeItems.size)
        // 额度用完，再调一次不再启动
        assertTrue(AsrQueue.startNext(started.queue).started.isEmpty())
    }

    @Test
    fun startNext_doesNothingWhenPausedOrStopped() {
        val queued = AsrQueue.enqueue(queue(), listOf(item(1)))
        assertTrue(AsrQueue.startNext(AsrQueue.pause(queued)).started.isEmpty())
        assertTrue(AsrQueue.startNext(AsrQueue.cancelAll(queued)).started.isEmpty())
    }

    @Test
    fun startNext_doesNothingWhenNothingQueued() {
        assertTrue(AsrQueue.startNext(queue()).started.isEmpty())
    }

    @Test
    fun clampConcurrency_keepsWithinOneAndTwo() {
        assertEquals(1, AsrQueue.clampConcurrency(0))
        assertEquals(2, AsrQueue.clampConcurrency(9))
    }

    // ---------------------------------------------------------------- 进度

    @Test
    fun onProgress_updatesItemAndTotalProgress() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2)))
        state = AsrQueue.startNext(state).queue
        state = AsrQueue.onProgress(state, 1L, recognizedMs = 30_000L, totalMs = 60_000L)
        val first = state.item(1L)!!
        assertEquals(0.5f, first.progress.fraction, 0.0001f)
        assertEquals(60_000L, first.durationMs)
        // 任务 1 走了一半、任务 2 没动 → 总进度 25%
        assertEquals(0.25f, state.totalProgress, 0.0001f)
        assertEquals(25, state.totalPercent)
    }

    @Test
    fun totalProgress_countsTerminalStatesAsDone() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3), item(4)))
        state = AsrQueue.succeed(state, 1L, "/movies/v1.srt")
        state = AsrQueue.skip(state, 2L, "已有字幕")
        state = AsrQueue.fail(state, 3L, AsrFailureKind.NETWORK, "断了")
        assertEquals(0.75f, state.totalProgress, 0.0001f)
        assertEquals(1, state.summary.succeeded)
        assertEquals(1, state.summary.skipped)
        assertEquals(1, state.summary.failed)
        assertFalse(state.isFinished)
        state = AsrQueue.cancel(state, 4L)
        assertTrue(state.isFinished)
        assertEquals(1.0f, state.totalProgress, 0.0001f)
    }

    @Test
    fun markWriting_andSucceed_movesThroughStates() {
        var state = AsrQueue.startNext(AsrQueue.enqueue(queue(), listOf(item(1)))).queue
        state = AsrQueue.markWriting(state, 1L)
        assertEquals(AsrItemState.WRITING, state.item(1L)!!.state)
        state = AsrQueue.succeed(state, 1L, "/movies/v1.srt")
        assertEquals(AsrItemState.SUCCEEDED, state.item(1L)!!.state)
        assertEquals("/movies/v1.srt", state.item(1L)!!.outputPath)
        assertEquals(AsrQueueState.IDLE, state.state)
    }

    @Test
    fun fail_recordsClassifiedReason() {
        var state = AsrQueue.startNext(AsrQueue.enqueue(queue(), listOf(item(1)))).queue
        state = AsrQueue.fail(state, 1L, StorageException.AccessDenied())
        val failed = state.item(1L)!!
        assertEquals(AsrItemState.FAILED, failed.state)
        assertEquals(AsrFailureKind.NO_WRITE_PERMISSION, failed.failure)
    }

    // ---------------------------------------------------------------- 暂停 / 继续 / 取消

    @Test
    fun pauseAndResume_toggleQueueState() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1)))
        state = AsrQueue.pause(state)
        assertEquals(AsrQueueState.PAUSED, state.state)
        assertEquals(0, state.canStartMore())
        state = AsrQueue.resume(state)
        assertEquals(AsrQueueState.IDLE, state.state)
        assertEquals(1, state.canStartMore())
        // 「取消全部」之后是 STOPPED，但**继续要能把它救回来**（2026-10-03 真机：灰按钮没有出路）
        assertEquals(AsrQueueState.IDLE, AsrQueue.resume(AsrQueue.cancelAll(state)).state)
    }

    @Test
    fun resume_keepsRunningWhenSomethingIsActive() {
        val running = AsrQueue.startNext(AsrQueue.enqueue(queue(), listOf(item(1), item(2)))).queue
        val paused = AsrQueue.pause(running)
        assertEquals(AsrQueueState.RUNNING, AsrQueue.resume(paused).state)
    }

    @Test
    fun cancel_singleItemLeavesOthersUntouched() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2)))
        state = AsrQueue.cancel(state, 1L)
        assertEquals(AsrItemState.CANCELLED, state.item(1L)!!.state)
        assertEquals(AsrItemState.QUEUED, state.item(2L)!!.state)
        // 已经落定的任务不会被取消改写
        state = AsrQueue.succeed(state, 2L, null)
        assertEquals(AsrItemState.SUCCEEDED, AsrQueue.cancel(state, 2L).item(2L)!!.state)
    }

    @Test
    fun cancelAll_stopsEverything() {
        var state = AsrQueue.startNext(AsrQueue.enqueue(queue(), listOf(item(1), item(2)))).queue
        state = AsrQueue.cancelAll(state)
        assertEquals(AsrQueueState.STOPPED, state.state)
        assertTrue(state.items.all { it.state == AsrItemState.CANCELLED })
    }

    // ---------------------------------------------------------------- 重排

    @Test
    fun moveUpAndDown_swapOnlyQueuedNeighbours() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3)))
        state = AsrQueue.moveUp(state, 2L)
        assertEquals(listOf(2L, 1L, 3L), state.items.map { it.id })
        state = AsrQueue.moveDown(state, 2L)
        assertEquals(listOf(1L, 2L, 3L), state.items.map { it.id })
        // 已经在最前 / 最后 → 原样
        assertEquals(listOf(1L, 2L, 3L), AsrQueue.moveUp(state, 1L).items.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), AsrQueue.moveDown(state, 3L).items.map { it.id })
    }

    @Test
    fun moveTo_reordersPendingItemsOnly() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3), item(4)))
        state = AsrQueue.moveTo(state, 4L, 0)
        assertEquals(listOf(4L, 1L, 2L, 3L), state.items.map { it.id })
        state = AsrQueue.moveTo(state, 4L, 99)
        assertEquals(listOf(1L, 2L, 3L, 4L), state.items.map { it.id })
        // 非排队中的任务不参与重排
        val running = AsrQueue.startNext(state).queue
        assertEquals(running.items.map { it.id }, AsrQueue.moveTo(running, 1L, 2).items.map { it.id })
    }

    // ---------------------------------------------------------------- 重试

    @Test
    fun retry_resetsFailedItemToQueued() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1)))
        state = AsrQueue.startNext(state).queue
        state = AsrQueue.onProgress(state, 1L, 10_000L, 60_000L)
        state = AsrQueue.fail(state, 1L, AsrFailureKind.DECODE_FAILED, "坏了")
        state = AsrQueue.retry(state, 1L)
        val retried = state.item(1L)!!
        assertEquals(AsrItemState.QUEUED, retried.state)
        assertNull(retried.failure)
        assertEquals(AsrProgress.EMPTY, retried.progress)
        assertEquals(1, retried.retryCount)
    }

    @Test
    fun retry_keepsProgressForInterruptedItem() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1)))
        state = AsrQueue.startNext(state).queue
        state = AsrQueue.onProgress(state, 1L, 20_000L, 60_000L)
        state = AsrQueue.markInterrupted(state)
        assertEquals(AsrItemState.INTERRUPTED, state.item(1L)!!.state)
        assertEquals(AsrQueueState.PAUSED, state.state)
        state = AsrQueue.retry(state, 1L)
        assertEquals(AsrItemState.QUEUED, state.item(1L)!!.state)
        assertEquals(20_000L, state.item(1L)!!.progress.recognizedMs)
    }

    @Test
    fun retryAllFailed_coversFailedAndInterruptedButNotCancelled() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3), item(4)))
        state = AsrQueue.fail(state, 1L, AsrFailureKind.NETWORK, "断网")
        state = AsrQueue.fail(state, 2L, AsrFailureKind.NO_AUDIO_TRACK, "没音轨")
        state = AsrQueue.cancel(state, 3L)
        state = AsrQueue.restore(
            state,
            state.items.map { if (it.id == 4L) it.copy(state = AsrItemState.INTERRUPTED) else it },
        )
        val (next, retried) = AsrQueue.retryAllFailed(state)
        assertEquals(listOf(1L, 2L, 4L), retried)
        assertEquals(AsrItemState.QUEUED, next.item(1L)!!.state)
        assertEquals(AsrItemState.QUEUED, next.item(4L)!!.state)
        assertEquals(AsrItemState.CANCELLED, next.item(3L)!!.state)
    }

    @Test
    fun retryAllFailed_isNoOpWithoutFailures() {
        val state = AsrQueue.enqueue(queue(), listOf(item(1)))
        val (next, retried) = AsrQueue.retryAllFailed(state)
        assertTrue(retried.isEmpty())
        assertEquals(state, next)
    }

    // ---------------------------------------------------------------- 进程被杀恢复

    @Test
    fun markInterrupted_pausesQueueAndKeepsProgress() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3)))
        state = AsrQueue.startNext(state).queue
        state = AsrQueue.onProgress(state, 1L, 15_000L, 30_000L)
        state = AsrQueue.markInterrupted(state)
        val first = state.item(1L)!!
        assertEquals(AsrItemState.INTERRUPTED, first.state)
        assertEquals(15_000L, first.progress.recognizedMs)
        assertEquals(AsrQueueState.PAUSED, state.state)
        assertEquals(2, state.summary.pending) // 2、3 仍在排队
        assertFalse(state.isFinished)
    }

    @Test
    fun clearFinished_removesTerminalItemsOnly() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1), item(2), item(3)))
        state = AsrQueue.succeed(state, 1L, null)
        state = AsrQueue.fail(state, 2L, AsrFailureKind.UNKNOWN, null)
        state = AsrQueue.clearFinished(state)
        assertEquals(listOf(3L), state.items.map { it.id })
    }

    @Test
    fun setConcurrency_andThreads_areClamped() {
        var state = AsrQueue.enqueue(queue(), listOf(item(1)))
        state = AsrQueue.setConcurrency(state, 5)
        assertEquals(2, state.concurrency)
        state = AsrQueue.setRequestedThreads(state, 99)
        assertEquals(WhisperNative.MAX_THREADS, state.requestedThreads)
    }

    @Test
    fun isEmpty_isTrueForFreshQueue() {
        assertTrue(queue().isEmpty)
        assertFalse(queue(item(1)).isEmpty)
        assertFalse(queue(item(1)).isFinished)
    }

    // ---------------------------------------------------------------- 失败分类

    @Test
    fun failureKind_classifiesCommonThrowables() {
        assertEquals(AsrFailureKind.NO_WRITE_PERMISSION, AsrFailureKind.of(StorageException.AccessDenied()))
        assertEquals(AsrFailureKind.NETWORK, AsrFailureKind.of(StorageException.Network()))
        assertEquals(AsrFailureKind.NOT_FOUND, AsrFailureKind.of(StorageException.NotFound()))
        assertEquals(AsrFailureKind.ENGINE_UNAVAILABLE, AsrFailureKind.of(WhisperException(WhisperNative.ERR_UNAVAILABLE, null)))
        assertEquals(AsrFailureKind.NATIVE_ERROR, AsrFailureKind.of(WhisperException(WhisperNative.ERR_TRANSCRIBE_FAILED, "x")))
        assertEquals(AsrFailureKind.NETWORK, AsrFailureKind.of(java.net.UnknownHostException("dns")))
        assertEquals(AsrFailureKind.NO_AUDIO_TRACK, AsrFailureKind.of(AsrFailureException(AsrFailureKind.NO_AUDIO_TRACK, "没有音轨")))
        assertEquals(AsrFailureKind.UNKNOWN, AsrFailureKind.of(null))
    }

    @Test
    fun failureKind_classifiesFromMessage() {
        assertEquals(AsrFailureKind.MODEL_MISSING, AsrFailureKind.ofMessage("模型未下载"))
        assertEquals(AsrFailureKind.DECODE_FAILED, AsrFailureKind.ofMessage("解码失败"))
        assertEquals(AsrFailureKind.NO_WRITE_PERMISSION, AsrFailureKind.ofMessage("目标只读"))
        assertEquals(AsrFailureKind.UNKNOWN, AsrFailureKind.ofMessage(""))
        assertEquals(AsrFailureKind.UNKNOWN, AsrFailureKind.ofMessage("说不清"))
        assertTrue(AsrFailureKind.NO_WRITE_PERMISSION.zhText.isNotEmpty())
    }

    @Test
    fun itemState_flags() {
        assertTrue(AsrItemState.RUNNING.isActive)
        assertTrue(AsrItemState.WRITING.isActive)
        assertFalse(AsrItemState.QUEUED.isActive)
        assertTrue(AsrItemState.SUCCEEDED.isTerminal)
        assertFalse(AsrItemState.INTERRUPTED.isTerminal)
        assertTrue(AsrItemState.INTERRUPTED.isRetryable)
        assertFalse(AsrItemState.SUCCEEDED.isRetryable)
    }
}
