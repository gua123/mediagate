package io.github.gua123.mediagate.feature.browser

import io.github.gua123.mediagate.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表排序（**2026-10-03 用户要求**：「增加文件文件夹排序功能」）。
 *
 * 三条口径要钉住：**目录永远在前**、未知值（大小 -1 / 时间 0）排最后、同键时按名称兜底（结果稳定）。
 */
class EntrySorterTest {

    private val dirB = entry("Beta", directory = true)
    private val dirA = entry("alpha", directory = true)
    private val big = entry("z.mp4", size = 9_000L, mtime = 300L)
    private val small = entry("a.mp4", size = 100L, mtime = 100L)
    private val unknown = entry("m.bin", size = -1L, mtime = 0L)
    private val other = entry("c.txt", size = 500L, mtime = 200L)

    @Test
    fun `名称升序：目录在前且大小写不敏感`() {
        val sorted = EntrySorter.sort(listOf(big, dirB, small, dirA, unknown), EntrySort())

        assertEquals(listOf("alpha", "Beta", "a.mp4", "m.bin", "z.mp4"), sorted.map { it.name })
    }

    @Test
    fun `名称降序：组内反转但目录仍在最前`() {
        val sorted = EntrySorter.sort(listOf(big, dirB, small, dirA), EntrySort(ascending = false))

        assertEquals(listOf("Beta", "alpha", "z.mp4", "a.mp4"), sorted.map { it.name })
    }

    @Test
    fun `按大小：未知大小排最后`() {
        val asc = EntrySorter.sort(listOf(big, small, unknown, other), EntrySort(mode = EntrySortMode.SIZE))
        assertEquals(listOf("a.mp4", "c.txt", "z.mp4", "m.bin"), asc.map { it.name })

        val desc = EntrySorter.sort(listOf(big, small, unknown, other), EntrySort(mode = EntrySortMode.SIZE, ascending = false))
        assertEquals("降序时未知大小仍然排最后", listOf("z.mp4", "c.txt", "a.mp4", "m.bin"), desc.map { it.name })
    }

    @Test
    fun `按时间：未知时间排最后`() {
        val asc = EntrySorter.sort(listOf(big, small, unknown, other), EntrySort(mode = EntrySortMode.TIME))
        assertEquals(listOf("a.mp4", "c.txt", "z.mp4", "m.bin"), asc.map { it.name })
    }

    @Test
    fun `按类型：扩展名排序，同扩展名按名称`() {
        val mp4b = entry("b.mp4", size = 1L)
        val sorted = EntrySorter.sort(listOf(other, big, small, mp4b), EntrySort(mode = EntrySortMode.TYPE))

        assertEquals(listOf("a.mp4", "b.mp4", "z.mp4", "c.txt"), sorted.map { it.name })
    }

    @Test
    fun `切换升降序`() {
        val sort = EntrySort(mode = EntrySortMode.NAME)
        assertEquals(false, sort.toggled().ascending)
        assertEquals("名称 ↓", sort.toggled().label)
        assertEquals("名称 ↑", sort.label)
    }

    @Test
    fun 用户排序覆盖模块内的名称排序_播放队列与列表一致() {
        // 2026-10-03 用户要求「播放时的列表也需要按照新的排序」：
        // 模块内"按名称排"只是兜底，:app 会在过滤之后再按用户设置排一次 ⇒ 这里钉住那次排序的语义。
        val entries = listOf(
            entry("b.mp4", size = 100L),
            entry("a.mp4", size = 900L),
            entry("c.mp4", size = 500L),
        )
        val bySizeDesc = EntrySorter.sort(entries, EntrySort(mode = EntrySortMode.SIZE, ascending = false))
        assertEquals(listOf("a.mp4", "c.mp4", "b.mp4"), bySizeDesc.map { it.name })
        val bySizeAsc = EntrySorter.sort(entries, EntrySort(mode = EntrySortMode.SIZE, ascending = true))
        assertEquals(listOf("b.mp4", "c.mp4", "a.mp4"), bySizeAsc.map { it.name })
    }

    private fun entry(name: String, directory: Boolean = false, size: Long = 1L, mtime: Long = 1L) =
        RemoteEntry(name = name, path = "/dir/" + name, isDirectory = directory, size = size, mtime = mtime)
}
