package io.github.gua123.mediagate

import android.app.Application
import io.github.gua123.mediagate.app.AppContainer
import io.github.gua123.mediagate.app.CrashReporter
import io.github.gua123.mediagate.app.AsrQueueController
import io.github.gua123.mediagate.app.AsrRuntimeHost
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.Breadcrumbs
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.asr.AsrEngine
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.PlaybackYieldGate
import io.github.gua123.mediagate.media.asr.WhisperModel
import io.github.gua123.mediagate.media.playback.PlaybackHost
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore
import io.github.gua123.mediagate.media.playback.VideoSessionHost
import io.github.gua123.mediagate.media.playback.VideoSessionSource
import java.io.File

/**
 * mediagate 应用入口。
 *
 * M1 起在这里建立**唯一的**手写 DI 容器 [AppContainer]（不引 Hilt）：它持有根目录设置
 * （DataStore）、当前本地根目录的存储后端、缩略图仓库与音频播放环境，供各页面共用。
 *
 * 同时实现三个「服务反查宿主」的接口（服务由系统创建、拿不到构造注入）：
 * - [PlaybackHost]（M1-G，R18）：后台播放服务要的当前后端与断点续播存储；
 * - [VideoSessionHost]（M8-A，R18 视频侧）：视频会话服务要的"当前在播的是哪个视频、用哪个内核"；
 * - [AsrRuntimeHost]（M7-B，R14/R19）：字幕前台服务要的队列、识别引擎、让路闸门与模型路径。
 */
class MediaGateApplication : Application(), PlaybackHost, AsrRuntimeHost, VideoSessionHost {

    /** 应用级依赖容器；[onCreate] 里创建，进程存活期间唯一。 */
    lateinit var container: AppContainer
        private set

    /** 后台播放服务要的当前后端（R12）；尚未选择根目录时为 null（播放以中文错误提示失败）。 */
    override val playbackBackend: StorageBackend?
        get() = container.root.value?.backend

    /** 断点续播存储（R18）。 */
    override val playbackProgress: PlaybackProgressStore
        get() = container.playbackProgress

    /**
     * 视频会话源（R18 视频侧）：播放页把当前内核借给会话，没有会话时为 null。
     *
     * 服务只在播放页活着时才有对象可会话——这正是"只做会话与通知、播放仍在页面"的口径。
     */
    override val videoSessionSource: VideoSessionSource?
        get() = container.videoSession.videoSessionSource

    // ------------------------------------------------------------ 音转字幕（M7-B，R14/R19）

    /** 字幕队列（唯一真相在容器里）。 */
    override val asrController: AsrQueueController
        get() = container.asrController

    /** 识别执行器。 */
    override val asrEngine: AsrEngine
        get() = container.asrEngine

    /** 播放让路闸门。 */
    override val asrYieldGate: PlaybackYieldGate
        get() = container.asrYieldGate

    /** 无写权限时字幕的落地目录。 */
    override val asrFallbackDir: File
        get() = container.asrFallbackDir

    /** 当前后端（远端取音与写回都走它）。 */
    override val asrBackend: StorageBackend?
        get() = container.asrBackend

    override fun asrModel(): WhisperModel = container.asrModel()

    override fun asrModelPath(model: WhisperModel): String? = container.asrModelPath(model)

    override fun asrSource(item: AsrItem): String? = container.asrSource(item)

    override suspend fun asrDurationMs(item: AsrItem): Long = container.asrDurationMs(item)

    override fun onCreate() {
        super.onCreate()
        // 崩溃捕获要**最先装**：容器构造/数据库迁移之类早期崩溃也得留下报告（2026-10-03 真机闪退之后加的）
        CrashReporter.install(this)
        // 再补一刀：Java 处理器抓不到**原生崩溃**（MediaCodec/ffmpeg 的 .so 会让进程直接消失），
        // 用系统记录的进程退出原因补上；放子线程，不给启动添延迟
        Thread { runCatching { CrashReporter.captureLastExit(this) } }.start()
        // 面包屑落盘（原生崩溃时内存日志会没，只有它留得下）
        Breadcrumbs.sink = { text -> CrashReporter.breadcrumb(this, text) }
        Breadcrumbs.mark("应用启动（versionName " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() + "）")
        container = AppContainer(this)
        AppLog.i(TAG, "MediaGate 启动：" + versionText())
    }

    /** 启动日志里带上真实版本（别再写死——之前写死的 0.1.0 在排查时很误导）。 */
    private fun versionText(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "version=" + info.versionName + "(" + code + ")"
    }.getOrDefault("version=未知")

    private companion object {
        const val TAG = "MediaGate"
    }
}
