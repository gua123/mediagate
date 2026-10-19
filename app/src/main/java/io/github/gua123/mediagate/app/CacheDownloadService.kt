package io.github.gua123.mediagate.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.gua123.mediagate.MainActivity
import io.github.gua123.mediagate.MediaGateApplication
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.core.common.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * **缓存下载前台服务**（2026-10-19 用户要求「需要加前台服务」）。
 *
 * 为什么要它：预约缓存是"用户点了就走"的后台活——切到别的 App、息屏之后下载要继续，
 * 而且用户要能在通知栏看见"正在下第几个、到哪儿了"。Android 只允许**前台服务**拥有这种待遇；
 * 之前的实现只在进程里跑协程，App 一被系统回收下载就停了。
 *
 * 三条硬口径（都是本项目踩过的）：
 * - **类型必须是 dataSync**，且从**前台**启动（enqueue 发生在用户操作当下）——Android 14+ 不允许
 *   从后台把 dataSync 服务拉进前台，所以**返回 START_NOT_STICKY**，绝不让系统在后台复活它；
 * - **startForeground 失败就 stopSelf**（否则会触发"未按时 startForeground"的另一条崩溃）；
 * - 通知**原地更新**（固定 id），队列空了就退场，不留一个空转的前台服务。
 */
class CacheDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var observeJob: Job? = null

    private var foregroundReady = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as? MediaGateApplication)?.container ?: run {
            stopSelf()
            return
        }
        runCatching {
            ensureChannel()
            val state = container.cacheQueue.state.value
            ServiceCompat.startForeground(
                this,
                CacheQueueNotification.NOTIFICATION_ID,
                buildNotification(state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            foregroundReady = true
            AppLog.i(TAG, "缓存下载前台服务已启动")
            observeJob = scope.launch {
                container.cacheQueue.state.collect { current ->
                    if (current.pending == 0 && current.running == null) {
                        // 队列空了：先把最后一版状态写出去（用户能看到"已完成 N 个"），再退场
                        runCatching { notifyState(current) }
                        stopSelf()
                        return@collect
                    }
                    runCatching { notifyState(current) }
                }
            }
        }.onFailure { error ->
            // 前台化失败（渠道/权限/系统限制）：不要让服务半死不活，直接退场，队列仍在进程里跑
            AppLog.w(TAG, "缓存下载前台服务启动失败，退化为进程内下载", error)
            foregroundReady = false
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户从通知栏点暂停/继续时走这里（也在 Receiver 里处理过一次，这里兜底）
        when (intent?.action) {
            CacheQueueNotification.ACTION_PAUSE -> {
                (application as? MediaGateApplication)?.container?.cacheQueue?.pause()
            }

            CacheQueueNotification.ACTION_RESUME -> {
                (application as? MediaGateApplication)?.container?.cacheQueue?.resume()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        if (foregroundReady) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        foregroundReady = false
        AppLog.i(TAG, "缓存下载前台服务已退场")
        super.onDestroy()
    }

    private fun notifyState(state: CacheQueueState) {
        if (!foregroundReady) return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(CacheQueueNotification.NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: CacheQueueState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (state.paused) {
            PendingIntent.getService(
                this,
                1,
                Intent(this, CacheDownloadService::class.java).setAction(CacheQueueNotification.ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                Intent(this, CacheDownloadService::class.java).setAction(CacheQueueNotification.ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(this, CacheQueueNotification.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(CacheQueueNotification.title(state))
            .setContentText(CacheQueueNotification.text(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val progress = CacheQueueNotification.progress(state)
        if (progress != null) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (state.pending > 0) {
            builder.addAction(
                0,
                if (state.paused) "继续" else "暂停",
                toggle,
            )
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = NotificationManagerCompat.from(this)
        val channel = NotificationChannelCompat.Builder(
            CacheQueueNotification.CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(CacheQueueNotification.CHANNEL_NAME)
            .setDescription("预约缓存的下载进度")
            .build()
        manager.createNotificationChannel(channel)
    }

    companion object {

        private const val TAG = "cache-service"

        /**
         * 拉起服务（幂等：已经在跑就只是再发一次 start）。
         *
         * 只能从**前台**调用（enqueue / App 启动恢复队列这两处），否则 Android 14+ 会拒绝 dataSync 前台化。
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CacheDownloadService::class.java),
                )
            }.onFailure { AppLog.w(TAG, "拉起缓存下载服务失败（退化为进程内下载）", it) }
        }
    }
}
