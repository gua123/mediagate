package io.github.gua123.mediagate.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.local.LocalBackends
import io.github.gua123.mediagate.feature.browser.BrowserEnvironment
import io.github.gua123.mediagate.feature.browser.BrowserRootState
import io.github.gua123.mediagate.feature.browser.RootModeKind
import io.github.gua123.mediagate.media.thumbnail.FfmpegFrameExtractor
import io.github.gua123.mediagate.media.thumbnail.MediaMetadataRetrieverFrameExtractor
import io.github.gua123.mediagate.media.thumbnail.ThumbnailCache
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository
import java.io.File

/**
 * 手写 DI 容器（不引 Hilt）——`:app` 的应用级单例，由 [io.github.gua123.mediagate.MediaGateApplication]
 * 在 `onCreate` 创建并持有。
 *
 * 它负责三件事（R12 / R5 / R2）：
 * 1. **根目录设置**：[RootSettings] 用 DataStore Preferences 持久化「模式 + 路径/树 URI」，
 *    并对外暴露 [rootConfig]（首页展示用）；
 * 2. **当前根目录的后端**：配置一变就创建新的 [io.github.gua123.mediagate.data.storage.api.StorageBackend]
 *    （SAF → `SafStorageBackend`；全盘 → `FileStorageBackend`），并关闭旧实例，
 *    以 [BrowserEnvironment.root] 的形式提供给 :feature:browser；
 * 3. **缩略图仓库**：懒加载 [ThumbnailRepository]（两级缓存 + MMR 主策略 + FFmpeg 兜底）。
 *
 * 权限动作（拉起 SAF 选择器 / 跳「所有文件访问」设置页）也收在这里，页面只调方法，
 * 不各自拼 Intent。所有耗时动作都跑在 [ioScope] 或后端内部的 `Dispatchers.IO` 上。
 */
class AppContainer(context: Context) : BrowserEnvironment {

    private val appContext: Context = context.applicationContext

    /** 应用级协程作用域：只做「订阅配置 → 换后端」这类长生命周期的小任务。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings = RootSettings(appContext)

    /** 当前根目录配置（首页展示模式 + 路径；null = 未选择）。 */
    val rootConfig: StateFlow<RootConfig?> =
        settings.config.stateIn(ioScope, SharingStarted.Eagerly, null)

    private val _root = MutableStateFlow<BrowserRootState?>(null)

    /** 浏览器看到的当前根目录（R12）；配置变化后自动换成新的后端实例。 */
    override val root: StateFlow<BrowserRootState?> = _root.asStateFlow()

    private val _allFilesGranted = MutableStateFlow(hasAllFilesAccess())

    /** 是否已获得「所有文件访问」权限（首页按钮据此决定「去开启」还是「使用」）。 */
    val allFilesGranted: StateFlow<Boolean> = _allFilesGranted.asStateFlow()

    /**
     * 缩略图仓库（R5，plan 4.4）。
     *
     * 懒加载：只有真正进浏览页才建缓存目录与抽帧器；缓存目录落在 App 缓存内（系统可回收）。
     */
    override val thumbnails: ThumbnailRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ThumbnailRepository(
            cache = ThumbnailCache(rootDir = File(appContext.cacheDir, THUMBNAIL_CACHE_DIR)),
            primary = MediaMetadataRetrieverFrameExtractor(),
            fallback = FfmpegFrameExtractor(workDir = File(appContext.cacheDir, THUMBNAIL_WORK_DIR)),
        )
    }

    init {
        ioScope.launch {
            rootConfig.collect { config -> applyConfig(config) }
        }
    }

    // ------------------------------------------------------------ 权限与根目录

    /**
     * SAF 目录选择器回调（R12 SAF 模式）。
     *
     * 必须立刻 `takePersistableUriPermission`，否则重启后授权失效；部分提供方不支持写权限，
     * 所以先试「读 + 写」，失败再退化成「只读」，两次都失败才记日志（不抛给 UI）。
     *
     * @param uri `ActivityResultContracts.OpenDocumentTree` 返回的树 URI。
     */
    fun onSafTreePicked(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, readWrite)
        }.isSuccess || runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!persisted) {
            AppLog.w(TAG, "SAF 持久化授权失败，重启后可能需要重新选择目录：$uri")
        }
        val display = safDisplayName(uri)
        ioScope.launch { settings.setSaf(uri.toString(), display) }
        AppLog.i(TAG, "SAF 根目录已设置：$display")
    }

    /** 切到「所有文件访问」模式（已授权时才调用；未授权请先走 [requestAllFilesAccess]）。 */
    fun useAllFilesRoot() {
        refreshAllFilesAccess()
        if (!_allFilesGranted.value) {
            AppLog.w(TAG, "尚未获得「所有文件访问」权限，忽略切换请求")
            return
        }
        ioScope.launch { settings.setAllFiles(allFilesRootPath()) }
    }

    /**
     * 跳系统「所有文件访问」设置页（R12 全盘模式引导）。
     *
     * 先用带包名的 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（直达本应用）；
     * 个别 ROM 没有该 Activity，退回 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 列表页。
     */
    fun requestAllFilesAccess() {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            AppLog.w(TAG, "本机没有应用级「所有文件访问」设置页，改用列表页", e)
            val listIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(listIntent) }
                .onFailure { AppLog.w(TAG, "无法打开「所有文件访问」设置页", it) }
        }
    }

    /** 清除根目录（回到「未选择」，首页重新显示引导卡片）。 */
    fun clearRoot() {
        ioScope.launch { settings.clear() }
    }

    /** 从系统重新读一次「所有文件访问」授权状态（页面 ON_RESUME 时调用）。 */
    fun refreshAllFilesAccess() {
        _allFilesGranted.value = hasAllFilesAccess()
    }

    // ------------------------------------------------------------ 内部实现

    /** 配置 → 后端；换根时关闭旧后端，避免 File 句柄 / SAF fd 泄漏。 */
    private fun applyConfig(config: RootConfig?) {
        val previous = _root.value
        val next = config?.let { buildRoot(it) }
        _root.value = next
        if (previous?.backend !== next?.backend) {
            runCatching { previous?.backend?.close() }
        }
        if (next != null) {
            ioScope.launch { logProbe(next) }
        }
    }

    private fun buildRoot(config: RootConfig): BrowserRootState? = when (config.mode) {
        RootModeKind.SAF -> BrowserRootState(
            mode = RootModeKind.SAF,
            label = appContext.getString(R.string.root_label_saf),
            displayPath = config.display,
            backend = LocalBackends.saf(appContext, config.value),
        )

        RootModeKind.ALL_FILES -> BrowserRootState(
            mode = RootModeKind.ALL_FILES,
            label = appContext.getString(R.string.root_label_all_files),
            displayPath = config.display.ifEmpty { config.value },
            backend = LocalBackends.file(File(config.value)),
        )

        // NONE 不会出现在已保存配置里；真出现也只当成「未选择」，绝不抛异常把首页炸掉（R12）
        RootModeKind.NONE -> null
    }

    /** 启动时做一次根目录自检并记日志（失败只提示，不阻塞 UI——浏览页自己会给错误卡片）。 */
    private suspend fun logProbe(root: BrowserRootState) {
        val report = runCatching { root.backend.probe() }.getOrNull() ?: return
        AppLog.i(
            TAG,
            "根目录自检 mode=${root.mode} path=${root.displayPath} ok=${report.ok} ${report.message.orEmpty()}",
        )
    }

    private fun hasAllFilesAccess(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** 全盘模式的根：内部存储挂载点（`/storage/emulated/0`）。 */
    private fun allFilesRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * SAF 树 URI → 展示名。
     *
     * 形如 `content://com.android.externalstorage.documents/tree/primary%3AMovies`，
     * 末段解码后是 `primary:Movies`，取冒号后的目录名；整盘授权（`primary:`）没有名字，
     * 用「内部存储」兜底。
     */
    private fun safDisplayName(uri: Uri): String {
        val segment = uri.lastPathSegment ?: return uri.toString()
        val name = segment.substringAfterLast(':')
        return name.ifEmpty { appContext.getString(R.string.root_label_internal_storage) }
    }

    private companion object {
        const val TAG = "app-container"

        /** 缩略图两级缓存的磁盘根（App 缓存目录下）。 */
        const val THUMBNAIL_CACHE_DIR = "thumbnails"

        /** FFmpeg 兜底抽帧的临时文件目录。 */
        const val THUMBNAIL_WORK_DIR = "thumb-work"
    }
}
