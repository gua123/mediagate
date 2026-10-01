package io.github.gua123.mediagate.core.common

import android.util.Log

/**
 * 统一日志门面：所有模块只用它，方便后续统一开关与落盘（诊断页）。
 * 约定：TAG 用「模块名」，例如 storage-webdav、asr、engine。
 */
object AppLog {

    var enabled: Boolean = true

    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg, null)

    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, tag, msg, tr)

    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    private fun log(priority: Int, tag: String, msg: String, tr: Throwable?) {
        if (!enabled) return
        val t = "MG/$tag"
        if (tr == null) Log.println(priority, t, msg) else Log.println(priority, t, "$msg\n${Log.getStackTraceString(tr)}")
    }
}
