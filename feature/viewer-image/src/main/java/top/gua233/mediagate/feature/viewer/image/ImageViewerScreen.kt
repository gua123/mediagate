package io.github.gua123.mediagate.feature.viewer.image

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 图片查看页（**M1-F**，R1「图片可缩放翻页」）。
 *
 * 能力：全屏黑底显示 / 双指缩放 + 拖动 + 双击放大 / 左右滑动或按钮翻页（显示「第 i / n 张」）/
 * 加载中转圈 / 失败按 [ViewerErrorKind] 给中文原因 / 返回键退出（回到原来的目录）。
 *
 * 依赖注入：与 `:feature:browser` 同一范式——:app 通过 [LocalImageViewerEnvironment] 提供
 * [ImageViewerEnvironment]，ViewModel 用 `viewModel { }` 工厂就地创建，本模块不反向依赖 :app。
 *
 * 线程：组合函数里**零 IO**；采样率按屏幕尺寸算（[LocalContext] 的 displayMetrics），
 * 真正的读字节与解码都在 [ImageViewerViewModel] 的 IO 协程里。
 *
 * @param path 要展示的图片路径（相对根目录，来自路由参数）。
 * @param onBack 返回回调（:app 传 `navController.popBackStack()`）；返回后仍在原目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    path: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val environment = LocalImageViewerEnvironment.current
    val context = LocalContext.current
    // 屏幕尺寸只用于「按屏采样解码」；取一次即可，窗口尺寸变化极少，不值得为它重建 ViewModel
    val metrics = remember(context) { context.resources.displayMetrics }
    val viewModel: ImageViewerViewModel = viewModel {
        ImageViewerViewModel(
            environment = environment,
            initialPath = path,
            viewportWidthPx = metrics.widthPixels,
            viewportHeightPx = metrics.heightPixels,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rootLabel by environment.rootLabel.collectAsStateWithLifecycle()

    // 返回键退出查看器（R1）；:app 的回调负责 popBackStack，回到原来的目录
    BackHandler(enabled = true) { onBack() }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        topBar = { ViewerTopBar(state = state, rootLabel = rootLabel, onBack = onBack) },
        bottomBar = {
            if (state.hasPages) {
                ViewerPageBar(state = state, onPrev = viewModel::prev, onNext = viewModel::next)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val image = state.image
            when {
                state.status == ViewerStatus.ERROR -> ViewerErrorPanel(state = state, onRetry = viewModel::retry)
                image == null -> ViewerLoadingPanel()
                else -> ZoomableImage(
                    image = image,
                    pathKey = state.path,
                    canPage = state.hasPages,
                    onSwipeNext = viewModel::next,
                    onSwipePrev = viewModel::prev,
                )
            }
        }
    }
}

/** 顶栏：文件名 + 根目录名 + 返回。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(state: ViewerUiState, rootLabel: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = state.name.ifEmpty { stringResource(R.string.viewer_title) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (rootLabel.isNotEmpty()) {
                    Text(
                        text = rootLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        navigationIcon = {
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.viewer_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
        ),
    )
}

/** 底部翻页条：上一张 / 第 i / n 张 / 下一张（到头即置灰）。 */
@Composable
private fun ViewerPageBar(state: ViewerUiState, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onPrev, enabled = state.canGoPrev) {
            Text(text = stringResource(R.string.viewer_prev))
        }
        Text(
            text = stringResource(R.string.viewer_page_indicator, state.position, state.count),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onNext, enabled = state.canGoNext) {
            Text(text = stringResource(R.string.viewer_next))
        }
    }
}

/**
 * 可缩放 / 可拖动 / 可双击放大的图片区（R1）。
 *
 * 手势只有一个 [pointerInput] 处理器，避免「缩放手势吃掉翻页手势」这类冲突：
 * - 两指缩放：钳在 [ViewerMath.MIN_SCALE]..[ViewerMath.MAX_SCALE]；
 * - 放大后单指拖动 = 平移画面，位移被 [ViewerMath.clampOffset] 钳住（拖不出黑边）；
 * - 未放大时的横向拖动：手势结束时若超过阈值就翻页（[ViewerMath.shouldTurnPage]）；
 * - 双击：在 1 倍与 [ViewerMath.DOUBLE_TAP_SCALE] 之间切换。
 *
 * UI 只认 [BitmapDecodedImage]（真机的解码器就是它）；拿到别的实现说明注入错了，直接不画。
 */
@Composable
private fun ZoomableImage(
    image: DecodedImage,
    pathKey: String,
    canPage: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
) {
    val bitmap: Bitmap = (image as? BitmapDecodedImage)?.bitmap ?: return
    // 放大状态按图片重置：翻到下一张永远从「适配屏幕」开始
    var scale by remember(pathKey) { mutableFloatStateOf(ViewerMath.MIN_SCALE) }
    var offsetX by remember(pathKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(pathKey) { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val content = ViewerMath.fitSize(
        contentWidth = bitmap.width.toFloat(),
        contentHeight = bitmap.height.toFloat(),
        viewportWidth = viewport.width.toFloat(),
        viewportHeight = viewport.height.toFloat(),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(pathKey, canPage, content) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragX = 0f
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f) {
                            scale = ViewerMath.clampScale(scale * zoomChange)
                            offsetX = ViewerMath.clampOffset(offsetX, content.width * scale, viewport.width.toFloat())
                            offsetY = ViewerMath.clampOffset(offsetY, content.height * scale, viewport.height.toFloat())
                            if (scale <= ViewerMath.MIN_SCALE) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                        if (panChange != Offset.Zero) {
                            if (scale > ViewerMath.MIN_SCALE) {
                                offsetX = ViewerMath.clampOffset(
                                    offset = offsetX + panChange.x,
                                    contentSize = content.width * scale,
                                    viewportSize = viewport.width.toFloat(),
                                )
                                offsetY = ViewerMath.clampOffset(
                                    offset = offsetY + panChange.y,
                                    contentSize = content.height * scale,
                                    viewportSize = viewport.height.toFloat(),
                                )
                            } else {
                                dragX += panChange.x
                            }
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })
                    // 手势结束：没放大且横向滑够远 → 翻页（左滑下一张、右滑上一张）
                    if (canPage && ViewerMath.shouldTurnPage(scale, dragX)) {
                        if (dragX < 0f) onSwipeNext() else onSwipePrev()
                    }
                }
            }
            .pointerInput(pathKey) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > ViewerMath.MIN_SCALE) {
                            scale = ViewerMath.MIN_SCALE
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = ViewerMath.DOUBLE_TAP_SCALE
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}

/** 加载中（大图解码要几百毫秒，必须给反馈）。 */
@Composable
private fun ViewerLoadingPanel() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.White)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.viewer_loading),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 失败提示（R1）：按分类给中文原因，附后端详情与「重试」。 */
@Composable
private fun ViewerErrorPanel(state: ViewerUiState, onRetry: () -> Unit) {
    val message = when (state.errorKind) {
        ViewerErrorKind.ACCESS_DENIED -> stringResource(R.string.viewer_error_access_denied)
        ViewerErrorKind.NOT_FOUND -> stringResource(R.string.viewer_error_not_found)
        ViewerErrorKind.NOT_SUPPORTED -> stringResource(R.string.viewer_error_not_supported)
        ViewerErrorKind.NETWORK -> stringResource(R.string.viewer_error_network)
        ViewerErrorKind.AUTH -> stringResource(R.string.viewer_error_auth)
        ViewerErrorKind.TOO_LARGE -> stringResource(R.string.viewer_error_too_large)
        ViewerErrorKind.DECODE_FAILED -> stringResource(R.string.viewer_error_decode)
        else -> stringResource(R.string.viewer_error_unknown)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        val detail = state.errorDetail
        if (!detail.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.viewer_error_detail, detail),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry) {
            Text(text = stringResource(R.string.viewer_retry))
        }
    }
}
