package io.github.gua123.mediagate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.app.AppContainer
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.feature.browser.BrowserRoutes
import io.github.gua123.mediagate.feature.browser.BrowserScreen
import io.github.gua123.mediagate.feature.browser.LocalBrowserEnvironment
import io.github.gua123.mediagate.feature.browser.RootModeKind
import io.github.gua123.mediagate.feature.home.HomeRootUi
import io.github.gua123.mediagate.feature.home.HomeScreen
import io.github.gua123.mediagate.feature.player.audio.AudioPlayerRoutes
import io.github.gua123.mediagate.feature.player.audio.AudioPlayerScreen
import io.github.gua123.mediagate.feature.player.audio.LocalAudioPlayerEnvironment
import io.github.gua123.mediagate.feature.viewer.image.ImageViewerScreen
import io.github.gua123.mediagate.feature.viewer.image.LocalImageViewerEnvironment
import io.github.gua123.mediagate.feature.viewer.image.ViewerRoutes
import kotlinx.coroutines.launch

/**
 * 顶层底部导航的五个目标（M1：首页 / 浏览有真实实现，连接 / 任务 / 设置先放占位页）。
 *
 * [navRoute] 是点击时真正跳的路由；[matchRoute] 是 NavHost 注册的路由模板，
 * 两者分开是因为浏览页带查询参数（`browser?path={path}&kind={kind}`），
 * 而点击时必须跳到不带参数的形式 `browser`。
 */
private enum class TopLevelDestination(
    val navRoute: String,
    val matchRoute: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", "home", R.string.nav_home, Icons.Default.Home),
    BROWSER(BrowserRoutes.BASE, BrowserRoutes.ROUTE, R.string.nav_browser, Icons.Default.FolderOpen),
    CONNECTIONS("connections", "connections", R.string.nav_connections, Icons.Default.Dns),
    TASKS("tasks", "tasks", R.string.nav_tasks, Icons.AutoMirrored.Filled.Assignment),
    SETTINGS("settings", "settings", R.string.nav_settings, Icons.Default.Settings),
}

/**
 * 应用外壳：Scaffold（底部导航）+ NavHost（各页面）。
 *
 * **依赖注入方式（二选一里的 CompositionLocal 方案）**：这里用
 * `CompositionLocalProvider(LocalBrowserEnvironment provides container)` 把 [AppContainer]
 * 交给功能模块；:feature:browser 只认自己模块里的 `BrowserEnvironment` 接口，不反向依赖 :app。
 * （另一种写法是给每个页面传 viewModel 工厂，但那样 :app 得知道每个页面的内部依赖，
 * 反而把模块边界搅乱，所以选 CompositionLocal。）
 *
 * 权限入口也收在这里（R12）：
 * - SAF：[rememberLauncherForActivityResult] + `OpenDocumentTree`，拿到 URI 交给容器持久化；
 * - 全盘访问：跳系统设置页后，靠 [LocalLifecycleOwner] 的 ON_RESUME 重新读授权状态。
 *
 * 图片查看器（**M1-F**，R1）：浏览页点图片 → 导航到 [ViewerRoutes]（带相对路径参数），
 * 返回后仍在原目录；查看器路由是全屏页，此时隐藏底部导航栏。
 *
 * 音频播放（**M1-G**，R1/R18）：浏览页点音频 → 先要通知权限（R18 的通知栏控制需要它），
 * 再导航到 [AudioPlayerRoutes]；播放本身交给 :media:playback 的 MediaSessionService，
 * 界面拿到的只有 [io.github.gua123.mediagate.feature.player.audio.AudioPlaybackSnapshot]。
 *
 * @param container 应用级依赖容器（由 [io.github.gua123.mediagate.MediaGateApplication] 持有）。
 */
@Composable
fun MediaGateApp(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 顶层提示条：浏览页点到「尚未接入播放器」的文件类型时给一句话，而不是点了没反应
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unsupportedHint = stringResource(R.string.open_unsupported)

    // 全屏页（R1）：图片查看器占满整屏；音频播放页是播放场景，同样不给底部导航让位
    val fullScreenDestination = currentDestination?.route == ViewerRoutes.ROUTE ||
        currentDestination?.route == AudioPlayerRoutes.ROUTE

    // R18：后台播放的通知栏/锁屏控制需要通知权限；拒绝也照常播放，只提示一句中文
    val context = LocalContext.current
    val notificationDeniedHint = stringResource(R.string.audio_notification_denied)
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted: Boolean ->
        if (!granted) scope.launch { snackbarHostState.showSnackbar(notificationDeniedHint) }
    }

    /** 首次点音频时申请一次通知权限（已授权则什么都不做）。 */
    fun ensureNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // R12 SAF 模式：系统目录选择器（结果 URI 由容器 takePersistableUriPermission 后落 DataStore）
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) container.onSafTreePicked(uri)
    }

    // R12 全盘模式：用户可能刚从系统设置页返回，回前台就重新读一次授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) container.refreshAllFilesAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // 查看器占满整屏，不给底部导航让位（R1）
            if (!fullScreenDestination) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.matchRoute } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTopLevel(destination) },
                            icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        CompositionLocalProvider(
            LocalBrowserEnvironment provides container,
            // M1-F：图片查看器（R1）的宿主能力，同样由 AppContainer 提供
            LocalImageViewerEnvironment provides container,
            // M1-G：音频后台播放（R1/R18）的宿主能力（MediaController + 进度存储）
            LocalAudioPlayerEnvironment provides container,
        ) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.HOME.navRoute,
                // 外层已经让出了状态栏 / 底部导航的高度，这里把它标记为「已消费」，
                // 否则页面内部的 Scaffold + TopAppBar 会把系统栏再顶一次（内容整体下移一倍）
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                composable(TopLevelDestination.HOME.navRoute) {
                    HomeRoute(
                        container = container,
                        onOpenKind = { kind: MediaKind ->
                            // 跨模块导航：路由常量由 :feature:browser 暴露，首页只拼目标
                            navController.navigate(BrowserRoutes.route(kind = kind)) {
                                launchSingleTop = true
                            }
                        },
                        onPickSafDirectory = { safPicker.launch(null) },
                    )
                }

                composable(route = BrowserRoutes.ROUTE, arguments = BrowserRoutes.arguments) { entry ->
                    BrowserScreen(
                        initialPath = BrowserRoutes.pathOf(entry.arguments?.getString(BrowserRoutes.ARG_PATH)),
                        initialKind = BrowserRoutes.kindOf(entry.arguments?.getString(BrowserRoutes.ARG_KIND)),
                        onOpenEntry = { clicked ->
                            // R1：图片进查看器（M1-F）、音频进音频播放页（M1-G）；
                            // 其余类型播放器尚未接入，给一句中文提示
                            when (MediaKindGuesser.guess(clicked.name)) {
                                MediaKind.IMAGE -> navController.navigate(ViewerRoutes.route(clicked.path))

                                MediaKind.AUDIO -> {
                                    ensureNotificationPermission()
                                    navController.navigate(AudioPlayerRoutes.route(clicked.path))
                                }

                                else -> scope.launch { snackbarHostState.showSnackbar(unsupportedHint) }
                            }
                        },
                        onRequestRootAccess = { safPicker.launch(null) },
                    )
                }

                // 图片查看器（M1-F，R1）：返回键由页面 BackHandler 回调到这里，回到原目录
                composable(route = ViewerRoutes.ROUTE, arguments = ViewerRoutes.arguments) { entry ->
                    ImageViewerScreen(
                        path = ViewerRoutes.pathOf(entry.arguments?.getString(ViewerRoutes.ARG_PATH)),
                        onBack = { navController.popBackStack() },
                    )
                }

                // 音频播放页（M1-G，R1/R18）：队列由播放页按「同目录音频」自己解析，
                // 路由只带路径；真正的播放器在后台服务里，退到后台/息屏不中断。
                composable(route = AudioPlayerRoutes.ROUTE, arguments = AudioPlayerRoutes.arguments) { entry ->
                    AudioPlayerScreen(
                        path = AudioPlayerRoutes.pathOf(entry.arguments?.getString(AudioPlayerRoutes.ARG_PATH)),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(TopLevelDestination.CONNECTIONS.navRoute) {
                    PlaceholderScreen(title = stringResource(R.string.nav_connections))
                }
                composable(TopLevelDestination.TASKS.navRoute) {
                    PlaceholderScreen(title = stringResource(R.string.nav_tasks))
                }
                composable(TopLevelDestination.SETTINGS.navRoute) {
                    PlaceholderScreen(title = stringResource(R.string.nav_settings))
                }
            }
        }
    }
}

/** 首页：把容器里的根目录配置 + 权限状态映射成 [HomeRootUi]（页面本身不碰 DataStore）。 */
@Composable
private fun HomeRoute(
    container: AppContainer,
    onOpenKind: (MediaKind) -> Unit,
    onPickSafDirectory: () -> Unit,
) {
    val config by container.rootConfig.collectAsStateWithLifecycle()
    val allFilesGranted by container.allFilesGranted.collectAsStateWithLifecycle()
    HomeScreen(
        root = HomeRootUi(
            mode = config?.mode ?: RootModeKind.NONE,
            displayPath = config?.display.orEmpty(),
            allFilesGranted = allFilesGranted,
        ),
        onOpenKind = onOpenKind,
        onPickSafDirectory = onPickSafDirectory,
        onUseAllFilesRoot = container::useAllFilesRoot,
        onRequestAllFilesAccess = container::requestAllFilesAccess,
        onClearRoot = container::clearRoot,
    )
}

/** 底部导航切换：标准的 saveState / restoreState 组合，切走再切回保留各自的滚动与路径。 */
private fun NavHostController.switchTopLevel(destination: TopLevelDestination) {
    navigate(destination.navRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
