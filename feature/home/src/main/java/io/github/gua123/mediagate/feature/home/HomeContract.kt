package io.github.gua123.mediagate.feature.home

import io.github.gua123.mediagate.feature.browser.RootModeKind

/**
 * 首页展示「当前根目录」所需的最小状态（R12 双模式）。
 *
 * 由 :app 从 AppContainer 的 DataStore 配置 + 权限状态映射而来：
 * 首页只负责展示与给入口，**不自己持有后端**（后端的生命周期归 :app）。
 *
 * @property mode 当前模式（未选择 = [RootModeKind.NONE]，此时卡片变成引导）。
 * @property displayPath 已选根目录的展示路径（SAF 是目录名，全盘是挂载路径）。
 * @property allFilesGranted 是否已拿到「所有文件访问」权限（决定按钮是「使用」还是「去开启」）。
 * @property remoteLabel 当前远端连接的展示名（如「homedev · 局域网」）；null = 没有远端连接。
 *   **远端优先**：有它时浏览与播放走的是远端，卡片必须说清楚，否则用户会以为自己在看本地目录。
 */
data class HomeRootUi(
    val mode: RootModeKind = RootModeKind.NONE,
    val displayPath: String = "",
    val allFilesGranted: Boolean = false,
    val remoteLabel: String? = null,
) {

    /** 是否已经选好根目录（否则首页必须显示引导而不是空白）。 */
    val configured: Boolean get() = mode != RootModeKind.NONE

    /** 现在真正在用的是远端连接（否则用本地根目录）。 */
    val remoteActive: Boolean get() = !remoteLabel.isNullOrBlank()

    /** 本地根目录这一项在远端生效时是"后台保留"状态（清不清都不影响远端）。 */
    val localStandby: Boolean get() = remoteActive && configured
}
