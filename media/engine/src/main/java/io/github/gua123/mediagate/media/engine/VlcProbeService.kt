package io.github.gua123.mediagate.media.engine

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.gua123.mediagate.core.common.AppLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import java.io.File

/**
 * LibVLC 启动探针（**2026-10-03 用户建议**）。
 *
 * 关键点：它跑在**独立进程**（清单里 `android:process=":vlcprobe"`）——`libvlc.so` 初始化在真机上可能
 * **直接把进程带走**（已有面包屑证据：`切换内核：→ LibVLC（已释放旧内核）` 之后没有"新内核已创建"）。
 * 放在独立进程里，崩的只是探针自己，主进程照常活着，还能据此**不让用户切到那个内核**。
 *
 * 三个证据落在文件里（跨进程、且崩溃后仍然存在）：
 * - `start`：开始测（先写它）；
 * - `ok`：LibVLC 与 MediaPlayer 都建起来了；
 * 主进程用"start 有、ok 没有、且系统记录探针进程是崩溃类退出"判定 [VlcProbeVerdict.FAILED]。
 */
class VlcProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dir = probeDir(this)
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_START).writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
            File(dir, FILE_OK).delete()
        }
        // 简化版初始化：建 LibVLC 实例 + 建一个 MediaPlayer（真机上崩的就是这一段里的事）
        runCatching {
            val libVlc = LibVLC(this)
            val player = MediaPlayer(libVlc)
            runCatching { player.release() }
            runCatching { libVlc.release() }
            File(dir, FILE_OK).writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
            AppLog.i(TAG, "LibVLC 启动探针：通过")
        }.onFailure { error ->
            // Java 层异常（UnsatisfiedLinkError 等）也算"起不来"，但只写日志、不写 ok
            AppLog.w(TAG, "LibVLC 启动探针：初始化抛异常", error)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {

        const val TAG = "vlc-probe"

        /** 探针目录（主进程也读它）。 */
        fun probeDir(context: Context): File = File(context.filesDir, "vlc-probe")

        const val FILE_START = "start"
        const val FILE_OK = "ok"

        /** 拉起探针（独立进程；用户从不知道它在跑，也不需要）。 */
        fun start(context: Context) {
            runCatching {
                // 探针是普通后台服务（不进前台）：只在启动测试时跑几秒
                context.startService(Intent(context, VlcProbeService::class.java))
            }.onFailure { AppLog.w(TAG, "拉起 LibVLC 探针失败", it) }
        }

        /** 清掉上一轮证据（重新测试前调用）。 */
        fun reset(context: Context) {
            val dir = probeDir(context)
            runCatching { File(dir, FILE_START).delete() }
            runCatching { File(dir, FILE_OK).delete() }
        }

        /** 开始过？ */
        fun started(context: Context): Boolean = File(probeDir(context), FILE_START).exists()

        /** 通过过？ */
        fun succeeded(context: Context): Boolean = File(probeDir(context), FILE_OK).exists()

        /** 探针进程上次退出原因（拿不到返回 -1）；反射读 ApplicationExitInfo，与 CrashReporter 同一套。 */
        fun lastProbeExitReason(context: Context): Int = runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return -1
            val method = ActivityManager::class.java.getMethod("getHistoricalProcessExitInfos")
            @Suppress("UNCHECKED_CAST")
            val infos = (method.invoke(manager) as? List<Any>).orEmpty()
            val probeProcess = context.packageName + ":vlcprobe"
            val info = infos.firstOrNull { info ->
                val name = runCatching { info.javaClass.getMethod("getProcessName").invoke(info) as? String }.getOrNull()
                name == probeProcess
            } ?: return -1
            runCatching { info.javaClass.getMethod("getReason").invoke(info) as? Int }.getOrNull() ?: -1
        }.getOrDefault(-1)
    }
}
