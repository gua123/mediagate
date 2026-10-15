package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.ui.cacheQueueSummary
import io.github.gua123.mediagate.ui.cacheTaskLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **手动缓存队列**（2026-10-15 用户要求：「增加手动批量多个文件的缓存方式，
 * 可以让我不打开对应文件夹就可以缓存视频」）。
 *
 * 这里全部用注入的假实现（已知大小 / 假下载），所以**不需要网络也不需要真缓存**：
 * 验证的是队列自己的契约——顺序、去重、暂停、取消、失败继续、让路、固定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheQueueControllerTest {

    private val playback = MutableStateFlow(false)

    /** 造一个控制器：文件大小 = 段数 × 段大小（默认 3 段 × 100 B）。 */
    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        segments: Int = 3,
        segmentBytes: Long = 100L,
        failOn: String? = null,
        pinned: MutableList<String> = mutableListOf(),
        downloads: MutableList<String> = mutableListOf(),
    ): CacheQueueController {
        val total = segments * segmentBytes
        return CacheQueueController(
            scope = scope,
            segmentBytes = { segmentBytes },
            knownSize = { total },
            cachedBytesOf = { path -> downloads.count { it == path } * segmentBytes },
            setPinned = { path, isPinned -> if (isPinned) pinned += path else pinned.remove(path) },
            downloadSegment = { path, _, _ ->
                if (path == failOn) throw IllegalStateException("网络断了")
                downloads += path
            },
            playbackActive = playback,
        )
    }

    @Test
    fun 批量入队后按顺序下完并固定() = runTest {
        val downloads = mutableListOf<String>()
        val pinned = mutableListOf<String>()
        val queue = controller(this, pinned = pinned, downloads = downloads)
        queue.enqueue(listOf("a.mp4" to "A", "b.mp4" to "B"))
        advanceUntilIdle()
        assertEquals("按顺序下完两个文件", 6, downloads.size)
        assertEquals(listOf("a.mp4", "a.mp4", "a.mp4", "b.mp4", "b.mp4", "b.mp4"), downloads)
        assertEquals(listOf("a.mp4", "b.mp4"), pinned)
        assertEquals(2, queue.state.value.done)
        assertEquals(0, queue.state.value.pending)
    }

    @Test
    fun 重复入队不会排两遍() = runTest {
        val queue = controller(this)
        queue.enqueue(listOf("a.mp4" to "A"))
        queue.enqueue(listOf("a.mp4" to "A"))
        advanceUntilIdle()
        assertEquals("同一个路径只留一条", 1, queue.state.value.tasks.size)
    }

    @Test
    fun 失败记原因并继续下一个() = runTest {
        val queue = controller(this, failOn = "bad.mp4")
        queue.enqueue(listOf("bad.mp4" to "坏的", "good.mp4" to "好的"))
        advanceUntilIdle()
        val bad = queue.state.value.tasks.first { it.path == "bad.mp4" }
        assertEquals(CacheTaskStatus.FAILED, bad.status)
        assertEquals("网络断了", bad.error)
        assertEquals("前一个失败不该拖住后一个", 1, queue.state.value.done)
    }

    @Test
    fun 播放中让路不下新段() = runTest {
        playback.value = true
        val downloads = mutableListOf<String>()
        val queue = controller(this, downloads = downloads)
        queue.enqueue(listOf("a.mp4" to "A"))
        // ⚠️ 这里**不能** advanceUntilIdle：让路是"每 2 秒看一眼播放状态"的循环，
        // 虚拟时间会被无限推进（测试挂住）。只推进有限几步即可。
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("播放时不该下任何段", downloads.isEmpty())
        assertEquals(CacheTaskStatus.RUNNING, queue.state.value.tasks.single().status)
        // 让路期间暂停 ⇒ 收工（否则 worker 会一直在那里等，测试结束时留下活跃协程）
        queue.pause()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(CacheTaskStatus.QUEUED, queue.state.value.tasks.single().status)
    }

    @Test
    fun 暂停与继续() = runTest {
        val downloads = mutableListOf<String>()
        val queue = controller(this, downloads = downloads)
        queue.pause()
        queue.enqueue(listOf("a.mp4" to "A"))
        advanceUntilIdle()
        assertTrue("暂停时不下", downloads.isEmpty())
        assertTrue(queue.state.value.paused)
        queue.resume()
        advanceUntilIdle()
        assertEquals(3, downloads.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun 取消任务后不再继续下它() = runTest {
        playback.value = true
        val downloads = mutableListOf<String>()
        val queue = controller(this, downloads = downloads)
        queue.enqueue(listOf("a.mp4" to "A"))
        runCurrent()
        queue.cancel("a.mp4")
        playback.value = false
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("取消之后一段都不该下", downloads.isEmpty())
        assertEquals(CacheTaskStatus.CANCELLED, queue.state.value.tasks.single().status)
        assertEquals(0, queue.state.value.pending)
    }

    @Test
    fun 已整份缓存过的直接算完成() = runTest {
        val downloads = mutableListOf<String>()
        val queue = CacheQueueController(
            scope = this,
            segmentBytes = { 100L },
            knownSize = { 300L },
            cachedBytesOf = { 300L },
            setPinned = { _, _ -> },
            downloadSegment = { path, _, _ -> downloads += path },
            playbackActive = playback,
        )
        queue.enqueue(listOf("a.mp4" to "A"))
        advanceUntilIdle()
        assertTrue("已经全在本地，不该再下", downloads.isEmpty())
        assertEquals(CacheTaskStatus.DONE, queue.state.value.tasks.single().status)
    }

    @Test
    fun 清掉已结束后只剩未完成的() = runTest {
        val queue = controller(this)
        queue.enqueue(listOf("a.mp4" to "A"))
        advanceUntilIdle()
        queue.clearFinished()
        assertTrue(queue.state.value.tasks.isEmpty())
    }

    @Test
    fun 进度与文案() {
        val task = CacheTask(
            path = "a.mp4",
            name = "A",
            status = CacheTaskStatus.RUNNING,
            totalBytes = 100L * 1024 * 1024,
            cachedBytes = 25L * 1024 * 1024,
        )
        assertEquals(0.25f, task.progress!!, 0.001f)
        assertTrue(cacheTaskLine(task.totalBytes, task.cachedBytes, task.progress).contains("25%"))
        assertNull("总大小未知时不给百分比", CacheTask(path = "b", name = "B", totalBytes = null).progress)
        assertEquals("缓存队列是空的", cacheQueueSummary(0, 0, 0, paused = false))
        val summary = cacheQueueSummary(pending = 2, done = 1, failed = 1, paused = true)
        assertTrue(summary, summary.contains("已暂停") && summary.contains("失败 1"))
    }
}
