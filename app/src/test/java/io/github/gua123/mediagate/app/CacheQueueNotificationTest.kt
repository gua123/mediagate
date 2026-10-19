package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存下载通知的纯展示逻辑（2026-10-19 用户要求「需要加前台服务」）。
 *
 * 通知文案在真机上只能"看一眼"，所以把三种常见状态（进行中 / 排队 / 暂停）与"没有总量"
 * 都在这里钉死——写错了用户会看到一句莫名其妙的话。
 */
class CacheQueueNotificationTest {

    private fun task(
        path: String,
        status: CacheTaskStatus = CacheTaskStatus.QUEUED,
        total: Long? = 100L * 1024 * 1024,
        cached: Long = 0L,
    ) = CacheTask(
        path = path,
        name = path.substringAfterLast('/'),
        status = status,
        totalBytes = total,
        // 预约缓存只温开头：目标＝min(文件, 12 MB)，与 CacheHeadPlan 同一口径
        targetBytes = CacheHeadPlan.targetBytes(total),
        cachedBytes = cached,
    )

    @Test
    fun 进行中要显示第几个_百分比与文件名() {
        val state = CacheQueueState(
            tasks = listOf(
                task("a.mp4", CacheTaskStatus.DONE, cached = 100L * 1024 * 1024),
                task("b.mp4", CacheTaskStatus.RUNNING, cached = 3L * 1024 * 1024),
                task("c.mp4"),
            ),
        )
        assertEquals("正在缓存视频", CacheQueueNotification.title(state))
        assertEquals("第 2/3 个 · 25% · b.mp4", CacheQueueNotification.text(state))
        assertEquals(25, CacheQueueNotification.progress(state))
        assertTrue(CacheQueueNotification.isBusy(state))
    }

    @Test
    fun 总大小未知时按固定目标算进度() {
        // 大小未知 ⇒ 目标固定为开头 12 MB（CacheHeadPlan 的口径），所以进度仍可算
        val state = CacheQueueState(tasks = listOf(task("a.mp4", CacheTaskStatus.RUNNING, total = null, cached = 3L * 1024 * 1024)))
        assertEquals("第 1/1 个 · 25% · a.mp4", CacheQueueNotification.text(state))
        assertEquals(25, CacheQueueNotification.progress(state))
    }

    @Test
    fun 暂停与排队各有说法() {
        val paused = CacheQueueState(tasks = listOf(task("a.mp4"), task("b.mp4")), paused = true)
        assertEquals("缓存已暂停", CacheQueueNotification.title(paused))
        assertEquals("已暂停 · 待处理 2 个", CacheQueueNotification.text(paused))

        val queued = CacheQueueState(tasks = listOf(task("a.mp4"), task("b.mp4")))
        assertEquals("缓存排队中", CacheQueueNotification.title(queued))
        assertEquals("待处理 2 个", CacheQueueNotification.text(queued))
    }

    @Test
    fun 队列空了就不该再保持前台服务() {
        val done = CacheQueueState(
            tasks = listOf(task("a.mp4", CacheTaskStatus.DONE, cached = 100L * 1024 * 1024)),
        )
        assertEquals("缓存完成", CacheQueueNotification.title(done))
        assertEquals("已完成 1 个", CacheQueueNotification.text(done))
        assertFalse(CacheQueueNotification.isBusy(done))
    }

    @Test
    fun 有失败项时如实说出来() {
        val state = CacheQueueState(
            tasks = listOf(
                task("a.mp4", CacheTaskStatus.DONE, cached = 100L * 1024 * 1024),
                task("b.mp4", CacheTaskStatus.FAILED),
            ),
        )
        assertEquals("已完成 1 个 · 失败 1 个", CacheQueueNotification.text(state))
    }

    @Test
    fun 队列序列化可以原样还原且坏行不炸() {
        val entries = listOf("/a/b c.mp4" to "b c.mp4", "/x/中文名.mp4" to "中文名.mp4")
        val raw = CacheQueueCodec.encode(entries)
        assertEquals("同一个集合编码稳定", raw, CacheQueueCodec.encode(entries))
        assertEquals("按原顺序还原", entries, CacheQueueCodec.decode(raw))
        assertEquals("坏行跳过，不抛异常", emptyList<Pair<String, String>>(), CacheQueueCodec.decode(setOf("没有分隔符", "")))
    }
}
