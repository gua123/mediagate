package io.github.gua123.mediagate.feature.browser

import io.github.gua123.mediagate.core.model.RemoteEntry

/** 列表排序方式（**2026-10-03 用户要求**：「增加文件文件夹排序功能」）。 */
enum class EntrySortMode(val zhText: String) {

    /** 按名称（大小写不敏感）。 */
    NAME("名称"),

    /** 按大小（未知大小排最后）。 */
    SIZE("大小"),

    /** 按修改时间（未知时间排最后）。 */
    TIME("修改时间"),

    /** 按类型（扩展名，其次名称）。 */
    TYPE("类型"),
}

/**
 * 排序设置：方式 + 升降序。
 *
 * 口径：**目录永远在前**（`目录优先` 是浏览器的既有约定，排序不该把它翻掉），
 * 组内再按 [mode] 排；未知值（大小 -1 / 时间 0）一律排到最后，不参与"小的在前"。
 */
data class EntrySort(
    val mode: EntrySortMode = EntrySortMode.NAME,
    val ascending: Boolean = true,
) {

    /** 切换升降序（界面点"升降"时用）。 */
    fun toggled(): EntrySort = copy(ascending = !ascending)

    /** 展示用一句话。 */
    val label: String get() = mode.zhText + (if (ascending) " ↑" else " ↓")
}

/** 目录项排序（**纯函数**，JVM 单测覆盖每种方式）。 */
object EntrySorter {

    /** 按 [sort] 排好序的新列表（不改原列表；稳定）。 */
    fun sort(entries: List<RemoteEntry>, sort: EntrySort): List<RemoteEntry> {
        val comparator = comparatorFor(sort)
        val directories = entries.filter { it.isDirectory }.sortedWith(comparator)
        val files = entries.filterNot { it.isDirectory }.sortedWith(comparator)
        return directories + files
    }

    private fun comparatorFor(sort: EntrySort): Comparator<RemoteEntry> {
        val value = when (sort.mode) {
            EntrySortMode.NAME -> compareBy<RemoteEntry> { it.name.lowercase() }
            EntrySortMode.SIZE -> compareBy { it.size }
            EntrySortMode.TIME -> compareBy { it.mtime }
            EntrySortMode.TYPE -> compareBy { extensionOf(it.name) }
        }
        // 升降序只作用于"有值的项"：**未知值（大小 -1 / 时间 0）永远排最后**，
        // 不能靠 MAX_VALUE 占位——那样降序一翻转它就跑最前面去了（单测踩过）。
        val directed = if (sort.ascending) value else value.reversed()
        val unknownLast = compareBy<RemoteEntry> { if (isUnknown(it, sort.mode)) 1 else 0 }
        // 同键时用名称兜底，保证结果稳定可预期（同名不同后缀也排得整齐）
        return unknownLast.then(directed).thenBy { it.name.lowercase() }
    }

    /** 该条目在这个维度上"没有值"（大小未知 / 时间未知）。 */
    private fun isUnknown(entry: RemoteEntry, mode: EntrySortMode): Boolean = when (mode) {
        EntrySortMode.SIZE -> entry.size < 0L
        EntrySortMode.TIME -> entry.mtime <= 0L
        else -> false
    }

    /** 扩展名（小写，无点）；没有扩展名给空串（排最前，与"名称以点开头"一致）。 */
    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
