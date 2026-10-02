package io.github.gua123.mediagate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import io.github.gua123.mediagate.feature.connections.ConnectionsScreen
import io.github.gua123.mediagate.feature.connections.LocalRootMode
import io.github.gua123.mediagate.feature.connections.LocalRootUi
import io.github.gua123.mediagate.feature.connections.LocalConnectionsEnvironment
import io.github.gua123.mediagate.feature.home.HomeRootUi
import io.github.gua123.mediagate.feature.home.HomeScreen
import io.github.gua123.mediagate.feature.player.audio.AudioPlayerRoutes
import io.github.gua123.mediagate.feature.player.audio.AudioPlayerScreen
import io.github.gua123.mediagate.feature.player.audio.LocalAudioPlayerEnvironment
import io.github.gua123.mediagate.feature.player.video.LocalVideoPlayerEnvironment
import io.github.gua123.mediagate.feature.player.video.VideoPlayerRoutes
import io.github.gua123.mediagate.feature.player.video.VideoPlayerScreen
import io.github.gua123.mediagate.core.download.FileDownloader
import io.github.gua123.mediagate.app.ui.DiagnosticsSection
import io.github.gua123.mediagate.app.ui.NetworkTuningSection
import io.github.gua123.mediagate.app.ui.PlaybackSection
import io.github.gua123.mediagate.app.ui.TrustedHostKeysSection
import io.github.gua123.mediagate.feature.settings.KeepAliveAction
import io.github.gua123.mediagate.feature.settings.KeepAliveItemKind
import io.github.gua123.mediagate.feature.settings.SettingsNote
import io.github.gua123.mediagate.feature.settings.SettingsScreen
import io.github.gua123.mediagate.feature.update.UpdateChecker
import io.github.gua123.mediagate.feature.update.UpdateSection
import io.github.gua123.mediagate.feature.update.UpdateViewModel
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
/**
 * 从播放/看图页"返回"到浏览页（**2026-10-03 用户要求**）。
 *
 * 两种来路：
 * - 从浏览页点进来的：回退栈里就有浏览页 ⇒ 直接 pop（它会保留自己所在的目录）；
 * - 从首页"最近播放/继续播放"点进来的：栈里没有浏览页 ⇒ **打开上次浏览的目录**，
 *   而不是回首页（用户原话：「返回下方菜单栏中的首页，这样我还要重新选择文件夹」）。
 */
private fun NavHostController.backToBrowser(lastPath: String?) {
    if (popBackStack(BrowserRoutes.ROUTE, inclusive = false)) return
    navigate(BrowserRoutes.route(path = lastPath?.takeIf { it.isNotEmpty() })) {
        launchSingleTop = true
    }
}

private enum class TopLevelDestination(
    val navRoute: String,
    val matchRoute: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", "home", R.string.nav_home, Icons.Default.Home),
    BROWSER(BrowserRoutes.BASE, BrowserRoutes.ROUTE, R.string.nav_browser, Icons.Default.FolderOpen),
    CONNECTIONS("connections", "connections", R.string.nav_connections, Icons.Default.Dns),
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
 * 视频播放（**M2-B**，R1/R4/R9/R10/R18）：浏览页点视频 → 导航到 [VideoPlayerRoutes]；
 * 内核实例（Media3 / LibVLC）、回环代理与断点存储都由容器经
 * [LocalVideoPlayerEnvironment] 注入，页面只发命令、收状态。
 *
 * @param container 应用级依赖容器（由 [io.github.gua123.mediagate.MediaGateApplication] 持有）。
 */
@Composable
fun MediaGateApp(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // **2026-10-03 用户要求**：「退出视频播放…而不是返回下方菜单栏中的首页，这样我还要重新选择文件夹」
    // ⇒ 记住浏览页当前所在目录；播放/看图退出时回到这个文件夹（而不是首页）。
    // 放在外壳层（不在浏览页的 ViewModel 里），所以浏览页被销毁也能记住。
    var lastBrowsedPath by rememberSaveable { mutableStateOf("") }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 顶层提示条：浏览页点到「尚未接入播放器」的文件类型时给一句话，而不是点了没反应
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unsupportedHint = stringResource(R.string.open_unsupported)

    // R2/R8：远端连接切不过去（没存密码 / 配置不合法 / 当前网络没有可用地址）时给一句话，
    // 而不是让用户以为"已经切到 SFTP 了、只是目录没跟着变"
    val remoteNotice by container.remoteNotice.collectAsStateWithLifecycle()
    LaunchedEffect(remoteNotice) {
        val text = remoteNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        container.dismissRemoteNotice()
    }

    // 全屏页（R1）：图片查看器占满整屏；音频/视频播放页是播放场景，同样不给底部导航让位
    val fullScreenDestination = currentDestination?.route == ViewerRoutes.ROUTE ||
        currentDestination?.route == AudioPlayerRoutes.ROUTE ||
        currentDestination?.route == VideoPlayerRoutes.ROUTE

    // R18：后台播放的通知栏/锁屏控制需要通知权限；拒绝也照常播放，只提示一句中文
    val context = LocalContext.current
    val notificationDeniedHint = stringResource(R.string.audio_notification_denied)
    val notificationDeniedAction = stringResource(R.string.audio_notification_denied_action)
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted: Boolean ->
        if (!granted) {
            // 被拒（含"不再询问"）时给一个直达系统通知设置页的出口，而不是只提示一句（R18）
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = notificationDeniedHint,
                    actionLabel = notificationDeniedAction,
                )
                if (result == SnackbarResult.ActionPerformed) container.keepAlive.openNotificationSettings()
            }
        }
    }

    /** 首次点音频时申请一次通知权限（已授权则什么都不做）。 */
    fun ensureNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // R12 SAF 模式：系统目录选择器（结果 URI 由容器 takePersistableUriPermission 后落 DataStore）
    // SAF 选择器有两个消费者：① 首页/连接页的"全局本地根目录"；② 连接编辑器里某个本地连接的目录。
    // 用 pendingSafTarget 区分：非 null 表示这次是给编辑器挑的（结果只回调，不改全局根目录）。
    var pendingSafTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val target = pendingSafTarget
        pendingSafTarget = null
        when {
            uri == null -> Unit
            target != null -> target(uri.toString())
            else -> container.onSafTreePicked(uri)
        }
    }

    // R12 全盘模式：用户可能刚从系统设置页返回，回前台就重新读一次授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                container.refreshAllFilesAccess()
                // R18：用户可能刚从系统设置页（省电策略 / 通知）回来，回前台就重查一次保活状态
                container.keepAlive.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // **播放页 / 看图页要铺满整块屏幕**（2026-10-03 真机横屏截图：左边出现一条浅色竖条 =
    // 应用背景从"黑底被内缩"的缝里露出来）。四层保障：① 主题里 windowLayoutInDisplayCutoutMode=always
    // （窗口从创建起就覆盖刘海区）；② 这两个路由不吃 Scaffold 的 innerPadding；③ **Scaffold 背后垫一层黑**
    // （本行，任何内缩都不会再露浅色）；④ 播放期间 decorView 背景刷黑（见 ImmersiveWhilePlaying）。
    val edgeToEdgeRoute = currentDestination?.route?.let { route ->
        route == VideoPlayerRoutes.ROUTE || route == ViewerRoutes.ROUTE
    } ?: false
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (edgeToEdgeRoute) Color.Black else Color.Transparent),
    ) {
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
            // M2-B：视频播放（R1/R4/R9/R10/R18）的宿主能力（双内核 + 回环代理 + 断点存储 + 偏好）
            LocalVideoPlayerEnvironment provides container.videoPlayerEnvironment,
            // M4：连接管理（R6/R7/R8）的宿主能力（三张表 + Keystore 加密 + 当前连接 + 网络现场）
            LocalConnectionsEnvironment provides container.connectionsEnvironment,
        ) {
            // 这两个路由**不吃内边距**（其余页面照旧）——理由见外层 edgeToEdgeRoute 的注释。
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.HOME.navRoute,
                // 外层已经让出了状态栏 / 底部导航的高度，这里把它标记为「已消费」，
                // 否则页面内部的 Scaffold + TopAppBar 会把系统栏再顶一次（内容整体下移一倍）
                modifier = Modifier
                    .padding(if (edgeToEdgeRoute) PaddingValues(0.dp) else innerPadding)
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
                            // R1：图片进查看器（M1-F）、音频进音频播放页（M1-G）、
                            // 视频进视频播放页（M2-B）；其余类型给一句中文提示
                            when (MediaKindGuesser.guess(clicked.name)) {
                                MediaKind.IMAGE -> navController.navigate(ViewerRoutes.route(clicked.path))

                                MediaKind.AUDIO -> {
                                    ensureNotificationPermission()
                                    navController.navigate(AudioPlayerRoutes.route(clicked.path))
                                }

                                MediaKind.VIDEO -> navController.navigate(VideoPlayerRoutes.route(clicked.path))

                                else -> scope.launch { snackbarHostState.showSnackbar(unsupportedHint) }
                            }
                        },
                        onRequestRootAccess = { safPicker.launch(null) },
                        onPathChanged = { lastBrowsedPath = it },
                    )
                }

                // 图片查看器（M1-F，R1）：返回键由页面 BackHandler 回调到这里，回到原目录
                composable(route = ViewerRoutes.ROUTE, arguments = ViewerRoutes.arguments) { entry ->
                    // 看图/看图集同样全屏（2026-10-03：用户要"不要显示任务栏"）
                    ImmersiveWhilePlaying()
                    ImageViewerScreen(
                        path = ViewerRoutes.pathOf(entry.arguments?.getString(ViewerRoutes.ARG_PATH)),
                        onBack = { navController.backToBrowser(lastBrowsedPath) },
                    )
                }

                // 音频播放页（M1-G，R1/R18）：队列由播放页按「同目录音频」自己解析，
                // 路由只带路径；真正的播放器在后台服务里，退到后台/息屏不中断。
                composable(route = AudioPlayerRoutes.ROUTE, arguments = AudioPlayerRoutes.arguments) { entry ->
                    AudioPlayerScreen(
                        path = AudioPlayerRoutes.pathOf(entry.arguments?.getString(AudioPlayerRoutes.ARG_PATH)),
                        onBack = { navController.backToBrowser(lastBrowsedPath) },
                    )
                }

                // 视频播放页（M2-B，R1/R4/R9/R10/R18）：队列由播放页按「同目录视频/音频」自己解析，
                // 路由只带路径；内核实例、回环代理与断点存储都在 AppContainer 里。
                composable(route = VideoPlayerRoutes.ROUTE, arguments = VideoPlayerRoutes.arguments) { entry ->
                    // 播放页沉浸式全屏（2026-10-03 真机截图：横屏看视频时状态栏还占着一条）
                    ImmersiveWhilePlaying()
                    // 亮度：播放页把值报上来，这里写成窗口属性（退出播放页 WindowBrightness 会恢复系统亮度）
                    var playerBrightness by remember { mutableStateOf(-1f) }
                    WindowBrightness(playerBrightness)
                    VideoPlayerScreen(
                        path = VideoPlayerRoutes.pathOf(entry.arguments?.getString(VideoPlayerRoutes.ARG_PATH)),
                        onBack = { navController.backToBrowser(lastBrowsedPath) },
                        onBrightnessChanged = { playerBrightness = it },
                    )
                }

                // 连接管理（M4，R6/R7/R8）：列表 + 新建/编辑/删除 + 测试连通性/测试全部 + 设为当前连接
                composable(TopLevelDestination.CONNECTIONS.navRoute) {
                    ConnectionsRoute(
                        container = container,
                        onPickSafDirectory = { safPicker.launch(null) },
                        onPickLocalDirectory = { callback ->
                            pendingSafTarget = callback
                            safPicker.launch(null)
                        },
                    )
                }
                // 设置（M4，R7/R8）：当前连接入口 + 网络切换策略说明（只读）
                composable(TopLevelDestination.SETTINGS.navRoute) {
                    SettingsRoute(
                        container = container,
                        onNotice = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
                        onOpenConnections = { navController.switchTopLevel(TopLevelDestination.CONNECTIONS) },
                        onRequestNotification = ::ensureNotificationPermission,
                    )
                }
            }
        }
    }
    }
}

/**
 * 播放页期间的**沉浸式全屏**（2026-10-03 真机截图：横屏播放时系统状态栏仍占一条，画面被顶下来）。
 *
 * 进播放页隐藏状态栏与导航栏，从边缘上滑可临时唤出（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）；
 * 离开播放页恢复——只在播放页生效，不影响其它页面。
 */
/**
 * **窗口亮度跟随播放页状态**（2026-10-03 用户要求：左侧上下滑调亮度）。
 *
 * 三条口径：① 拖动过程中实时改（[brightness] 一变就写窗口属性）；
 * ② **退出播放页恢复系统亮度**（`screenBrightness = -1`，用户原话「在不播放视频时需要恢复」）；
 * ③ 记住的值由播放页偏好负责，下次进来 [brightness] 一开始就是上次那个值。
 */
@Composable
private fun WindowBrightness(brightness: Float) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // 只在播放页存在期间生效：离开时恢复"跟随系统"
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            window.attributes = window.attributes.apply { screenBrightness = -1f }
        }
    }
    LaunchedEffect(activity, brightness) {
        val window = activity?.window ?: return@LaunchedEffect
        if (brightness < 0f) return@LaunchedEffect
        window.attributes = window.attributes.apply { screenBrightness = brightness.coerceIn(0.01f, 1f) }
    }
}

@Composable
private fun ImmersiveWhilePlaying() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(activity) {
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
    }
    DisposableEffect(controller) {
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // **窗口背景刷黑 + 允许画进刘海区**（2026-10-03 真机横屏截图：左边一条浅色竖条＝
        // 刘海/手势区没被内容覆盖，露出了窗口背景）。这两下手之后，哪怕还有没被内容盖住的窗口区域，
        // 也是黑的，不会再出现浅色条。
        val window = activity?.window
        val previousBackground = window?.decorView?.background
        val previousCutout = window?.attributes?.layoutInDisplayCutoutMode
        if (window != null) {
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
        onDispose {
            // 退出播放页把系统栏还回来（否则回到首页也是一条光秃秃的全屏）
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.decorView?.background = previousBackground
            if (window != null && previousCutout != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previousCutout }
            }
        }
    }
    // 再补一刀：从桌面切回来、锁屏解锁、或用户上滑把系统栏唤出过之后，重新收起来
    // （只做一次 hide 的话，系统会在这些时机把栏放回来，用户会看到"任务栏又冒出来了"）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** 从 Context 里找 Activity（Compose 里没有现成的）。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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
    val remoteLabel by container.remoteRootLabel.collectAsStateWithLifecycle()
    HomeScreen(
        root = HomeRootUi(
            mode = config?.mode ?: RootModeKind.NONE,
            displayPath = config?.display.orEmpty(),
            allFilesGranted = allFilesGranted,
            remoteLabel = remoteLabel,
        ),
        onOpenKind = onOpenKind,
        onPickSafDirectory = onPickSafDirectory,
        onUseAllFilesRoot = container::useAllFilesRoot,
        onRequestAllFilesAccess = container::requestAllFilesAccess,
        onClearRoot = container::clearRoot,
    )
}

/**
 * 连接页（M4，R6/R7/R8；R12 追加本地目录入口）：把容器里的根目录配置与权限状态映射成页面模型。
 *
 * 为什么要在这里也放本地目录：用户"媒体从哪来"的两个来源（本地 / 远端）本该在同一个页面里管，
 * 原先只有首页能选本地目录，于是"我在连接页想换成手机里的目录"这件事没有入口。
 */
@Composable
private fun ConnectionsRoute(
    container: AppContainer,
    onPickSafDirectory: () -> Unit,
    /** 为本地连接挑目录：:app 拉起 SAF 选择器，结果以 URI 回调给编辑器草稿。 */
    onPickLocalDirectory: ((String) -> Unit) -> Unit,
) {
    val config by container.rootConfig.collectAsStateWithLifecycle()
    val allFilesGranted by container.allFilesGranted.collectAsStateWithLifecycle()
    ConnectionsScreen(
        localRoot = LocalRootUi(
            mode = when (config?.mode) {
                RootModeKind.SAF -> LocalRootMode.SAF
                RootModeKind.ALL_FILES -> LocalRootMode.ALL_FILES
                else -> LocalRootMode.NONE
            },
            displayPath = config?.display.orEmpty(),
            allFilesGranted = allFilesGranted,
            // 原始值：SAF 是树 URI，全盘是绝对路径。编辑器里"用已选的目录"一键沿用
            value = config?.value.orEmpty(),
        ),
        onPickSafDirectory = onPickSafDirectory,
        onUseAllFilesRoot = container::useAllFilesRoot,
        onRequestAllFilesAccess = container::requestAllFilesAccess,
        onClearRoot = container::clearRoot,
        onPickLocalDirectory = onPickLocalDirectory,
    )
}

/**
 * 设置页（M4，R7/R8；M8-A 增加 **R18** 后台与保活）：把容器里的「当前连接」、
 * 网络策略文案与保活引导映射成页面模型。
 *
 * 页面本身只读，不查库、不建后端、不碰权限查询；跳转与状态查询都由这里转给容器
 * （导航控制器与 ActivityResult 启动器在 :app 手里）。
 *
 * @param onRequestNotification 申请通知权限（要 ActivityResult 启动器，只能在 :app 侧发起）。
 */
@Composable
private fun SettingsRoute(
    container: AppContainer,
    onOpenConnections: () -> Unit,
    onRequestNotification: () -> Unit,
    onNotice: (String) -> Unit = {},
) {
    val connection by container.settingsConnection.collectAsStateWithLifecycle()
    val keepAlive by container.keepAlive.guide.collectAsStateWithLifecycle()

    // 应用内更新（R20）：状态机在 :feature:update，宿主能力由 AppContainer 提供
    val updateViewModel: UpdateViewModel = viewModel {
        UpdateViewModel(
            environment = container.updateEnvironment,
            checker = UpdateChecker(container.updateTransport),
            downloader = FileDownloader(container.updateTransport),
        )
    }
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // 网络与缓冲参数（2026-10-03）：设置页里能改，改完即时生效（段大小下次连接生效）
    val networkTuning by container.networkTuning.tuning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()


    // 缓存用量（2026-10-03 用户要求「设置里增加缓存大小按 GB」）：进设置页读一次，清空后刷新
    var cacheUsedBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cacheUsedBytes = container.remoteCacheBytes() }

    // 已信任的 SFTP 主机指纹（R2）：TOFU 落库后要能看到、能忘掉
    val trustedHostKeys by container.trustedHostKeys.collectAsStateWithLifecycle()

    // 崩溃诊断（2026-10-03）：进设置页时读一次最新报告（崩溃会杀进程，所以不需要轮询刷新）
    var crashReport by remember { mutableStateOf(container.readLatestCrash()) }
    var crashTimeMs by remember { mutableStateOf(container.latestCrashTimeMs()) }
    var crashReportCount by remember { mutableStateOf(container.crashReportCount()) }
    // 没有崩溃报告时也要能说清"上次是怎么没的"（2026-10-03 用户反馈"没捕捉到崩溃日志"）
    var lastExit by remember { mutableStateOf(container.lastExitSummary()) }
    var breadcrumbs by remember { mutableStateOf(container.readBreadcrumbs()) }

    LaunchedEffect(updateState.notice) {
        val notice = updateState.notice ?: return@LaunchedEffect
        onNotice(notice)
        updateViewModel.dismissNotice()
    }
    val notes = listOf(
        SettingsNote(
            title = stringResource(R.string.settings_note_order_title),
            detail = stringResource(R.string.settings_note_order_detail),
        ),
        SettingsNote(
            title = stringResource(R.string.settings_note_cache_title),
            detail = stringResource(R.string.settings_note_cache_detail),
        ),
        SettingsNote(
            title = stringResource(R.string.settings_note_credential_title),
            detail = stringResource(R.string.settings_note_credential_detail),
        ),
        SettingsNote(
            title = stringResource(R.string.settings_note_protocol_title),
            detail = stringResource(R.string.settings_note_protocol_detail),
        ),
    )
    SettingsScreen(
        connection = connection,
        notes = notes,
        onOpenConnections = onOpenConnections,
        keepAlive = keepAlive,
        onKeepAliveAction = { action: KeepAliveAction ->
            when (action) {
                KeepAliveAction.REQUEST_NOTIFICATION -> onRequestNotification()
                KeepAliveAction.REQUEST_BATTERY_UNRESTRICTED -> container.keepAlive.requestBatteryUnrestricted()
                KeepAliveAction.OPEN_APP_DETAILS -> container.keepAlive.openAppDetails()
            }
        },
        onKeepAliveConfirm = { kind: KeepAliveItemKind, confirmed: Boolean ->
            container.keepAlive.setConfirmed(kind, confirmed)
        },
        extraSections = {
            // 播放（2026-10-03 用户要求：「可以设置软件在后台时是否显示画中画」）
            val pipAutoEnterNow by container.pipAutoEnterSetting.collectAsStateWithLifecycle()
            PlaybackSection(
                pipAutoEnter = pipAutoEnterNow,
                onPipAutoEnter = { enabled -> scope.launch { container.updatePipAutoEnter(enabled) } },
            )
            // 网络与缓冲（2026-10-03 用户要求）：并发数/块大小/预读/段大小 开放给用户自己调
            NetworkTuningSection(
                tuning = networkTuning,
                onReadAheadSegments = { value -> scope.launch { container.networkTuning.setReadAheadSegments(value) } },
                onSegmentBytes = { value ->
                    scope.launch { container.networkTuning.setSegmentMb((value / (1024 * 1024)).toInt()) }
                },
                onCacheBytes = { value -> scope.launch { container.networkTuning.setCacheMb((value / (1024 * 1024)).toInt()) } },
                cacheUsedBytes = cacheUsedBytes,
                onClearCache = {
                    scope.launch {
                        container.clearRemoteCache()
                        cacheUsedBytes = container.remoteCacheBytes()
                    }
                },
                onReset = { scope.launch { container.networkTuning.reset() } },
            )
            // 诊断（2026-10-03）：崩溃报告看得到、复制得走——真机闪退唯一的定位手段
            DiagnosticsSection(
                crashReport = crashReport,
                crashTimeMs = crashTimeMs,
                reportCount = crashReportCount,
                lastExit = lastExit,
                breadcrumbs = breadcrumbs,
                onClear = {
                    container.clearCrashes()
                    container.clearBreadcrumbs()
                    crashReport = null
                    crashTimeMs = null
                    crashReportCount = 0
                    lastExit = null
                    breadcrumbs = emptyList()
                },
            )
            // SFTP 主机密钥（R2）：TOFU 落库后的展示与"忘掉重来"出口
            TrustedHostKeysSection(
                keys = trustedHostKeys,
                onForget = { key -> container.forgetHostKey(key.host, key.port) },
                onForgetAll = container::forgetAllHostKeys,
            )
            // 语音识别模型（R14）：档位、下载进度、删除、设为当前
            // 应用内更新（R20）：检查 → 下载（断点续传）→ 签名校验 → 系统安装器
            UpdateSection(
                state = updateState,
                onCheck = updateViewModel::check,
                onDownload = updateViewModel::download,
                onCancel = updateViewModel::cancelDownload,
                onInstall = updateViewModel::install,
                onOpenReleases = updateViewModel::openReleasesPage,
            )
        },
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
