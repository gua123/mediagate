package io.github.gua123.mediagate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.gua123.mediagate.app.VideoPipController
import io.github.gua123.mediagate.ui.MediaGateApp
import io.github.gua123.mediagate.ui.theme.MediaGateTheme

/**
 * 唯一的 Activity（R6 无登录、冷启动直达首页）。
 *
 * 职责三件：开 edge-to-edge、把 [MediaGateApplication] 里的 [io.github.gua123.mediagate.app.AppContainer]
 * 交给 [MediaGateApp]，以及**画中画（R13）的 Activity 级接线**——进/出 PIP、PIP 参数与动作按钮
 * 只有 Activity 能做，所以这里建一个 [VideoPipController] 附着到容器上；播放页通过
 * `VideoPipHost` 这个宿主能力间接使用它（:feature:player-video 不认识 Activity）。
 */
class MainActivity : ComponentActivity() {

    /** 画中画控制器（R13）；[onCreate] 建、[onDestroy] 摘。 */
    private lateinit var pip: VideoPipController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MediaGateApplication).container
        // R13：PIP 比例按视频真实尺寸定档（Media3 的 PlayerView 能报 videoSize；取不到回落 16:9）
        pip = VideoPipController(this) { container.videoSession.videoSize() }
        container.attachPipBridge(pip)
        setContent {
            MediaGateTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MediaGateApp(container = container)
                }
            }
        }
    }

    /**
     * 用户按 Home / 上滑回桌面（**R13**：播放中自动进 PIP 的兜底路径）。
     *
     * Android 12+ 已经由 `setAutoEnterEnabled(true)` 让系统自己进 PIP；这里再显式进一次是为了
     * 行为确定（部分 ROM 的自动进入会被省电策略拦下）。页面只在"真的在播"时开放自动进入，
     * 所以暂停状态下按 Home 不会缩成小窗。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip.onUserLeaveHint()
    }

    /**
     * PIP 模式变化（**R13**）。
     *
     * 用 Activity 回调而不是 `addOnPictureInPictureModeChangedListener`：两者等价，前者在
     * compose 之外也成立（例如此时 Compose 树被系统裁剪成小窗尺寸），少一次监听器的注册/注销。
     * 这里把状态同步给容器（页面据此收控制层、提示"画中画中"）并刷新 PIP 参数。
     *
     * 说明：compileSdk 37 起这个回调被标记为 deprecated，但配套的
     * `addOnPictureInPictureModeChangedListener`（platform 37.2 的 android.jar 里没有）在本工程
     * 的编译 SDK 上取不到，所以仍用回调重载——语义完全等价，且不需要额外注册/注销监听器。
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pip.onPipModeChanged(isInPictureInPictureMode)
        (application as MediaGateApplication).container.setPipVisible(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        val app = application as MediaGateApplication
        // R13：先摘掉 PIP 桥（此后页面拿到的 PIP 调用都是空操作），再注销广播接收器
        app.container.detachPipBridge(pip)
        pip.release()
        super.onDestroy()
        // 用户真的离开应用（不是转屏/配置变更）时关掉回环代理的监听线程；
        // 若进程之后被系统拉回前台，AppContainer 会按需重建代理（见 requireVideoProxy）
        if (isFinishing) app.container.close()
    }
}
