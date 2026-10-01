package io.github.gua123.mediagate.feature.browser

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 列表展示用的**纯函数**格式化（R16：文案走各模块 strings.xml，这里只产出数字与单位）。
 *
 * 刻意不依赖任何 Android API，可直接 JVM 单测（见 `BrowserFormatTest`）。
 */
object BrowserFormat {

    /** 未知值的占位（大小 / 时间拿不到时用，不用 0 冒充）。 */
    const val UNKNOWN = "—"

    /** 进制单位；B 之后的单位是国际通用写法（中文界面同样写作 KB / MB / GB）。 */
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /**
     * 人类可读的文件大小。
     *
     * @param bytes 字节数；**负数表示未知**（[io.github.gua123.mediagate.core.model.RemoteEntry.size] 的口径，目录或未知为 -1），
     *   返回 [UNKNOWN]。1024 进制，保留 1 位小数。
     */
    fun size(bytes: Long): String {
        if (bytes < 0) return UNKNOWN
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit])
    }

    /**
     * 修改时间：`yyyy-MM-dd HH:mm`（本地时区）。
     *
     * @param epochMillis Unix 毫秒；**<= 0 表示未知**（[io.github.gua123.mediagate.core.model.RemoteEntry.mtime] 的口径），返回 [UNKNOWN]。
     * @param zone 时区，默认系统时区；单测传固定时区以保证结果稳定。
     */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return UNKNOWN
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d %02d:%02d",
            time.year,
            time.monthValue,
            time.dayOfMonth,
            time.hour,
            time.minute,
        )
    }
}
