package io.github.gua123.mediagate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.gua123.mediagate.ui.MediaGateApp
import io.github.gua123.mediagate.ui.theme.MediaGateTheme

/**
 * 唯一的 Activity（R6 无登录、冷启动直达首页）。
 *
 * 职责只有两件：开 edge-to-edge、把 [MediaGateApplication] 里的 [io.github.gua123.mediagate.app.AppContainer]
 * 交给 [MediaGateApp]；导航、权限引导、各页面都在 Compose 侧。
 */
class MainActivity : ComponentActivity() {

    override fun onDestroy() {
        super.onDestroy()
        // 用户真的离开应用（不是转屏/配置变更）时关掉回环代理的监听线程；
        // 若进程之后被系统拉回前台，AppContainer 会按需重建代理（见 requireVideoProxy）
        if (isFinishing) (application as MediaGateApplication).container.close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MediaGateApplication).container
        setContent {
            MediaGateTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MediaGateApp(container = container)
                }
            }
        }
    }
}
