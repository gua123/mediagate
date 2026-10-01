package io.github.gua123.mediagate.feature.browser

/**
 * 面包屑的一段（R2 目录浏览）。
 *
 * @property name 展示名（根那段是根目录名，其余是目录名）。
 * @property path 点它时跳转的相对路径；根为 `""`。
 */
data class BrowserCrumb(val name: String, val path: String)

/**
 * 路径拼装 / 面包屑（纯函数，可直接 JVM 单测）。
 *
 * 路径口径与 [io.github.gua123.mediagate.data.storage.api.StorageBackend] 一致：**相对根目录的
 * POSIX 风格路径**，空串表示根，不以 `/` 开头也不以 `/` 结尾。
 */
object BrowserPaths {

    /** 归一化：去掉首尾空白与多余斜杠，丢掉空段与 `.` 段。 */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        return trimmed.split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
    }

    /**
     * 面包屑：第一段固定是根目录（[rootLabel]），随后逐级累加。
     *
     * `crumbs("内部存储", "Movies/2026")` → `[内部存储/"", Movies/"Movies", 2026/"Movies/2026"]`
     */
    fun crumbs(rootLabel: String, path: String): List<BrowserCrumb> {
        val normalized = normalize(path)
        val out = ArrayList<BrowserCrumb>(4)
        out += BrowserCrumb(rootLabel, "")
        if (normalized.isEmpty()) return out
        var acc = ""
        for (segment in normalized.split('/')) {
            acc = if (acc.isEmpty()) segment else "$acc/$segment"
            out += BrowserCrumb(segment, acc)
        }
        return out
    }

    /**
     * 上一级路径。
     *
     * @return 根目录的上一级返回 null（用 null 表示「不能再往上」，不用空串冒充）。
     */
    fun parentOf(path: String): String? {
        val normalized = normalize(path)
        if (normalized.isEmpty()) return null
        return normalized.substringBeforeLast('/', "")
    }

    /** 拼子路径：`join("Movies", "2026")` → `"Movies/2026"`；`join("", "a")` → `"a"`。 */
    fun join(parent: String, name: String): String {
        val base = normalize(parent)
        val child = normalize(name)
        return when {
            base.isEmpty() -> child
            child.isEmpty() -> base
            else -> "$base/$child"
        }
    }

    /** 路径最后一段（当前目录名）；根目录返回空串。 */
    fun nameOf(path: String): String = normalize(path).substringAfterLast('/')
}
