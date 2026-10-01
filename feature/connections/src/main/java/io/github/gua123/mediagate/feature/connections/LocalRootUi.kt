package io.github.gua123.mediagate.feature.connections

/**
 * 本地根目录模式（连接页自己的一份，**不依赖 :feature:browser**）。
 *
 * 为什么连接页也要管本地目录：用户"媒体从哪来"这件事有两个来源——本地目录与远端连接。
 * 原先只有首页能选本地目录，连接页只列远端，于是"我在连接页想换成手机里的目录"这件事没有入口。
 */
enum class LocalRootMode {

    /** 还没选（此时按钮是「选择目录」）。 */
    NONE,

    /** SAF 授权的目录（用户选的那棵树）。 */
    SAF,

    /** 全盘访问（已授予「所有文件访问」权限时可用）。 */
    ALL_FILES,
}

/**
 * 连接页里的「本地目录」摘要（**R12**）。
 *
 * :app 把 DataStore 里的根目录配置与权限状态映射成它；页面只展示与给入口，
 * 后端的生命周期仍归 :app（与首页同一套口径）。
 *
 * @param mode 当前模式。
 * @param displayPath 展示路径（SAF 是目录名，全盘是挂载路径）；未选择时为空。
 * @param allFilesGranted 是否已拿到「所有文件访问」权限。
 * @param value 底层值：SAF 是树 URI（content 开头），全盘是绝对路径。编辑本地连接时可以一键沿用。
 */
data class LocalRootUi(
    val mode: LocalRootMode = LocalRootMode.NONE,
    val displayPath: String = "",
    val allFilesGranted: Boolean = false,
    val value: String = "",
) {

    /** 是否已经选好本地根目录。 */
    val configured: Boolean get() = mode != LocalRootMode.NONE

    /** 全盘入口的按钮语义（未授权 = 去开启；已授权 = 直接切过去）。 */
    val allFilesAction: AllFilesAction
        get() = if (allFilesGranted) AllFilesAction.USE else AllFilesAction.REQUEST_PERMISSION

    /** 是否显示「清除」（没选过就没什么可清）。 */
    val canClear: Boolean get() = configured
}

/** 全盘入口该做什么（纯逻辑，便于单测钉住文案/行为切换）。 */
enum class AllFilesAction {

    /** 已授权：按一下直接切到全盘模式。 */
    USE,

    /** 未授权：跳系统「所有文件访问」设置页。 */
    REQUEST_PERMISSION,
}
