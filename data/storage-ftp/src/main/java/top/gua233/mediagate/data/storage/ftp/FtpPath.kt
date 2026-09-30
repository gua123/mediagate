package io.github.gua123.mediagate.data.storage.ftp

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * 把外部传入的路径规范成「树内相对路径」（**R2** 口径与 `:data:storage-local` 一致）。
 *
 * 规则：反斜杠转正斜杠、去掉首尾与重复的 /、去掉 . 段；根目录得到空串。
 * 出现 .. 段直接抛 [StorageException.AccessDenied]：不允许越出 [FtpConfig.basePath]（安全边界）。
 */
internal fun normalizeFtpPath(path: String): String {
    val segments = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) {
        throw StorageException.AccessDenied("路径不允许越出根目录：" + path)
    }
    return segments.joinToString("/")
}

/** 拼接树内相对路径（[base] 为空表示根）。 */
internal fun joinFtpPath(base: String, name: String): String = if (base.isEmpty()) name else base + "/" + name

/** 把 [FtpConfig.basePath] 归一化成绝对根路径：以 / 开头、无尾斜杠（根就是 /）。 */
internal fun absoluteFtpRoot(basePath: String): String {
    val relative = normalizeFtpPath(basePath)
    return if (relative.isEmpty()) "/" else "/" + relative
}

/** 树内相对路径 → 远端绝对路径（**R2**：一律用绝对路径，不依赖服务端当前工作目录）。 */
internal fun absoluteFtpPath(rootPath: String, relative: String): String {
    val normalized = normalizeFtpPath(relative)
    if (normalized.isEmpty()) return rootPath
    return if (rootPath == "/") "/" + normalized else rootPath + "/" + normalized
}

/** 取路径末段作为展示名（根路径回退成 /）。 */
internal fun ftpNameOf(relative: String): String =
    if (relative.isEmpty()) "/" else relative.substringAfterLast('/')

/** 父目录的树内相对路径；根目录的父目录还是空串。 */
internal fun ftpParentOf(relative: String): String = relative.substringBeforeLast('/', "")

/** 目录优先、再按名称（大小写不敏感）排序：与 FileStorageBackend 完全同口径（R2 列表稳定性）。 */
internal val FTP_DIRECTORY_FIRST: Comparator<RemoteEntry> =
    compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** 按 [page] 截取分页；page 为 null 或 limit <= 0 表示不限制（plan 4.10）。 */
internal fun paginateFtp(entries: List<RemoteEntry>, page: Page?): List<RemoteEntry> {
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
internal fun effectiveFtpLength(total: Long, offset: Long, length: Long): Long {
    if (length < 0) return if (total < 0) -1 else (total - offset).coerceAtLeast(0)
    if (total < 0) return length
    return minOf(length, (total - offset).coerceAtLeast(0))
}
