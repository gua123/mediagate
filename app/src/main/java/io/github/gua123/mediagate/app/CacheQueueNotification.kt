package io.github.gua123.mediagate.app

/**
 * 缓存下载通知的**纯展示逻辑**（2026-10-19 用户要求「需要加前台服务」）。
 *
 * 为什么把它抽成纯函数：通知文案与进度最容易写错（暂停态、排队态、没有总量时），
 * 而这些在真机上只能靠"看一眼通知栏"验证；抽出来就能在 JVM 单测里逐种状态钉住。
 */
object CacheQueueNotification {

    /** 通知渠道（dataSync 前台服务用；用户可以在系统里单独关掉它）。 */
    const val CHANNEL_ID = "cache_download"

    /** 渠道名（系统设置里显示）。 */
    const val CHANNEL_NAME = "缓存下载"

    /** 通知 id（固定：同一格通知原地更新）。 */
    const val NOTIFICATION_ID = 4201

    /** 通知栏按钮动作。 */
    const val ACTION_PAUSE = "io.github.gua123.mediagate.CACHE_PAUSE"

    /** 通知栏按钮动作。 */
    const val ACTION_RESUME = "io.github.gua123.mediagate.CACHE_RESUME"

    /** 标题：一句话说清现在在干什么。 */
    fun title(state: CacheQueueState): String = when {
        state.paused && state.pending > 0 -> "缓存已暂停"
        state.running != null -> "正在缓存视频"
        state.pending > 0 -> "缓存排队中"
        else -> "缓存完成"
    }

    /**
     * 正文：
     * - 进行中：「第 n/N 个 · 45% · a.mp4」；
     * - 排队中：「待处理 N 个」；
     * - 暂停：「已暂停 · 待处理 N 个」。
     */
    fun text(state: CacheQueueState): String {
        val running = state.running
        if (running != null) {
            // 计数口径：整个队列里没被取消的条数（已完成的也算进总数——用户读起来是「第 2/3 个」）
            val active = state.tasks.filter { it.status != CacheTaskStatus.CANCELLED }
            val total = active.size
            val index = active.indexOf(running) + 1
            val percent = running.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
            val prefix = "第 " + index + "/" + total.coerceAtLeast(index) + " 个"
            return if (percent != null) prefix + " · " + percent + "% · " + running.name else prefix + " · " + running.name
        }
        if (state.paused && state.pending > 0) return "已暂停 · 待处理 " + state.pending + " 个"
        if (state.pending > 0) return "待处理 " + state.pending + " 个"
        return "已完成 " + state.done + " 个" + if (state.failed > 0) " · 失败 " + state.failed + " 个" else ""
    }

    /** 进度条百分比；null = 用不确定进度（总大小未知）。 */
    fun progress(state: CacheQueueState): Int? =
        state.running?.progress?.let { (it * 100).toInt().coerceIn(0, 100) }

    /** 是否是"还在忙"的状态（决定要不要保持前台服务）。 */
    fun isBusy(state: CacheQueueState): Boolean = state.pending > 0
}
