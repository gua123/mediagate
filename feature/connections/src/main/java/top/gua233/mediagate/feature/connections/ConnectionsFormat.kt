package io.github.gua123.mediagate.feature.connections

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接页的展示格式化（**R8** 的"最近检测时间"）——纯函数，JVM 单测覆盖。
 *
 * 文案全中文（R16）；相对时间只到"分钟前"，再久就给绝对时间，避免"3 天前"这种没法排查的显示。
 */
object ConnectionsFormat {

    /** 一分钟 / 一小时的毫秒数。 */
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS

    /**
     * 最近检测时间的展示文案。
     *
     * @param lastCheckedAtMs 记录里的最近检测时刻；null = 从未测过。
     * @param nowMs 当前时刻（注入以便单测）。
     */
    fun lastCheckedText(lastCheckedAtMs: Long?, nowMs: Long): String {
        if (lastCheckedAtMs == null || lastCheckedAtMs <= 0L) return "从未检测"
        val delta = nowMs - lastCheckedAtMs
        return when {
            delta < 0L -> "刚刚检测"
            delta < MINUTE_MS -> "刚刚检测"
            delta < HOUR_MS -> (delta / MINUTE_MS).toString() + " 分钟前检测"
            delta < 24L * HOUR_MS -> (delta / HOUR_MS).toString() + " 小时前检测"
            else -> absolute(lastCheckedAtMs)
        }
    }

    /** 绝对时间（月-日 时:分）。 */
    fun absolute(epochMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))

    /** 状态徽标文案（状态 + 最近检测时间）。 */
    fun badgeText(status: ConnectionStatus, lastCheckedAtMs: Long?, nowMs: Long): String =
        status.zhText + " · " + lastCheckedText(lastCheckedAtMs, nowMs)
}
