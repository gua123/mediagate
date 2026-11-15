package io.github.gua123.mediagate

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.gua123.mediagate.app.AppContainer
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

    /** 后台播放通知点回前台时要重开的视频路径；token 让同一路径也能再次触发导航。 */
    private data class DebugOpenRequest(
        val path: String,
        val token: Long,
        val backendId: String = "",
    )

    private val debugOpen = MutableStateFlow<DebugOpenRequest?>(null)

    /**
     * 只接受当前后端能解析的恢复请求。
     *
     * 真实故障：播放中切回本地目录后，通知栏还带着远端路径；回到前台时拿
     * `local-file:/storage/emulated/0` 去读 `home/nas1/...`，代理直接 404。
     * 带 backendId 的请求必须和当前后端一致；调试入口不带 backendId 时保持兼容。
     */
    private fun acceptedDebugOpen(container: AppContainer, request: DebugOpenRequest?): DebugOpenRequest? {
        if (request == null) return null
        val current = container.videoPlayerEnvironment.backend.value?.id
        if (request.backendId.isBlank() || request.backendId == current) return request
        return null
    }

    /**
     * 冷启动时远端连接是异步建起来的：通知/调试恢复可能先于远端后端就绪，
     * 此时直接打开会拿本地 backend 去读远端路径（VLC HTTP 404）。
     *
     * 带 backendId 的恢复请求先等这个后端最多 10 秒；等到才导航，等不到就放弃，
     * 不把“播放失败”甩到用户脸上。
     */
    private fun restoreDebugOpen(container: AppContainer, request: DebugOpenRequest?) {
        if (request == null) return
        if (request.backendId.isBlank() || acceptedDebugOpen(container, request) != null) {
            debugOpen.value = request
            return
        }
        lifecycleScope.launch {
            android.util.Log.i("MG/MediaGate", "等待播放后端就绪：" + request.backendId)
            val matched = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                container.videoPlayerEnvironment.backend.first { it?.id == request.backendId }
                true
            } ?: false
            if (matched) {
                debugOpen.value = request
            } else {
                android.util.Log.w(
                    "MG/MediaGate",
                    "恢复超时，已放弃：path=" + request.path + " backend=" + request.backendId,
                )
            }
        }
    }

    private fun readDebugOpen(intent: Intent?): DebugOpenRequest? {
        val path = intent?.getStringExtra("mg_open_path_b64")?.let {
            runCatching {
                String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
        } ?: intent?.getStringExtra("mg_open_path")
        if (path.isNullOrBlank()) return null
        return DebugOpenRequest(
            path,
            intent?.getLongExtra("mg_open_token", SystemClock.uptimeMillis()) ?: SystemClock.uptimeMillis(),
            intent?.getStringExtra("mg_backend_id").orEmpty(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MediaGateApplication).container
        // R13：PIP 比例按视频真实尺寸定档（Media3 的 PlayerView 能报 videoSize；取不到回落 16:9）
        pip = VideoPipController(this) { container.videoSession.videoSize() }
        container.attachPipBridge(pip)
        // 调试入口：adb shell am start -n .../.MainActivity --ez mg_clear_cache true
        if (intent?.getBooleanExtra("mg_clear_cache", false) == true) {
            lifecycleScope.launch {
                val freed = container.clearRemoteCache()
                android.util.Log.i("MG/MediaGate", "调试清空分段缓存：释放 " + freed + " 字节")
            }
        }
        restoreDebugOpen(
            container,
            readDebugOpen(intent) ?: container.videoSession.videoSessionSource
                ?.takeIf { it.path.isNotBlank() }
                ?.let {
                    DebugOpenRequest(
                        it.path,
                        SystemClock.uptimeMillis(),
                        it.backendId,
                    )
                },
        )
        setContent {
            val debugRequest by debugOpen.collectAsState()
            MediaGateTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MediaGateApp(
                        container = container,
                        debugOpenPath = debugRequest?.path,
                        debugOpenToken = debugRequest?.token ?: 0L,
                    )
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
     * 后台再回前台时主动检查/重建远端连接（2026-11-15 用户反馈：连接会断，重启才恢复）。
     * 播放中（视频会话/音频）由容器内部跳过，不会拆正在播的流。
     */
    override fun onResume() {
        super.onResume()
        val container = (application as MediaGateApplication).container
        container.onHostResumed()
    }

    override fun onPause() {
        (application as MediaGateApplication).container.onHostPaused()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as MediaGateApplication).container
        restoreDebugOpen(container, readDebugOpen(intent))
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
