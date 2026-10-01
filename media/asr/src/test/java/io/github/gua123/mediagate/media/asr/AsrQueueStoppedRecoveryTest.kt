package io.github.gua123.mediagate.media.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 队列被「取消全部」停掉（STOPPED）之后必须有出路（**2026-10-03 真机截图**：勾了 1 项但按钮是灰的）。
 *
 * 三条出路都要能把它带回可运行状态：继续（resume）、重新入队（enqueue）、重试失败项（retry）。
 */
class AsrQueueStoppedRecoveryTest {

    private fun item(id: Long, state: AsrItemState) = AsrItem(
        id = id,
        path = "/movies/a" + id + ".mp4",
        name = "a" + id + ".mp4",
        state = state,
    )

    private fun queue(vararg items: AsrItem) = AsrQueueSnapshot(items = items.toList(), state = AsrQueueState.STOPPED)

    @Test
    fun `继续可以把已停止的队列复活成空转`() {
        val next = AsrQueue.resume(queue(item(1, AsrItemState.CANCELLED)))
        assertEquals(AsrQueueState.IDLE, next.state)
    }

    @Test
    fun `重新入队会把已停止的队列复活（并保留原任务）`() {
        val next = AsrQueue.enqueue(queue(item(1, AsrItemState.CANCELLED)), listOf(item(2, AsrItemState.QUEUED)))
        assertEquals(AsrQueueState.IDLE, next.state)
        assertEquals(2, next.items.size)
        assertEquals(AsrItemState.QUEUED, next.item(2)?.state)
    }

    @Test
    fun `重试失败项也会复活已停止的队列`() {
        val next = AsrQueue.retry(queue(item(1, AsrItemState.FAILED)), 1)
        assertEquals(AsrItemState.QUEUED, next.item(1)?.state)
        assertEquals(AsrQueueState.IDLE, next.state)
    }

    @Test
    fun `复活之后就能真的开跑`() {
        val revived = AsrQueue.resume(queue(item(1, AsrItemState.QUEUED)))
        val started = AsrQueue.startNext(revived)
        assertEquals(AsrQueueState.RUNNING, started.queue.state)
        assertEquals(listOf(1L), started.started.map { it.id })
    }
}
