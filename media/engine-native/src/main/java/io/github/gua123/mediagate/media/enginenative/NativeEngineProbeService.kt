package io.github.gua123.mediagate.media.enginenative

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import java.io.File

/**
 * 自研引擎的启动探针（**与 LibVLC 探针同一套思路**）。
 *
 * 关键点：它跑在**独立进程**（清单里 android:process=":engineprobe"）——自研引擎里最容易
 * 把进程带走的几步（FFmpeg 解码器初始化、AAudio 开流）在这个进程里做；
 * 崩了只带走探针自己，主进程照常活着，还能据此**不让用户切到它**（或提示先重测）。
 *
 * 留三个文件当证据（跨进程、崩溃后仍在）：
 * - [FILE_START]：开始测（先写）；
 * - [FILE_OK]：自检通过（内容是人话摘要）；
 * - [FILE_FAIL]：自检没通过（内容是中文原因，可直接显示给用户）；
 * - [FILE_DONE]：这段代码执行到底了（**没写 done 又没写 ok/fail ⇒ 进程中途被带走**）。
 */
class NativeEngineProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dir = probeDir(this)
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_START).writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
            File(dir, FILE_OK).delete()
            File(dir, FILE_FAIL).delete()
            File(dir, FILE_DONE).delete()
        }
        runCatching {
            val report = NativeEngine.selfCheck()
            if (report.startsWith("ok")) {
                File(dir, FILE_OK).writeText(report, Charsets.UTF_8)
                AppLog.i(TAG, "自研引擎探针：通过（" + report + "）")
            } else {
                // 自检不通过也**不是崩溃**：把中文原因留下来给界面用
                File(dir, FILE_FAIL).writeText(report.removePrefix("错误："), Charsets.UTF_8)
                AppLog.w(TAG, "自研引擎探针：没通过（" + report + "）")
            }
        }.onFailure { error ->
            runCatching { File(dir, FILE_FAIL).writeText(ErrorText.of(error), Charsets.UTF_8) }
            AppLog.w(TAG, "自研引擎探针：抛异常", error)
        }
        runCatching { File(dir, FILE_DONE).writeText(System.currentTimeMillis().toString(), Charsets.UTF_8) }
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {

        const val TAG = "engine-probe"

        /** 探针目录（主进程也读它）。 */
        fun probeDir(context: Context): File = File(context.filesDir, "engine-probe")

        const val FILE_START = "start"
        const val FILE_OK = "ok"
        const val FILE_FAIL = "fail"

        /** 跑完（无论成败）都会写；**没写**就说明进程在自检中途被带走了。 */
        const val FILE_DONE = "done"

        /** 拉起探针（独立进程；用户不知道它在跑，也不需要）。 */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, NativeEngineProbeService::class.java))
            }.onFailure { AppLog.w(TAG, "拉起自研引擎探针失败", it) }
        }

        /** 清掉上一轮证据（重新测试前调用）。 */
        fun reset(context: Context) {
            val dir = probeDir(context)
            runCatching { File(dir, FILE_START).delete() }
            runCatching { File(dir, FILE_OK).delete() }
            runCatching { File(dir, FILE_FAIL).delete() }
            runCatching { File(dir, FILE_DONE).delete() }
        }

        fun started(context: Context): Boolean = File(probeDir(context), FILE_START).exists()

        fun succeeded(context: Context): Boolean = File(probeDir(context), FILE_OK).exists()

        fun finished(context: Context): Boolean = File(probeDir(context), FILE_DONE).exists()

        /** 自检不通过的原因（中文；没有则返回 null）。 */
        fun failureReason(context: Context): String? =
            File(probeDir(context), FILE_FAIL).takeIf { it.exists() }
                ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                ?.takeIf { it.isNotBlank() }

        /** 通过时的人话摘要（没有则返回 null）。 */
        fun successReport(context: Context): String? =
            File(probeDir(context), FILE_OK).takeIf { it.exists() }
                ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                ?.removePrefix("ok|")
                ?.takeIf { it.isNotBlank() }
    }
}
