package io.github.gua123.mediagate.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.time.ZoneOffset

/**
 * 浏览页纯逻辑单测（R2 / R5 / R16）。
 *
 * 覆盖「可纯 JVM 测试」的四块：大小格式化、路径与面包屑、按 [MediaKind] 分组/过滤、UiState 归约。
 * 不涉及任何 Android API（组合函数部分只保证编译通过，需真机验证）。
 */
class BrowserLogicTest {

    // ------------------------------------------------------------ 大小格式化

    @Test
    fun `大小未知用占位符而不是 0`() {
        assertEquals(BrowserFormat.UNKNOWN, BrowserFormat.size(-1))
        assertEquals(BrowserFormat.UNKNOWN, BrowserFormat.size(Long.MIN_VALUE))
    }

    @Test
    fun `字节级大小不加小数`() {
        assertEquals("0 B", BrowserFormat.size(0))
        assertEquals("512 B", BrowserFormat.size(512))
        assertEquals("1023 B", BrowserFormat.size(1023))
    }

    @Test
    fun `按 1024 进制换算并保留一位小数`() {
        assertEquals("1.0 KB", BrowserFormat.size(1024))
        assertEquals("1.5 KB", BrowserFormat.size(1536))
        assertEquals("1.0 MB", BrowserFormat.size(1024L * 1024))
        assertEquals("12.5 MB", BrowserFormat.size((12.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", BrowserFormat.size(1024L * 1024 * 1024))
        assertEquals("2.0 TB", BrowserFormat.size(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun `修改时间未知用占位符`() {
        assertEquals(BrowserFormat.UNKNOWN, BrowserFormat.dateTime(0))
        assertEquals(BrowserFormat.UNKNOWN, BrowserFormat.dateTime(-1))
    }

    @Test
    fun `修改时间按本地时区格式化到分钟`() {
        // 2026-09-30T22:50:00Z
        val epoch = 1_790_808_600_000L
        assertEquals("2026-09-30 22:50", BrowserFormat.dateTime(epoch, ZoneOffset.UTC))
        // 同一时刻换到东八区：跨到次日 06:50
        assertEquals("2026-10-01 06:50", BrowserFormat.dateTime(epoch, ZoneOffset.ofHours(8)))
    }

    // ------------------------------------------------------------ 路径与面包屑

    @Test
    fun `路径归一化去掉多余斜杠与空段`() {
        assertEquals("", BrowserPaths.normalize(""))
        assertEquals("", BrowserPaths.normalize("/"))
        assertEquals("Movies", BrowserPaths.normalize("/Movies/"))
        assertEquals("Movies/2026", BrowserPaths.normalize(" //Movies//2026/ "))
        assertEquals("a/b", BrowserPaths.normalize("a/./b"))
    }

    @Test
    fun `面包屑首段是根目录并逐级累加`() {
        val crumbs = BrowserPaths.crumbs("内部存储", "Movies/2026")
        assertEquals(listOf("内部存储", "Movies", "2026"), crumbs.map { it.name })
        assertEquals(listOf("", "Movies", "Movies/2026"), crumbs.map { it.path })
    }

    @Test
    fun `根目录只有一个面包屑`() {
        val crumbs = BrowserPaths.crumbs("内部存储", "")
        assertEquals(1, crumbs.size)
        assertEquals("", crumbs[0].path)
    }

    @Test
    fun `返回上级在根目录时为 null`() {
        assertNull(BrowserPaths.parentOf(""))
        assertNull(BrowserPaths.parentOf("/"))
        assertEquals("", BrowserPaths.parentOf("Movies"))
        assertEquals("Movies", BrowserPaths.parentOf("Movies/2026"))
    }

    @Test
    fun `拼子路径`() {
        assertEquals("Movies/2026", BrowserPaths.join("Movies", "2026"))
        assertEquals("a", BrowserPaths.join("", "a"))
        assertEquals("a", BrowserPaths.join("/a/", ""))
    }

    @Test
    fun `取路径最后一段`() {
        assertEquals("", BrowserPaths.nameOf(""))
        assertEquals("2026", BrowserPaths.nameOf("Movies/2026/"))
    }

    // ------------------------------------------------------------ 过滤与分组

    private val mixed = listOf(
        entry("Movies", isDirectory = true),
        entry("a.mp4"),
        entry("b.mkv"),
        entry("c.mp3"),
        entry("d.jpg"),
        entry("e.srt"),
        entry("f.txt"),
    )

    @Test
    fun `不过滤时原样透传后端顺序`() {
        assertEquals(mixed.map { it.name }, BrowserFilters.apply(mixed, null).map { it.name })
    }

    @Test
    fun `按类型过滤时目录始终保留`() {
        val videos = BrowserFilters.apply(mixed, MediaKind.VIDEO)
        assertEquals(listOf("Movies", "a.mp4", "b.mkv"), videos.map { it.name })

        val images = BrowserFilters.apply(mixed, MediaKind.IMAGE)
        assertEquals(listOf("Movies", "d.jpg"), images.map { it.name })
    }

    @Test
    fun `按类型统计不包括目录`() {
        val counts = BrowserFilters.countByKind(mixed)
        assertEquals(2, counts[MediaKind.VIDEO])
        assertEquals(1, counts[MediaKind.AUDIO])
        assertEquals(1, counts[MediaKind.IMAGE])
        assertEquals(1, counts[MediaKind.SUBTITLE])
        assertEquals(1, counts[MediaKind.OTHER])
    }

    // ------------------------------------------------------------ UiState 归约

    @Test
    fun `初始状态是未选根目录`() {
        val state = BrowserUiState()
        assertEquals(BrowserStatus.NO_ROOT, state.status)
        // 面包屑这时只有「根」一段（根名来自 :app，未选根目录时是空串）
        assertEquals(1, state.crumbs.size)
        assertEquals("", state.crumbs[0].path)
        assertTrue(state.visibleEntries.isEmpty())
    }

    @Test
    fun `开始加载会清空旧内容并记住路径与根名`() {
        val state = BrowserUiState(status = BrowserStatus.CONTENT, entries = mixed, visibleEntries = mixed)
            .reduce(BrowserEvent.LoadStarted(path = "/Movies/", rootLabel = "内部存储"))
        assertEquals(BrowserStatus.LOADING, state.status)
        assertEquals("Movies", state.path)
        assertEquals("内部存储", state.rootLabel)
        assertTrue(state.entries.isEmpty())
        assertTrue(state.visibleEntries.isEmpty())
        assertEquals("Movies", state.title)
        assertTrue(state.canGoUp)
    }

    @Test
    fun `下拉刷新保留旧内容`() {
        val loaded = BrowserUiState().reduce(BrowserEvent.LoadSucceeded(mixed))
        val refreshing = loaded.reduce(
            BrowserEvent.LoadStarted(path = "", rootLabel = "内部存储", refreshing = true),
        )
        assertEquals(BrowserStatus.LOADING, refreshing.status)
        assertTrue(refreshing.refreshing)
        assertEquals(mixed.size, refreshing.visibleEntries.size)
    }

    @Test
    fun `加载成功按内容是否为空分流并套用过滤`() {
        val empty = BrowserUiState(filter = MediaKind.VIDEO).reduce(BrowserEvent.LoadSucceeded(emptyList()))
        assertEquals(BrowserStatus.EMPTY, empty.status)

        val content = empty.reduce(BrowserEvent.LoadSucceeded(mixed))
        assertEquals(BrowserStatus.CONTENT, content.status)
        assertEquals(listOf("Movies", "a.mp4", "b.mkv"), content.visibleEntries.map { it.name })
        assertEquals(mixed.size, content.entries.size)
        assertTrue(content.filteredToEmpty.not())
    }

    @Test
    fun `过滤后为空时给专门的提示判断`() {
        val onlyAudio = listOf(entry("c.mp3"))
        val state = BrowserUiState(filter = MediaKind.IMAGE).reduce(BrowserEvent.LoadSucceeded(onlyAudio))
        assertEquals(BrowserStatus.CONTENT, state.status)
        assertTrue(state.visibleEntries.isEmpty())
        assertTrue(state.filteredToEmpty)
    }

    @Test
    fun `切换与清除过滤只重算展示列表`() {
        val loaded = BrowserUiState().reduce(BrowserEvent.LoadSucceeded(mixed))
        val audio = loaded.reduce(BrowserEvent.FilterChanged(MediaKind.AUDIO))
        assertEquals(listOf("Movies", "c.mp3"), audio.visibleEntries.map { it.name })

        val cleared = audio.reduce(BrowserEvent.FilterChanged(null))
        assertNull(cleared.filter)
        assertEquals(mixed.size, cleared.visibleEntries.size)
    }

    @Test
    fun `加载失败进入错误态并丢弃内容`() {
        val state = BrowserUiState(status = BrowserStatus.CONTENT, entries = mixed, visibleEntries = mixed)
            .reduce(BrowserEvent.LoadFailed(BrowserErrorKind.NOT_FOUND, "目录不存在：X"))
        assertEquals(BrowserStatus.ERROR, state.status)
        assertEquals(BrowserErrorKind.NOT_FOUND, state.errorKind)
        assertEquals("目录不存在：X", state.errorDetail)
        assertTrue(state.visibleEntries.isEmpty())
    }

    @Test
    fun `未选根目录回到引导态并清掉错误`() {
        val state = BrowserUiState(status = BrowserStatus.ERROR, errorKind = BrowserErrorKind.ACCESS_DENIED)
            .reduce(BrowserEvent.RootMissing)
        assertEquals(BrowserStatus.NO_ROOT, state.status)
        assertNull(state.errorKind)
    }

    private fun entry(
        name: String,
        isDirectory: Boolean = false,
        size: Long = 1024L,
        mtime: Long = 0L,
    ) = RemoteEntry(
        name = name,
        path = name,
        isDirectory = isDirectory,
        size = if (isDirectory) -1L else size,
        mtime = mtime,
    )
}
