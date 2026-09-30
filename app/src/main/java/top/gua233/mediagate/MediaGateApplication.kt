package io.github.gua123.mediagate

import android.app.Application
import io.github.gua123.mediagate.app.AppContainer
import io.github.gua123.mediagate.core.common.AppLog

/**
 * mediagate 应用入口。
 *
 * M1 起在这里建立**唯一的**手写 DI 容器 [AppContainer]（不引 Hilt）：它持有根目录设置
 * （DataStore）、当前本地根目录的存储后端与缩略图仓库，供首页 / 浏览页共用。
 * 后续的 Room、前台服务、全局配置也挂在这里。
 */
class MediaGateApplication : Application() {

    /** 应用级依赖容器；[onCreate] 里创建，进程存活期间唯一。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppLog.i(TAG, "MediaGate 启动 version=0.1.0")
    }

    private companion object {
        const val TAG = "MediaGate"
    }
}
