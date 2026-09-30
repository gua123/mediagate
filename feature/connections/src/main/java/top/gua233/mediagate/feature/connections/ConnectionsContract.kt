package io.github.gua123.mediagate.feature.connections

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.crypto.CredentialCipher
import io.github.gua123.mediagate.core.database.MediaGateDatabase
import io.github.gua123.mediagate.core.network.NetworkContext

/**
 * 连接管理页需要的宿主能力（**R8** 多连接与自测 / **R7** 选路 / **R6** 凭据加密）。
 *
 * 沿用本项目的"手写 DI"口径（见 :feature:browser 的 [io.github.gua123.mediagate.feature.browser.BrowserEnvironment]）：
 * :feature:connections 不反向依赖 :app，页面的依赖由 :app 在导航宿主处用
 * [LocalConnectionsEnvironment] 注入一次。**接口只给能力，不给实现**：
 *
 * - [database]：connection / address / network_rule 三张表（R7/R8，plan 第 7 章）；
 * - [cipher]：协议凭据加解密（R6，只存密文）；
 * - [currentConnectionId] / [setCurrentConnection]：「当前连接」由 :app 持有并据此切换后端；
 * - [networkContext]：当前网络现场（R7），变化时 revision 自增，界面据此让 60 s 缓存失效。
 */
interface ConnectionsEnvironment {

    /** 连接/地址/规则三张表所在的数据库（R7/R8）。 */
    val database: MediaGateDatabase

    /** 凭据加解密（R6）：密码只以密文进 `secretRef`。 */
    val cipher: CredentialCipher

    /** 当前连接 id；null = 没有指定（回落到首页选择的本地根目录）。 */
    val currentConnectionId: StateFlow<Long?>

    /** 设置当前连接（null = 清除）。:app 据此切换浏览器/播放器拿到的后端。 */
    suspend fun setCurrentConnection(id: Long?)

    /** 当前网络现场（R7）。JVM 单测注入固定值即可。 */
    val networkContext: StateFlow<NetworkContext>
}

/**
 * :app 在导航宿主处提供的连接管理依赖（手写 DI 的注入点）。
 *
 * 取值失败说明忘了在 `MediaGateApp()` 里
 * `CompositionLocalProvider(LocalConnectionsEnvironment provides container.connectionsEnvironment)`。
 */
val LocalConnectionsEnvironment = staticCompositionLocalOf<ConnectionsEnvironment> {
    error("LocalConnectionsEnvironment 未注入：请在 :app 的 MediaGateApp() 里用 AppContainer 提供（R8）")
}
