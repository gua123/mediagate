package io.github.gua123.mediagate.feature.browser

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository

/**
 * 本地根目录模式（R12 双模式）。
 *
 * 两种模式**都必须有入口**，用户可以随时切换：
 * - [SAF]：`ActivityResultContracts.OpenDocumentTree` 选目录 + `takePersistableUriPermission`；
 * - [ALL_FILES]：`Environment.isExternalStorageManager()` 为真后直接以 `/storage/emulated/0` 为根；
 * - [NONE]：尚未选择 —— 首页给引导卡片，浏览器给提示页，**不允许空白或崩溃**。
 */
enum class RootModeKind {
    /** 尚未选择任何根目录。 */
    NONE,

    /** SAF 目录授权（系统选择器 + 持久化 URI 授权）。 */
    SAF,

    /** 「所有文件访问」全盘模式（MANAGE_EXTERNAL_STORAGE）。 */
    ALL_FILES,
}

/**
 * 浏览器可见的「当前根目录」（R12）。
 *
 * [backend] 由 :app 的 AppContainer 按当前配置创建并持有生命周期：根目录一变就换新实例，
 * 旧实例由容器关闭。浏览器只消费，不负责创建/关闭。
 *
 * @property mode 根目录模式。
 * @property label 面包屑第一段的展示名（已本地化，来自 :app 的资源）。
 * @property displayPath 根目录的展示路径（SAF 为目录名，全盘为挂载路径）。
 * @property backend 已打开的存储后端（File 或 SAF）。
 */
data class BrowserRootState(
    val mode: RootModeKind,
    val label: String,
    val displayPath: String,
    val backend: StorageBackend,
)

/**
 * 浏览器需要的宿主能力（R12 根目录 / R5 缩略图）。
 *
 * 为什么要有这层接口：`:feature:browser` 不能反向依赖 `:app`，[AppContainer] 也无法
 * 通过构造函数传进 Compose 树。因此 :app 只在导航宿主里用
 * [LocalBrowserEnvironment] 提供一次，页面与 ViewModel 都从这里取依赖（手写 DI，不引 Hilt）。
 */
interface BrowserEnvironment {

    /**
     * 当前根目录（R12）；`null` 表示尚未选择，页面应展示引导而不是空白。
     *
     * 根目录切换（换目录 / 换模式）会发出新值（[BrowserRootState] 内含新的后端实例），
     * 浏览器据此自动重新列目录。
     */
    val root: StateFlow<BrowserRootState?>

    /** 缩略图仓库（R5，plan 4.4）：列表缩略图统一走它，不在组合函数里做 IO。 */
    val thumbnails: ThumbnailRepository

    /**
     * 列表排序设置（**2026-10-03 用户要求**：「增加文件文件夹排序功能」）。
     *
     * 由 :app 持久化（DataStore），所以换页/重启后仍是用户上次选的排法。
     */
    val sort: StateFlow<EntrySort>

    /** 改排序并落盘。 */
    suspend fun setSort(sort: EntrySort)
}

/**
 * 由 :app 在导航宿主处提供的浏览器依赖（手写 DI 的注入点）。
 *
 * 取值失败说明忘了在 `MediaGateApp()` 里 `CompositionLocalProvider(LocalBrowserEnvironment provides container)`。
 */
val LocalBrowserEnvironment = staticCompositionLocalOf<BrowserEnvironment> {
    error("LocalBrowserEnvironment 未注入：请在 :app 的 MediaGateApp() 里用 AppContainer 提供（R12）")
}
