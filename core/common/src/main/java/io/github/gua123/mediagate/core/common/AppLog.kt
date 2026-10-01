package io.github.gua123.mediagate.core.common

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志门面：所有模块只用它，方便后续统一开关与落盘（诊断页）。
 * 约定：TAG 用「模块名」，例如 storage-webdav、asr、engine。
 *
 * 除了打 logcat，还维护一个**内存环形缓冲**（[snapshot]）：真机崩溃时 logcat 拿不到，
 * 崩溃报告里带上"出事前最后几百行"往往比堆栈本身更能说明问题（哪一步走到这里）。
 * 缓冲只占几十 KB，且按行截断，不会因为一条超长日志把内存吃光。
 */
object AppLog {

    var enabled: Boolean = true

    /** 环形缓冲容量（行）。 */
    const val RING_CAPACITY: Int = 400

    /** 单行最大长度（超出截断）。 */
    const val MAX_LINE_CHARS: Int = 400

    private val ring = ArrayDeque<String>()

    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) = log(Log.DEBUG, "D", tag, msg, null)

    fun i(tag: String, msg: String) = log(Log.INFO, "I", tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, "W", tag, msg, tr)

    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, "E", tag, msg, tr)

    /** 最近几行日志（旧 → 新）；崩溃报告与诊断页都用它。 */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    /** 清空缓冲（用户"清空日志"时调用）。 */
    fun clearBuffer() {
        synchronized(ring) { ring.clear() }
    }

    private fun log(priority: Int, level: String, tag: String, msg: String, tr: Throwable?) {
        if (!enabled) return
        val t = "MG/$tag"
        // 堆栈文本也走 runCatching：JVM 单测里 android.util.Log 是桩实现（getStackTraceString 会抛）
        val text = if (tr == null) msg else msg + "\n" + stackTraceText(tr)
        // 打 logcat 失败不该让业务崩（也让本类在 JVM 单测里可用：Android 的 Log 是桩实现）
        runCatching { Log.println(priority, t, text) }
        append(level, tag, text)
    }

    /** 堆栈文本：优先用 Android 的（带 cause 链），拿不到就退回 JVM 的。 */
    private fun stackTraceText(tr: Throwable): String =
        runCatching { Log.getStackTraceString(tr) }.getOrElse { tr.stackTraceToString() }

    /** 写进环形缓冲（按行拆开、逐行截断）。 */
    private fun append(level: String, tag: String, text: String) {
        val stamp = clock.format(Date())
        val lines = text.split('\n')
        synchronized(ring) {
            lines.forEach { line ->
                val trimmed = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…" else line
                ring.addLast(stamp + " " + level + "/" + tag + " " + trimmed)
                while (ring.size > RING_CAPACITY) ring.removeFirst()
            }
        }
    }
}
