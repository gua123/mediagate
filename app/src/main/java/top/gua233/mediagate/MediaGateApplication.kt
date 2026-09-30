package io.github.gua123.mediagate

import android.app.Application
import io.github.gua123.mediagate.core.common.AppLog

/**
 * mediagate 应用入口。
 *
 * M0 阶段只做启动日志；后续在这里初始化 DI 容器、Room、前台服务与全局配置。
 */
class MediaGateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "MediaGate 启动 version=0.1.0")
    }

    private companion object {
        const val TAG = "MediaGate"
    }
}
