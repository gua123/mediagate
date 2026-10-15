package io.github.gua123.mediagate.feature.browser

import io.github.gua123.mediagate.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** 封面空闲预热的策略（2026-10-14 用户提出）。 */
class CoverWarmupPolicyTest {

    private fun entry(name: String, directory: Boolean = false) = RemoteEntry(
        path = name,
        name = name,
        isDirectory = directory,
        size = 1024L,
        mtime = 0L,
    )

    @Test
    fun 只预热视频_跳过目录图片音频() {
        val entries = listOf(
            entry("电影.mp4"),
            entry("子目录", directory = true),
            entry("照片.jpg"),
            entry("歌曲.mp3"),
            entry("剧集.mkv"),
        )
        val targets = CoverWarmupPolicy.targets(entries)
        assertEquals(listOf("电影.mp4", "剧集.mkv"), targets.map { it.name })
    }

    @Test
    fun 一个目录最多预热一批_其余等用户滚到再说() {
        val entries = (1..30).map { entry("v$it.mp4") }
        assertEquals(CoverWarmupPolicy.DEFAULT_BATCH, CoverWarmupPolicy.targets(entries).size)
        assertEquals(3, CoverWarmupPolicy.targets(entries, limit = 3).size)
        assertEquals(0, CoverWarmupPolicy.targets(entries, limit = 0).size)
    }

    @Test
    fun 按并发数分批_默认两个一批() {
        val targets = (1..5).map { entry("v$it.mp4") }
        val batches = CoverWarmupPolicy.batches(targets)
        assertEquals(listOf(2, 2, 1), batches.map { it.size })
        assertEquals(5, CoverWarmupPolicy.batches(targets, concurrency = 1).size)
        assertEquals(1, CoverWarmupPolicy.batches(targets, concurrency = 0).first().size)
        assertEquals(emptyList<List<RemoteEntry>>(), CoverWarmupPolicy.batches(emptyList()))
    }
}
