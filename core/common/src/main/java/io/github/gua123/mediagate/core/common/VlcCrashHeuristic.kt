package io.github.gua123.mediagate.core.common

/**
 * 从面包屑判断「上次切到 LibVLC 时进程是不是被带走了」（**2026-10-03 真机实测的判据**）。
 *
 * 真机面包屑长这样（用户 07:48 复现）：
 * ```
 * 10-02 07:47:51.862 开始播放：…#Akari 2024.11.9.mp4（内核 Media3）
 * 10-02 07:48:01.437 切换内核：→ LibVLC（已释放旧内核）
 * 10-02 07:48:03.320 应用启动（versionName 0.1.19）      ← 进程没了，用户重开
 * ```
 * 也就是：**打了"切换内核：→ LibVLC"，却始终没等到"新内核已创建"**，而它后面又出现了新的"应用启动"。
 * 这说明进程死在 `createEngine(VLC)` 里面（原生崩溃，Java 处理器抓不到）。
 *
 * 判据只依赖文本，所以能在 JVM 单测里穷举（见 VlcCrashHeuristicTest）。
 */
object VlcCrashHeuristic {

    private const val SWITCH_TO_VLC = "切换内核：→ LibVLC"
    private const val ENGINE_CREATED = "新内核已创建"
    private const val APP_STARTED = "应用启动"

    /**
     * @param breadcrumbs 面包屑（旧 → 新）。
     * @return true = 最后一次切 LibVLC 看起来把进程带走了（没有"新内核已创建"，但之后有"应用启动"）。
     */
    fun vlcSwitchLooksCrashed(breadcrumbs: List<String>): Boolean {
        val lastSwitch = breadcrumbs.indexOfLast { it.contains(SWITCH_TO_VLC) }
        if (lastSwitch < 0) return false
        val after = breadcrumbs.drop(lastSwitch + 1)
        val created = after.any { it.contains(ENGINE_CREATED) }
        val restarted = after.any { it.contains(APP_STARTED) }
        return !created && restarted
    }
}
