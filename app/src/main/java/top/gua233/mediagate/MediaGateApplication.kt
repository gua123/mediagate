package io.github.gua123.mediagate

import android.app.Application
import io.github.gua123.mediagate.app.AppContainer
import io.github.gua123.mediagate.app.AsrQueueController
import io.github.gua123.mediagate.app.AsrRuntimeHost
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.asr.AsrEngine
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.PlaybackYieldGate
import io.github.gua123.mediagate.media.asr.WhisperModel
import io.github.gua123.mediagate.media.playback.PlaybackHost
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore
import java.io.File

/**
 * mediagate 应用入口。
 *
 * M1 起在这里建立**唯一的**手写 DI 容器 [AppContainer]（不引 Hilt）：它持有根目录设置
 * （DataStore）、当前本地根目录的存储后端、缩略图仓库与音频播放环境，供各页面共用。
 *
 * 同时实现两个「服务反查宿主」的接口（服务由系统创建、拿不到构造注入）：
 * - [PlaybackHost]（M1-G，R18）：后台播放服务要的当前后端与断点续播存储；
 * - [AsrRuntimeHost]（M7-B，R14/R19）：字幕前台服务要的队列、识别引擎、让路闸门与模型路径。
 */
class MediaGateApplication : Application(), PlaybackHost, AsrRuntimeHost {

    /** 应用级依赖容器；[onCreate] 里创建，进程存活期间唯一。 */
    lateinit var container: AppContainer
        private set

    /** 后台播放服务要的当前后端（R12）；尚未选择根目录时为 null（播放以中文错误提示失败）。 */
    override val playbackBackend: StorageBackend?
        get() = container.root.value?.backend

    /** 断点续播存储（R18）。 */
    override val playbackProgress: PlaybackProgressStore
        get() = container.playbackProgress

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
        container = AppContainer(this)
        AppLog.i(TAG, "MediaGate 启动 version=0.1.0")
    }

    private companion object {
        const val TAG = "MediaGate"
    }
}
