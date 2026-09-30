package io.github.gua123.mediagate.data.storage.local

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * 把外部传入的路径规范成「树内相对路径」（内部工具，File 与 SAF 双模式共用）。
 *
 * 规则：反斜杠转正斜杠、去掉首尾与重复的 /、去掉 . 段；根目录得到空串。
 * 出现 .. 段直接抛 [StorageException.AccessDenied]：本地后端不允许越出根目录（R12 安全边界）。
 */
internal fun normalizeRelativePath(path: String): String {
    val segments = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) {
        throw StorageException.AccessDenied("路径不允许越出根目录：$path")
    }
    return segments.joinToString("/")
}

/** 拼接树内相对路径（[base] 为空表示根）。 */
internal fun joinPath(base: String, name: String): String = if (base.isEmpty()) name else "$base/$name"

/** 目录优先、再按名称（大小写不敏感）排序：文件浏览器与缩略图列表都依赖这个稳定顺序。 */
internal val DIRECTORY_FIRST: Comparator<RemoteEntry> =
    compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** 按 [page] 截取分页；page 为 null 或 limit <= 0 表示不限制。 */
internal fun paginate(entries: List<RemoteEntry>, page: Page?): List<RemoteEntry> {
    if (page == null) return entries
    val from = page.offset.coerceAtMost(entries.size)
    val rest = entries.subList(from, entries.size)
    return if (page.limit <= 0) rest.toList() else rest.take(page.limit)
}

/**
 * 计算一段读请求的实际可读长度（0 表示起点已在末尾之后）。
 *
 * @param total 数据总长度（-1 未知）。
 * @param offset 起始偏移。
 * @param length 请求长度（-1 表示读到末尾）。
 */
internal fun effectiveLength(total: Long, offset: Long, length: Long): Long {
    if (length < 0) return if (total < 0) -1 else (total - offset).coerceAtLeast(0)
    if (total < 0) return length
    return minOf(length, (total - offset).coerceAtLeast(0))
}
