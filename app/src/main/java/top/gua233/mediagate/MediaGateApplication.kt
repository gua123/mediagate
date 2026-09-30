package io.github.gua123.mediagate

import android.app.Application
import io.github.gua123.mediagate.app.AppContainer
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.playback.PlaybackHost
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore

/**
 * mediagate 应用入口。
 *
 * M1 起在这里建立**唯一的**手写 DI 容器 [AppContainer]（不引 Hilt）：它持有根目录设置
 * （DataStore）、当前本地根目录的存储后端、缩略图仓库与音频播放环境，供各页面共用。
 *
 * 同时实现 [PlaybackHost]（M1-G，R18）：后台播放服务由系统创建、拿不到构造注入，
 * 只能从 Service.getApplication() 反查宿主，这里把「当前后端 + 断点续播存储」交给它。
 */
class MediaGateApplication : Application(), PlaybackHost {

    /** 应用级依赖容器；[onCreate] 里创建，进程存活期间唯一。 */
    lateinit var container: AppContainer
        private set

    /** 后台播放服务要的当前后端（R12）；尚未选择根目录时为 null（播放以中文错误提示失败）。 */
    override val playbackBackend: StorageBackend?
        get() = container.root.value?.backend

    /** 断点续播存储（R18）。 */
    override val playbackProgress: PlaybackProgressStore
        get() = container.playbackProgress

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppLog.i(TAG, "MediaGate 启动 version=0.1.0")
    }

    private companion object {
        const val TAG = "MediaGate"
    }
}
