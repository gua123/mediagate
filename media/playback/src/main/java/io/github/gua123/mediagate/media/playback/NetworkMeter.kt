package io.github.gua123.mediagate.media.playback

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * **网络读取计数**（2026-10-20）：累计从后端读到的字节与阻塞耗时，给诊断卡出一行「网络」统计。
 *
 * 只做两件小事：record 累加、line 格式化。线程安全用 AtomicLong；
 * 格式化是纯逻辑，可在 JVM 单测里钉住（弱网定位时这行数字就是证据）。
 */
class NetworkMeter {

    private val bytes = AtomicLong(0L)

    private val nanos = AtomicLong(0L)

    /** 记录一次读/开：实际读到的字节数（可能为 0）与阻塞耗时。 */
    fun record(bytesRead: Int, elapsedNanos: Long) {
        if (bytesRead > 0) bytes.addAndGet(bytesRead.toLong())
        if (elapsedNanos > 0L) nanos.addAndGet(elapsedNanos)
    }

    /** 开始播一个新文件时清零。 */
    fun reset() {
        bytes.set(0L)
        nanos.set(0L)
    }

    /** 一行中文统计；还没读到数据时返回 null（诊断卡不显示空行）。 */
    fun line(): String? {
        val total = bytes.get()
        if (total <= 0L) return null
        val millis = nanos.get() / 1_000_000L
        val megabytes = total / 1048576.0
        val seconds = millis / 1000.0
        val speed = if (millis > 0L) total * 1000.0 / millis / 1048576.0 else 0.0
        return "网络 " + oneDecimal(megabytes) + " MB · " + oneDecimal(seconds) + " s（" +
            oneDecimal(speed) + " MB/s）"
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
