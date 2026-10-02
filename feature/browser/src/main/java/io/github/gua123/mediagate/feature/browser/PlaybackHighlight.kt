package io.github.gua123.mediagate.feature.browser

/**
 * 浏览页里"上次播放"的高亮判定（**2026-10-03 用户要求**：「最后一次播放的视频和文件夹变一个颜色，
 * 并且这个变色需要向上贯穿到连接的根目录」）。
 *
 * 语义：
 * - 那个**视频文件本身**要变色（[HighlightKind.LAST_PLAYED]）；
 * - 它所在的文件夹、以及从根到它的**每一级祖先目录**都要变色（[HighlightKind.ON_PATH]）；
 * - 其它条目不变色。
 */
enum class HighlightKind { NONE, LAST_PLAYED, ON_PATH }

object PlaybackHighlight {

    /**
     * 判断列表项 [entryPath] 该怎么显示。
     *
     * @param entryPath 列表项在**后端内的路径**（与 [lastPlayedPath] 同一口径）。
     * @param isDirectory 这一项是不是目录。
     * @param lastPlayedPath 最后一次播放的文件路径；null/blank ＝没有记录，全都变色。
     */
    fun kindOf(entryPath: String, isDirectory: Boolean, lastPlayedPath: String?): HighlightKind {
        val last = lastPlayedPath?.takeIf { it.isNotBlank() } ?: return HighlightKind.NONE
        if (!isDirectory) return if (entryPath == last) HighlightKind.LAST_PLAYED else HighlightKind.NONE
        // 目录：是不是"通往那个文件"路上的一级（含它自己所在的那一级）
        return if (isAncestor(entryPath, last)) HighlightKind.ON_PATH else HighlightKind.NONE
    }

    /**
     * [dirPath] 是不是 [filePath] 的祖先目录（含"文件所在目录"本身）。
     *
     * 逐字符比对前缀 + 边界斜杠，避免 `/a/bc` 被误判成 `/a/b` 的子目录。
     */
    fun isAncestor(dirPath: String, filePath: String): Boolean {
        val dir = normalize(dirPath)
        val file = normalize(filePath)
        if (dir.isEmpty()) return true
        if (!file.startsWith(dir)) return false
        return file.length > dir.length && file[dir.length] == '/'
    }

    /** 去掉首尾多余的斜杠（根目录统一成空串）。 */
    private fun normalize(path: String): String = path.trim().trim('/')
}
