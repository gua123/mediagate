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
 */
data class HomeRootUi(
    val mode: RootModeKind = RootModeKind.NONE,
    val displayPath: String = "",
    val allFilesGranted: Boolean = false,
) {

    /** 是否已经选好根目录（否则首页必须显示引导而不是空白）。 */
    val configured: Boolean get() = mode != RootModeKind.NONE
}
