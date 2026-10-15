package io.github.gua123.mediagate.app

/**
 * 「预约缓存」选中的结果（2026-10-15 用户要求：「增加手动批量多个文件的缓存方式，
 * 可以让我不打开对应文件夹就可以缓存视频」）。
 *
 * @param entries 要缓存的文件（路径 → 显示名）。
 * @param truncated 文件太多被截断了（界面要如实说"只选了前 N 个"）。
 * @param notVideo 长按的不是视频（界面给一句中文说明，而不是静默什么都不做）。
 */
data class CachePickResult(
    val entries: List<Pair<String, String>>,
    val truncated: Boolean = false,
    val notVideo: Boolean = false,
)
