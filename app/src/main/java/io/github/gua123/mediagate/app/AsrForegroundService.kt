package io.github.gua123.mediagate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.gua123.mediagate.MainActivity
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.media.asr.AsrFailureKind
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.AsrOutput
import io.github.gua123.mediagate.media.asr.AsrOutputFormat
import io.github.gua123.mediagate.media.asr.AsrQueueState
import io.github.gua123.mediagate.media.asr.AsrRequest
import io.github.gua123.mediagate.media.asr.AsrRunResult
import io.github.gua123.mediagate.media.asr.AsrWriteKind
import io.github.gua123.mediagate.media.asr.WhisperAsrEngine

/**
 * 批量字幕前台服务（**M7-B / R19**：前台服务 + 通知；息屏、切应用、锁屏都继续跑）。
 *
 * 组成（与 AudioPlaybackService 同一套路）：
 * - **前台通知**：总进度、当前文件、暂停/取消两个按钮；类型 mediaProcessing（manifest 已声明）；
 * - **执行循环**：从 [AsrQueueController] 取「该跑的任务」，逐个交给 [WhisperAsrEngine]；
 * - **让路**：每个窗口开始前问一次 [PlaybackYieldGate]——播放中就降到 1 线程，勾了
 *   「暂停识别」就等播放结束再继续（plan 4.12 的关键约束）；
 * - **结果**：成功写回视频同目录（无写权限落 App 私有目录并提示，R14），失败按
 *   [AsrFailureKind] 记原因（R19），全部落 asr_task 表，进程被杀后可续跑。
 *
 * **并发说明**：队列允许设成 2，但 native 侧同一时刻只允许存在一个 whisper 上下文
 * （whisper small 一份就 ~466 MB，两份必 OOM，见 whisper_jni.cpp 的 MG_ERR_ALREADY_INIT），
 * 所以**识别阶段实际仍是串行**；并发设置影响的是排队与界面展示。真机上要真正并行，
 * 得先解决内存问题（换 tiny/base 或用更小的量化档），这条列在真机验证清单里。
 *
 * 真机才能验证的：前台服务保活（澎湃 OS 省电策略）、进程被杀后重启续跑、让路对播放的实际影响。
 */
class AsrForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null, 0, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = application as? AsrRuntimeHost
        if (host == null) {
            AppLog.w(TAG, "应用没有实现 AsrRuntimeHost，字幕服务无法取依赖")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> host.asrController.pause()
            ACTION_CANCEL_ALL -> host.asrController.cancelAll()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        refreshNotification(host)
        startWorker(host)
        return START_STICKY
    }

    override fun onDestroy() {
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 执行循环

    private fun startWorker(host: AsrRuntimeHost) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            val controller = host.asrController
            runCatching { controller.restore() }
            while (isActive) {
                val snapshot = controller.snapshot.value
                if (snapshot.state == AsrQueueState.STOPPED || snapshot.state == AsrQueueState.PAUSED) break
                val started = controller.start()
                if (started.isEmpty()) {
                    if (!controller.hasWork) break
                    refreshNotification(host)
                    delay(IDLE_POLL_MS)
                    continue
                }
                for (item in started) {
                    runCatching { process(host, item) }
                        .onFailure { error -> controller.fail(item.id, error) }
                    refreshNotification(host)
                }
            }
            refreshNotification(host)
            stopSelf()
        }
    }

    /** 处理一条任务：取音源 → 识别（可取消、保留部分结果）→ 合并 → 写回。 */
    private suspend fun process(host: AsrRuntimeHost, item: AsrItem) {
        val controller = host.asrController
        val model = host.asrModel()
        val modelPath = host.asrModelPath(model)
        if (modelPath == null) {
            controller.fail(item.id, AsrFailureKind.MODEL_MISSING, "模型 " + model.id + " 还没下载")
            return
        }
        val source = host.asrSource(item)
        if (source == null) {
            controller.fail(item.id, AsrFailureKind.NOT_FOUND, "打不开音源：" + item.path)
            return
        }
        val durationMs = host.asrDurationMs(item)
        if (durationMs <= 0L) {
            controller.fail(item.id, AsrFailureKind.NO_AUDIO_TRACK, "读不到音轨时长（可能没有音轨）")
            return
        }

        val engine = host.asrEngine as? WhisperAsrEngine
        val request = AsrRequest(
            source = source,
            durationMs = durationMs,
            model = model,
            modelPath = modelPath,
        )
        val progress: (io.github.gua123.mediagate.media.asr.AsrProgress) -> Unit = { value ->
            controller.onProgress(item.id, value.recognizedMs, value.totalMs)
        }
        val result: AsrRunResult = if (engine != null) {
            engine.transcribePartial(request, progress)
        } else {
            host.asrEngine.transcribe(request, progress)
        }

        if (result.cues.isEmpty()) {
            if (result.cancelled) {
                controller.cancel(item.id)
            } else {
                controller.skip(item.id, "没有识别出任何语音")
            }
            return
        }
        controller.markWriting(item.id)
        val written = AsrOutput.write(
            backend = host.asrBackend,
            videoPath = item.path,
            format = AsrOutputFormat.DEFAULT,
            cues = result.cues,
            fallbackDir = host.asrFallbackDir,
        )
        controller.succeed(item.id, written.path)
        AppLog.i(
            TAG,
            "字幕完成 " + item.name + " 类型=" + written.kind + " 落点=" + written.path +
                (if (written.kind == AsrWriteKind.WRITTEN) "" else "（无写权限，已落 App 目录）"),
        )
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.asr_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.asr_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 按当前队列刷新通知（总进度 / 当前文件 / 暂停与取消按钮）。 */
    private fun refreshNotification(host: AsrRuntimeHost) {
        val snapshot = host.asrController.snapshot.value
        val running = snapshot.state == AsrQueueState.RUNNING
        runCatching {
            val notification = buildNotification(snapshot.currentItem?.name, snapshot.totalPercent, running)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { AppLog.w(TAG, "刷新字幕通知失败", it) }
    }

    private fun buildNotification(currentName: String?, percent: Int, running: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.asr_notification_title))
            .setContentText(
                if (currentName.isNullOrBlank()) {
                    getString(R.string.asr_notification_idle)
                } else {
                    getString(R.string.asr_notification_current, currentName)
                },
            )
            .setProgress(100, percent.coerceIn(0, 100), currentName.isNullOrBlank())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(
                0,
                getString(if (running) R.string.asr_notification_pause else R.string.asr_notification_resume),
                action(running, ACTION_PAUSE, ACTION_RESUME),
            )
            .addAction(0, getString(R.string.asr_notification_cancel), action(true, ACTION_CANCEL_ALL, ACTION_CANCEL_ALL))
        return builder.build()
    }

    /** 通知栏按钮 → 服务自身（暂停/继续共用同一个 action，服务里按当前状态处理）。 */
    private fun action(primary: Boolean, primaryAction: String, fallbackAction: String): PendingIntent {
        val chosen = if (primary) primaryAction else fallbackAction
        return PendingIntent.getService(
            this,
            chosen.hashCode(),
            Intent(this, AsrForegroundService::class.java).setAction(chosen),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {

        const val ACTION_START = "io.github.gua123.mediagate.action.ASR_START"
        const val ACTION_PAUSE = "io.github.gua123.mediagate.action.ASR_PAUSE"
        const val ACTION_RESUME = "io.github.gua123.mediagate.action.ASR_RESUME"
        const val ACTION_CANCEL_ALL = "io.github.gua123.mediagate.action.ASR_CANCEL_ALL"
        const val ACTION_STOP = "io.github.gua123.mediagate.action.ASR_STOP"

        private const val CHANNEL_ID = "mediagate_asr"
        private const val NOTIFICATION_ID = 0x4153 // "AS"
        private const val IDLE_POLL_MS = 1_000L
        private const val TAG = "asr-service"

        /** 供 :app 之外的地方（诊断/测试）拉起服务。 */
        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, AsrForegroundService::class.java).setAction(ACTION_START),
                )
            }
        }
    }
}
