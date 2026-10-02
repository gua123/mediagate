package io.github.gua123.mediagate.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend

/**
 * 目录浏览页（R2 列目录 / R12 双模式 / R5 缩略图 / R16 全中文）。
 *
 * 结构：顶栏（返回上级 + 刷新）→ 面包屑路径栏 → 可选类型筛选条 → 列表 / 空 / 错误 / 未选根目录。
 *
 * 依赖注入方式（**二选一里的 CompositionLocal 方案**）：根目录与缩略图仓库由 :app 通过
 * [LocalBrowserEnvironment] 注入（:app 的 AppContainer），ViewModel 用 `viewModel { }` 工厂就地创建。
 * 好处是 :feature:browser 不需要反向依赖 :app，也不用把一堆参数层层透传。
 *
 * @param initialPath 进入时展示的目录（相对根目录，来自路由参数）。
 * @param initialKind 进入时的类型过滤（首页入口卡片带入）。
 * @param onOpenEntry 打开文件的回调；**为 null 时本页只弹「开发中」提示**，绝不空白或崩溃
 *   （视频/音频播放 M2、图片查看器 M1-F 接入后由 :app 传回调接管）。
 * @param onRequestRootAccess 未选根目录 / 无权限时点「去授权」的回调（:app 拉起目录选择器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    modifier: Modifier = Modifier,
    initialPath: String = "",
    initialKind: MediaKind? = null,
    onOpenEntry: ((RemoteEntry) -> Unit)? = null,
    onRequestRootAccess: () -> Unit = {},
    /** **2026-10-03 用户要求**：当前目录变化时回调（:app 记下来，退出播放时好回到这个文件夹）。 */
    onPathChanged: (String) -> Unit = {},
) {
    val environment = LocalBrowserEnvironment.current
    val viewModel: BrowserViewModel = viewModel {
        BrowserViewModel(environment = environment, initialPath = initialPath, initialFilter = initialKind)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val root by environment.root.collectAsStateWithLifecycle()
    val imageLoader = rememberThumbnailImageLoader(environment.thumbnails)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // **2026-10-03 用户要求**：「系统手势返回修改为返回上级目录」——
    // 不在根目录时，返回手势 = 回到上一级；已在根目录就不拦截，交回系统（退出/回首页）。
    BackHandler(enabled = state.canGoUp) { viewModel.up() }

    // 把当前目录报给 :app（退出播放页时回到这里，而不是回首页重新找文件夹）
    LaunchedEffect(state.path) { onPathChanged(state.path) }

    val placeholders = OpenPlaceholders(
        image = stringResource(R.string.browser_open_todo_image),
        video = stringResource(R.string.browser_open_todo_video),
        audio = stringResource(R.string.browser_open_todo_audio),
        subtitle = stringResource(R.string.browser_open_todo_subtitle),
        other = stringResource(R.string.browser_open_todo_other),
    )

    // 点击分发：目录 → 继续列目录；文件 → 交给 :app（M2/M1-F），没有回调就提示「开发中」
    val onEntryClick: (RemoteEntry) -> Unit = { entry ->
        when {
            entry.isDirectory -> viewModel.open(entry.path)
            onOpenEntry != null -> onOpenEntry(entry)
            else -> scope.launch {
                snackbarHostState.showSnackbar(placeholders.forKind(MediaKindGuesser.guess(entry.name)))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifEmpty { stringResource(R.string.browser_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    // 只在能往上走时给按钮；根目录交给系统返回键（底部导航的顶层目标）
                    if (state.canGoUp) {
                        IconButton(onClick = viewModel::up) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = stringResource(R.string.browser_action_up),
                            )
                        }
                    }
                },
                actions = {
                    // 排序（2026-10-03 用户要求）：方式 + 升降序，选择落盘，换页/重启都记得
                    SortMenu(
                        sort = state.sort,
                        onPick = { viewModel.setSort(it) },
                    )
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.browser_action_refresh),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 根目录名要到第一帧状态才有；都没有时先不画，避免闪一个空面包屑
            if (state.status != BrowserStatus.NO_ROOT && (state.rootLabel.isNotEmpty() || state.path.isNotEmpty())) {
                BreadcrumbRow(crumbs = state.crumbs, onCrumbClick = { viewModel.open(it.path) })
            }
            state.filter?.let { filter ->
                FilterRow(filter = filter, onClear = viewModel::clearFilter)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.status == BrowserStatus.NO_ROOT -> NoRootPanel(onRequestRootAccess)

                    state.status == BrowserStatus.ERROR -> ErrorPanel(
                        state = state,
                        onRetry = viewModel::retry,
                        onRequestAccess = onRequestRootAccess,
                    )

                    state.status == BrowserStatus.LOADING && state.entries.isEmpty() -> LoadingPanel()

                    else -> EntryListPanel(
                        state = state,
                        backend = root?.backend,
                        imageLoader = imageLoader,
                        onRefresh = viewModel::refresh,
                        onEntryClick = onEntryClick,
                    )
                }
            }
        }
    }
}

/** 面包屑路径栏（R2）：点任意一段跳到该层。 */
@Composable
private fun BreadcrumbRow(crumbs: List<BrowserCrumb>, onCrumbClick: (BrowserCrumb) -> Unit) {
    if (crumbs.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items = crumbs) { index, crumb ->
            if (index > 0) {
                Text(
                    text = stringResource(R.string.browser_crumb_separator),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val current = index == crumbs.lastIndex
            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCrumbClick(crumb) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** 类型筛选提示条：点一下清除筛选（回到「全部」）。 */
@Composable
private fun FilterRow(filter: MediaKind, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onClear,
            label = { Text(stringResource(R.string.browser_filter_active, kindLabel(filter))) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.browser_filter_clear),
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

/** 列表区：下拉刷新 + 懒加载列表（缩略图由 Coil 在自己的协程里取，滚出屏幕即取消）。 */
@Composable
private fun EntryListPanel(
    state: BrowserUiState,
    backend: StorageBackend?,
    imageLoader: ImageLoader,
    onRefresh: () -> Unit,
    onEntryClick: (RemoteEntry) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val emptyText = if (state.filteredToEmpty) {
            stringResource(R.string.browser_empty_filtered)
        } else {
            stringResource(R.string.browser_empty_dir)
        }
        val visible = state.visibleEntries
        if (visible.isEmpty() || backend == null) {
            // 可滚动容器才能下拉刷新（空目录也要能刷新）
            Box(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = visible, key = { it.path + "|" + it.name }) { entry ->
                    EntryRow(
                        entry = entry,
                        backend = backend,
                        imageLoader = imageLoader,
                        onClick = { onEntryClick(entry) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

/** 单行：缩略图占位 / 名称 / 大小 / 修改时间 / 类型图标。 */
@Composable
private fun EntryRow(
    entry: RemoteEntry,
    backend: StorageBackend,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val kind = remember(entry.name) { MediaKindGuesser.guess(entry.name) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailSlot(entry = entry, backend = backend, kind = kind, imageLoader = imageLoader)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entryMeta(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.isDirectory) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 缩略图位：底层永远是类型图标，上层叠加 Coil 缩略图。
 *
 * 这样「拿不到缩略图」（音频封面尚未接入、抽帧失败、图片过大…）时自然露出类型图标，
 * 不需要额外的占位分支，也不会出现空白格子。
 */
@Composable
private fun ThumbnailSlot(
    entry: RemoteEntry,
    backend: StorageBackend,
    kind: MediaKind,
    imageLoader: ImageLoader,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kindIcon(entry, kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
        if (!entry.isDirectory && (kind == MediaKind.VIDEO || kind == MediaKind.IMAGE)) {
            val model = remember(entry, backend, kind) { ThumbnailRequest(entry, backend, kind) }
            AsyncImage(
                model = model,
                contentDescription = null,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 行的第二行文案：大小（目录写「文件夹」）+ 修改时间。 */
@Composable
private fun entryMeta(entry: RemoteEntry): String {
    val primary = if (entry.isDirectory) {
        stringResource(R.string.browser_kind_folder)
    } else {
        BrowserFormat.size(entry.size)
    }
    return stringResource(R.string.browser_meta, primary, BrowserFormat.dateTime(entry.mtime))
}

/** 未选根目录（R12）：给引导，不给空白。 */
@Composable
private fun NoRootPanel(onRequestRootAccess: () -> Unit) {
    CenteredMessage(
        icon = Icons.Default.Lock,
        title = stringResource(R.string.browser_no_root_title),
        message = stringResource(R.string.browser_no_root_message),
    ) {
        Button(onClick = onRequestRootAccess) {
            Text(stringResource(R.string.browser_action_pick_root))
        }
    }
}

/**
 * 排序菜单（**2026-10-03 用户要求**：「增加文件文件夹排序功能」）。
 *
 * 两项交互：选排序方式（名称/大小/修改时间/类型）、切升降序。目录永远排在最前（见 [EntrySorter]）。
 */
@Composable
private fun SortMenu(sort: EntrySort, onPick: (EntrySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Default.Sort,
                contentDescription = stringResource(R.string.browser_action_sort, sort.label),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                text = stringResource(R.string.browser_sort_title),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            EntrySortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.zhText) },
                    onClick = {
                        open = false
                        onPick(sort.copy(mode = mode))
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (sort.ascending) R.string.browser_sort_descending else R.string.browser_sort_ascending,
                        ),
                    )
                },
                onClick = {
                    open = false
                    onPick(sort.toggled())
                },
            )
        }
    }
}

/** 出错（R2/R12）：按分类给文案，无权限额外给「去授权」。 */
@Composable
private fun ErrorPanel(
    state: BrowserUiState,
    onRetry: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    val message = when (state.errorKind) {
        BrowserErrorKind.ACCESS_DENIED -> stringResource(R.string.browser_error_access_denied)
        BrowserErrorKind.NOT_FOUND -> stringResource(R.string.browser_error_not_found)
        BrowserErrorKind.NOT_SUPPORTED -> stringResource(R.string.browser_error_not_supported)
        BrowserErrorKind.AUTH_FAILED -> stringResource(R.string.browser_error_auth)
        else -> stringResource(R.string.browser_error_unknown)
    }
    val detail = state.errorDetail?.let { stringResource(R.string.browser_error_detail, it) }.orEmpty()
    // 认证失败是"用户自己能修"的一类：把去哪儿改说清楚（真机反馈：只看到认证失败，不知道该重填密码）
    val authHint = if (state.errorKind == BrowserErrorKind.AUTH_FAILED) {
        stringResource(R.string.browser_error_auth_hint)
    } else {
        ""
    }
    val fullText = listOf(message, detail, authHint).filter { it.isNotEmpty() }.joinToString("\n\n")
    val context = LocalContext.current
    CenteredMessage(
        icon = Icons.Default.ErrorOutline,
        title = message,
        message = listOf(detail, authHint).filter { it.isNotEmpty() }.joinToString("\n\n"),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.errorKind == BrowserErrorKind.ACCESS_DENIED) {
                    Button(onClick = onRequestAccess) {
                        Text(stringResource(R.string.browser_action_request_access))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.browser_action_retry))
                }
            }
            // 2026-10-03 用户要求：错误信息要能复制出去，不然只能截图
            TextButton(onClick = { copyError(context, fullText) }) {
                Text(stringResource(R.string.browser_action_copy_error))
            }
        }
    }
}

/** 把"错误 + 详情 + 指引"一起写进剪贴板（报错时用户可以直接粘给我，不用截图）。 */
private fun copyError(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("mediagate 错误", text))
    Toast.makeText(context, context.getString(R.string.browser_error_copied), Toast.LENGTH_SHORT).show()
}

/** 加载中。 */
@Composable
private fun LoadingPanel() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.browser_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 居中提示（未选根目录 / 出错共用骨架）。 */
@Composable
private fun CenteredMessage(
    icon: ImageVector,
    title: String,
    message: String,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        actions()
    }
}

/** 媒体类型 → 列表图标（R5：按 MediaKindGuesser 粗分流）。 */
private fun kindIcon(entry: RemoteEntry, kind: MediaKind): ImageVector = when {
    entry.isDirectory -> Icons.Default.Folder
    kind == MediaKind.VIDEO -> Icons.Default.VideoFile
    kind == MediaKind.AUDIO -> Icons.Default.AudioFile
    kind == MediaKind.IMAGE -> Icons.Default.Image
    kind == MediaKind.SUBTITLE -> Icons.Default.Subtitles
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** 媒体类型 → 中文名（筛选条用）。 */
@Composable
private fun kindLabel(kind: MediaKind): String = stringResource(
    when (kind) {
        MediaKind.VIDEO -> R.string.browser_kind_video
        MediaKind.AUDIO -> R.string.browser_kind_audio
        MediaKind.IMAGE -> R.string.browser_kind_image
        MediaKind.SUBTITLE -> R.string.browser_kind_subtitle
        MediaKind.OTHER -> R.string.browser_kind_other
    },
)

/**
 * 文件点击的占位提示文案（M1 还没有播放器 / 查看器）。
 *
 * 在组合里一次性取好（stringResource 必须在组合中调用），点击回调里只做纯查表。
 */
private data class OpenPlaceholders(
    val image: String,
    val video: String,
    val audio: String,
    val subtitle: String,
    val other: String,
) {
    fun forKind(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> image
        MediaKind.VIDEO -> video
        MediaKind.AUDIO -> audio
        MediaKind.SUBTITLE -> subtitle
        MediaKind.OTHER -> other
    }
}
