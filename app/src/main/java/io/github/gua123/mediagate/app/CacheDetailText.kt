package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend

/**
 * **缓存明细**里每条记录的文案（**纯函数**，JVM 单测）。
 *
 * 2026-10-15 用户要求：「关于缓存部分，是否可以增加一个可视化界面，告诉我哪些缓存过，哪些没有」——
 * 界面要回答两件事：**哪些文件缓存过**（有名字就够）、**缓存到什么程度**（多少 MB、几段、占比）。
 * 占比只有在知道文件总大小时才有意义，所以未知时显示「—」而不是瞎算一个百分比。
 */
internal fun cacheEntryLine(entry: SegmentedCacheBackend.CachedFileEntry): String {
    val parts = mutableListOf<String>()
    parts += "已缓存 " + cacheMegabytes(entry.bytes)
    parts += entry.segments.toString() + " 段"
    val percent = entry.coverage?.let { (it * 100).toInt().coerceIn(0, 100) }
    parts += if (percent != null) "占该文件 " + percent + "%" else "总大小未知"
    return parts.joinToString(" · ")
}

/** 字节 → 人类可读（保留一位小数；小于 1 MB 用 KB）。 */
internal fun cacheMegabytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/** 头部那一行：共几个文件、合计多少。 */
internal fun cacheSummaryLine(entries: List<SegmentedCacheBackend.CachedFileEntry>): String {
    if (entries.isEmpty()) return "还没有缓存过任何文件"
    val total = entries.sumOf { it.bytes }
    val unnamed = entries.count { it.path == null }
    val extra = if (unnamed > 0) "（其中 " + unnamed + " 个是旧版本缓存，名称未知）" else ""
    return "已缓存 " + entries.size + " 个文件 · 合计 " + cacheMegabytes(total) + extra
}
