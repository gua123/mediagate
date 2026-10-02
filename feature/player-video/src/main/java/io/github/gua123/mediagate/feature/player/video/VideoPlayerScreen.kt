package io.github.gua123.mediagate.feature.player.video

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import io.github.gua123.mediagate.media.engine.PlayerEngine
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.util.Locale
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.subtitle.SubtitleAlignment
import io.github.gua123.mediagate.media.subtitle.SubtitleCandidate
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleSource
import io.github.gua123.mediagate.media.subtitle.SubtitleStyle

/** 控制层自动隐藏的等待时长（播放中才计时）。 */
private const val CONTROLS_AUTO_HIDE_MS = 3_500L

/** 时间轴步进按钮：长按多久开始连续微调。 */
private const val SUBTITLE_LONG_PRESS_DELAY_MS = 400L

/** 时间轴步进按钮：长按连续微调的重复间隔。 */
private const val SUBTITLE_REPEAT_INTERVAL_MS = 200L

/** 字幕覆盖层的水平内边距（避免长行顶到屏幕边缘）。 */
private val SUBTITLE_HORIZONTAL_PADDING = 24.dp

/** 控制层压在画面上的深色底（半透明，保证白色文字在任何画面上都可读）。 */
private val CONTROL_SCRIM = Color.Black.copy(alpha = 0.45f)

/**
 * 视频播放页（R1 视频 / R4 拖拽 / R9 多内核 / R10 硬软解 / R16 全中文）。
 *
 * 结构：全屏黑底 → AndroidView 挂引擎给的画面（Media3 是 PlayerView、LibVLC 是 SurfaceView）→
 * 点按显隐的控制层（返回 / 播放暂停 / 可拖拽进度条与时间 / 倍速 / 上下集 / 内核切换 /
 * 解码档位 / 缩放 / 集数）→ 失败面板（播放失败 + 一键切 LibVLC 续播）→ 切换遮罩（正在切换解码器…）。
 *
 * 依赖注入：宿主能力由 :app 通过 [LocalVideoPlayerEnvironment] 注入（内核、回环代理、断点存储、
 * 偏好都在 AppContainer 里）；本页**不碰 Media3 / LibVLC**，也不做任何 IO——位置、时长、缓冲、
 * 内核状态全部来自 ViewModel 的 StateFlow。
 *
 * 画中画（**R13**）：进/出 PIP、PIP 内的播放/暂停与 ±10 秒动作、按 Home 自动进入，
 * 都是 **Activity 级 API**，由 :app 通过 [VideoPipHost] 提供（本页只读 [VideoPipHost.isInPip] 决定
 * 要不要收起控制层、显示"画中画中"角标）。本模块不拿 Activity，也不认识 RemoteAction。
 *
 * @param path 要播放的视频路径（相对根目录，来自路由参数）。
 * @param onBack 返回上一页（系统返回键也走它）。
 */
@Composable
fun VideoPlayerScreen(
    path: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * **亮度变化回调**（2026-10-03 用户要求：左侧上下滑调亮度）。
     *
     * 亮度是"窗口属性"，只有 :app 拿得到 Activity，所以本模块只把值报上去；
     * 默认空实现，单测/预览不必提供。
     */
    onBrightnessChanged: (Float) -> Unit = {},
) {
    val environment = LocalVideoPlayerEnvironment.current
    val viewModel: VideoPlayerViewModel = viewModel(key = path) {
        VideoPlayerViewModel(environment = environment, initialPath = path)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val output by viewModel.videoOutput.collectAsStateWithLifecycle()
    val subtitleCue by viewModel.subtitleCue.collectAsStateWithLifecycle()

    // 亮度：值一变就报给宿主（宿主负责写窗口属性；退出播放页由宿主恢复系统亮度）
    LaunchedEffect(state.brightness) { onBrightnessChanged(state.brightness) }

    // R13：是否在画中画里（由 :app 的宿主能力给出，页面只读）
    val inPip by environment.pip.isInPip.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }

    /** 字幕面板是否展开（界面本地状态：不进 ViewModel 状态机，R14 面板纯展示）。 */
    var subtitlePanelVisible by remember { mutableStateOf(false) }
    // 同文件夹列表（2026-10-03 用户要求）：面板里直接跳到别的文件
    var playlistVisible by remember { mutableStateOf(false) }
    // LibVLC 上次把进程带走过 → 再切之前先弹一句（不是禁止，是告知）
    var vlcWarningVisible by remember { mutableStateOf(false) }
    // 启动探针判定"本机跑不了 LibVLC" → 直接不让切，并给「重新测试」出口
    var vlcUnavailableVisible by remember { mutableStateOf(false) }
    // 离开播放页恢复"跟随系统"方向（见 ResetOrientationOnLeave 的说明）
    ResetOrientationOnLeave()

    BackHandler(enabled = true) { onBack() }

    // 进 PIP 就把控制层与字幕面板收掉：小窗里放不下，留着只会挡住画面（R13）
    LaunchedEffect(inPip) {
        if (inPip) {
            controlsVisible = false
            subtitlePanelVisible = false
        }
    }

    // 播放中 3.5 秒无操作自动隐藏控制层；拖拽中、暂停时、字幕面板展开时不隐藏
    // （否则用户还在面板里挑字幕，控制层就没了）
    LaunchedEffect(controlsVisible, state.playing, state.dragging, subtitlePanelVisible, inPip) {
        if (controlsVisible && state.playing && !state.dragging && !subtitlePanelVisible && !inPip) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 1) 画面：内核给什么就挂什么（同一个内核重挂时 key 不变，不会重复 addView）
        val surface = output
        if (surface != null) {
            key(surface) {
                AndroidView(factory = { mountVideoView(surface) }, modifier = Modifier.fillMaxSize())
            }
        }

        // 2) 点按显隐控制层 + **横滑调进度**（2026-10-03 用户要求：「不弹出控制也能左右滑动调整进度条」）
        //    横滑走独立的手势检测，只更新画面中央的 HUD，不把控制层弹出来
        val dragAccum = remember { FloatArray(1) }
        // **竖直手势**（2026-10-03 用户要求）：左 1/3 调亮度、右 1/3 调音量。
        // 三个区各挂各的手势检测，互不抢事件（中间仍是横滑调进度）。
        val zoneHeight = remember { FloatArray(1) }
        val startValue = remember { FloatArray(1) }
        val verticalDrag = remember { FloatArray(1) }
        val onVerticalStart = { zone: PlayerGestureZone ->
            verticalDrag[0] = 0f
            viewModel.beginVerticalGesture(zone)
            startValue[0] = when (zone) {
                PlayerGestureZone.VOLUME -> state.volume
                else -> if (state.brightness < 0f) 0.5f else state.brightness
            }
        }
        val onVerticalDrag = { zone: PlayerGestureZone, delta: Float ->
            verticalDrag[0] += delta
            viewModel.updateVerticalGesture(
                PlayerGestureMath.applyVerticalDrag(
                    start = startValue[0],
                    deltaPx = verticalDrag[0],
                    height = zoneHeight[0].toInt(),
                    min = if (zone == PlayerGestureZone.VOLUME) 0f else 0.01f,
                    max = if (zone == PlayerGestureZone.VOLUME) PlayerEngine.MAX_VOLUME else 1f,
                ),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { zoneHeight[0] = it.height.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                }
                .pointerInput(state.durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccum[0] = 0f
                            viewModel.beginSeekGesture()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccum[0] += dragAmount
                            viewModel.seekGestureBy(dragAccum[0] / size.width.coerceAtLeast(1))
                        },
                        onDragEnd = { viewModel.commitSeekGesture() },
                        onDragCancel = { viewModel.cancelSeekGesture() },
                    )
                },
        )

        // 亮度区（左 1/3）与音量区（右 1/3）：竖直拖动，各自独立检测
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / 3f)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { onVerticalStart(PlayerGestureZone.BRIGHTNESS) },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            onVerticalDrag(PlayerGestureZone.BRIGHTNESS, delta)
                        },
                        onDragEnd = { viewModel.commitVerticalGesture() },
                        onDragCancel = { viewModel.commitVerticalGesture() },
                    )
                },
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / 3f)
                .align(Alignment.CenterEnd)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { onVerticalStart(PlayerGestureZone.VOLUME) },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            onVerticalDrag(PlayerGestureZone.VOLUME, delta)
                        },
                        onDragEnd = { viewModel.commitVerticalGesture() },
                        onDragCancel = { viewModel.commitVerticalGesture() },
                    )
                },
        )

        // 竖直手势的 HUD：拖动时显示「亮度 40%」「音量 180%」
        state.verticalZone?.let { zone ->
            VerticalGestureHud(
                text = PlayerGestureMath.label(zone, state.verticalValue),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 横滑调进度的 HUD（只显示目标时间与位移量；预览缩略图已按用户要求移除）
        state.gestureSeekMs?.let { target ->
            SeekGestureHud(
                targetMs = target,
                startMs = state.gestureStartMs,
                durationMs = state.durationMs,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.status == VideoPlayerStatus.LOADING) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (state.status == VideoPlayerStatus.ERROR) {
            ErrorPanel(
                state = state,
                onRetry = viewModel::retry,
                onFallback = viewModel::fallbackFromFailure,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.switching || state.blackoutHint) {
            SwitchOverlay(state = state, modifier = Modifier.align(Alignment.Center))
        }

        // 3) 字幕覆盖层（R14）：自己渲染 cue，见文件末尾「字幕渲染路径」说明
        SubtitleOverlay(
            cue = subtitleCue,
            style = state.subtitleStyle,
            modifier = Modifier.matchParentSize(),
        )

        // R13：PIP 里不显示控制层（系统只给一个"展开"按钮，控制交给 PIP 动作按钮）
        if (!inPip) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.matchParentSize(),
            ) {
                Controls(
                    state = state,
                    viewModel = viewModel,
                    onBack = onBack,
                    onToggleSubtitlePanel = {
                        subtitlePanelVisible = !subtitlePanelVisible
                        // 打开面板时按需匹配同目录候选（含远端；R14）
                        if (subtitlePanelVisible) viewModel.openSubtitlePanel()
                    },
                    onOpenPlaylist = { playlistVisible = true },
                    onRequestSwitchEngine = {
                        // 判定收敛在纯函数里（VlcSwitchDecision，有单测）：
                        // 已验证可用 → 直接切；未知（含"上次崩过"）→ 弹窗，主按钮是「先测试再切换」；
                        // 已验证不可用 → 弹窗说明原因，不给切换按钮。
                        when (vlcKernelTap(state.otherEngine == EngineKind.VLC, state.vlcUsable)) {
                            VlcKernelTap.DIRECT -> viewModel.switchEngine()
                            VlcKernelTap.ASK_WITH_TEST -> vlcWarningVisible = true
                            VlcKernelTap.BLOCKED -> vlcUnavailableVisible = true
                        }
                    },
                )
            }

            // LibVLC 不可用提示（2026-10-03 用户建议：启动时先测，不能跑就不让切）
            if (vlcUnavailableVisible) {
                AlertDialog(
                    onDismissRequest = { vlcUnavailableVisible = false },
                    title = { Text(stringResource(R.string.video_vlc_unavailable_title)) },
                    text = { Text(stringResource(R.string.video_vlc_unavailable_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                vlcUnavailableVisible = false
                                viewModel.retestVlc()
                            },
                        ) { Text(stringResource(R.string.video_vlc_unavailable_retest)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { vlcUnavailableVisible = false }) {
                            Text(stringResource(R.string.video_vlc_warning_cancel))
                        }
                    },
                )
            }

            // 切 LibVLC 前的提示（2026-10-03 真机：点内核 → 切 LibVLC → 进程被带走）
            // 主按钮改成「先测试再切换」——用户反馈「我没找到测试内核的地方」，这里就是最自然的入口。
            if (vlcWarningVisible) {
                AlertDialog(
                    onDismissRequest = { vlcWarningVisible = false },
                    title = { Text(stringResource(R.string.video_vlc_warning_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.video_vlc_warning_body))
                            if (state.vlcProbeRunning) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.video_vlc_probe_running))
                                }
                            }
                            state.vlcProbeMessage?.let { message -> Text(message) }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { viewModel.probeThenSwitch() },
                            enabled = !state.vlcProbeRunning,
                        ) { Text(stringResource(R.string.video_vlc_probe_and_switch)) }
                    },
                    dismissButton = {
                        Row {
                            TextButton(
                                onClick = {
                                    vlcWarningVisible = false
                                    viewModel.switchEngine()
                                },
                                enabled = !state.vlcProbeRunning,
                            ) { Text(stringResource(R.string.video_vlc_warning_continue)) }
                            TextButton(onClick = { vlcWarningVisible = false }) {
                                Text(stringResource(R.string.video_vlc_warning_cancel))
                            }
                        }
                    },
                )
            }

            // 4) 同文件夹列表（2026-10-03）：点一行直接跳过去
            if (playlistVisible) {
                PlaylistSheet(
                    state = state,
                    onPick = { index ->
                        playlistVisible = false
                        viewModel.openEpisodeAt(index)
                    },
                    onDismiss = { playlistVisible = false },
                )
            }

            // 5) 字幕面板（R14：轨道列表 + 样式 + 时间轴微调 + 写回）
            if (subtitlePanelVisible) {
                SubtitlePanel(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = { subtitlePanelVisible = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else {
            PipBadge(modifier = Modifier.align(Alignment.TopStart))
        }
    }
}

/**
 * 画中画角标（R13）：明确告诉用户"现在是小窗模式，全屏控制在展开后回来"。
 *
 * 只有一行文字，不拦截点击（PIP 里的手势由系统处理）。
 */
@Composable
private fun PipBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.video_pip_active),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(6.dp)
            .background(CONTROL_SCRIM, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 横滑调进度的 HUD（**2026-10-03 用户要求**：不弹控制也能拖，且要**预览缩略图**）。
 *
 * 预览帧由 :app 抽（拿不到就只显示"±位移 + 目标时间"，拖动本身照常可用）。
 */
/** 竖直手势（亮度/音量）的中央提示：一行字，和其它 HUD 一样半透明黑底白字。 */
@Composable
private fun VerticalGestureHud(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = text, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SeekGestureHud(
    targetMs: Long,
    startMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(CONTROL_SCRIM, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = SeekGestureMath.deltaLabel(targetMs - startMs),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = VideoPlayerMath.formatDuration(targetMs) + " / " + VideoPlayerMath.formatDuration(durationMs),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 播放页芯片的统一配色（**2026-10-03 用户要求**："字体需要白色不然看不清"）。
 *
 * 播放页是黑底，Material3 默认芯片取主题的 onSurfaceVariant（灰），在手机上几乎看不清；
 * 这里统一改成白字 + 半透明白底 + 白色描边，所有芯片都从这里取色，避免漏掉某一个。
 */
@Composable
private fun playerChipColors(): ChipColors = AssistChipDefaults.assistChipColors(
    labelColor = Color.White,
    leadingIconContentColor = Color.White,
    containerColor = Color.White.copy(alpha = 0.16f),
)

/** 芯片描边（同样是白色系）。 */
@Composable
private fun playerChipBorder(): BorderStroke = AssistChipDefaults.assistChipBorder(
    enabled = true,
    borderColor = Color.White.copy(alpha = 0.45f),
)

/** 控制层：顶栏（返回 / 标题 / 内核）＋ 底栏（进度、播放暂停、上下集、倍速、解码、缩放、字幕）。 */
@Composable
private fun Controls(
    state: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    onBack: () -> Unit,
    onToggleSubtitlePanel: () -> Unit,
    onOpenPlaylist: () -> Unit,
    /** 请求切内核（是否要先弹 LibVLC 风险提示由上层决定，它才拿得到那个状态）。 */
    onRequestSwitchEngine: () -> Unit,
) {
    // 横屏是"看电影"的场景：同样的控件在 2.17:1 的屏幕上显得又高又占地
    //（2026-10-03 用户反馈："播放过程中的这个页面占地方太大了"）→ 横屏统一用更紧凑的尺寸
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 极简模式（2026-10-03 用户要求）：只留进度条 + 播放键 + 一个「完整」出口，
    // 顶栏（标题/内核/旋转）与整排档位芯片都不画。
    if (state.simpleMode) {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CONTROL_SCRIM)
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 4.dp),
        ) {
            ProgressRow(
                state = state,
                onSeek = viewModel::onSeekChange,
                onSeekFinished = viewModel::onSeekFinished,
                compact = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.video_pause else R.string.video_play),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::toggleSimpleMode) {
                    Text(stringResource(R.string.video_simple_off), color = Color.White)
                }
            }
        }
      }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CONTROL_SCRIM)
                .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.video_back),
                    tint = Color.White,
                )
            }
            Text(
                text = state.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 旋转（2026-10-03 用户要求）：点一下在横屏/竖屏之间切换；退出播放页恢复"跟随系统"
            RotateButton()
            EngineChip(state = state, onClick = onRequestSwitchEngine)
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CONTROL_SCRIM)
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 2.dp else 8.dp,
                ),
        ) {
            if (state.ended) {
                Text(
                    text = stringResource(R.string.video_ended),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.resumeHint) {
                Text(
                    text = stringResource(R.string.video_resume_hint, state.positionText),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.switchMessage?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.buffering && state.status != VideoPlayerStatus.ERROR) {
                Text(
                    text = stringResource(R.string.video_buffering),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ProgressRow(
                state = state,
                onSeek = viewModel::onSeekChange,
                onSeekFinished = viewModel::onSeekFinished,
                compact = compact,
            )
            PlaybackRow(state = state, viewModel = viewModel, compact = compact)
            ChipsRow(
                state = state,
                viewModel = viewModel,
                onToggleSubtitlePanel = onToggleSubtitlePanel,
                onOpenPlaylist = onOpenPlaylist,
            )

            // 时间戳重建（R3/R11）：进度 + 结束提示
            if (state.timestampRepairRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.timestampRepairPercent > 0) {
                            stringResource(R.string.video_repair_running, state.timestampRepairPercent)
                        } else {
                            stringResource(R.string.video_repair_running_indeterminate)
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // R3：无 PCR 的 TS 提示（拖拽会不准，并指出「修复时间戳」出口）
            state.tsIndexNotice?.let { notice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = notice,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = viewModel::dismissTsIndexNotice) {
                        Text(stringResource(R.string.video_repair_dismiss))
                    }
                }
            }
            state.timestampRepairNotice?.let { notice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = notice,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = viewModel::dismissTimestampRepairNotice) {
                        Text(stringResource(R.string.video_repair_dismiss))
                    }
                }
            }
        }
    }
}

/**
 * 旋转按钮（**2026-10-03 用户要求**："需要修改播放视频时的 ui 包括 旋转屏幕"）。
 *
 * 点一下在横屏/竖屏之间切换；离开播放页时由 [ResetOrientationOnLeave] 恢复"跟随系统"，
 * 免得把整个 App 的方向锁死。
 */
@Composable
private fun RotateButton() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    IconButton(
        onClick = {
            val activity = context.findActivity() ?: return@IconButton
            activity.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        },
    ) {
        Icon(
            imageVector = Icons.Default.ScreenRotation,
            contentDescription = stringResource(
                if (landscape) R.string.video_rotate_to_portrait else R.string.video_rotate_to_landscape,
            ),
            tint = Color.White,
        )
    }
}

/** 离开播放页时把方向恢复成"跟随系统"（否则会一直锁在播放页最后选的那个方向）。 */
@Composable
private fun ResetOrientationOnLeave() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose {
            context.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** 从 Context 里找 Activity（Compose 里拿不到现成的，需要包一层 ContextWrapper）。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 同文件夹列表面板（**2026-10-03 用户要求**："可以显示此播放文件夹内的其他文件，可以在播放时直接跳转"）。
 *
 * 数据就是播放页已有的上下集队列（[VideoPlayerUiState.siblingPaths]，进页面时列过一次目录），
 * 点一行调 [VideoPlayerViewModel.openEpisodeAt] 直接切过去（复用同一个内核，不重建解码器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSheet(
    state: VideoPlayerUiState,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.video_playlist_title, state.count),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (state.siblingPaths.isEmpty()) {
                Text(stringResource(R.string.video_playlist_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(state.siblingPaths) { index, path ->
                        val selected = index == state.siblingIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = VideoPlayerMath.fileNameOf(path),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            supportingContent = if (selected) {
                                { Text(stringResource(R.string.video_playlist_current)) }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable { onPick(index) },
                        )
                    }
                }
            }
        }
    }
}

/** 可拖拽进度条 + 两侧时间（R4）。 */
@Composable
private fun ProgressRow(
    state: VideoPlayerUiState,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    compact: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = state.progress,
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
            // 时长未知时禁用：拖了也没法换算成位置
            enabled = state.durationMs > 0L,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = state.positionText, color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(text = state.durationText, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 上一集 / 播放暂停 / 下一集。 */
@Composable
private fun PlaybackRow(state: VideoPlayerUiState, viewModel: VideoPlayerViewModel, compact: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val skipSize = if (compact) 26.dp else 32.dp
        IconButton(onClick = viewModel::previous, enabled = state.canPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.video_previous),
                tint = Color.White,
                modifier = Modifier.size(skipSize),
            )
        }
        Spacer(modifier = Modifier.size(if (compact) 12.dp else 20.dp))
        // 横屏下 64dp 的圆钮太占高度：缩到 48dp（图标同步缩小），竖屏保持原尺寸
        val buttonSize = if (compact) 48.dp else 64.dp
        val iconSize = if (compact) 26.dp else 32.dp
        FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(buttonSize)) {
            Icon(
                imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (state.playing) R.string.video_pause else R.string.video_play),
                modifier = Modifier.size(iconSize),
            )
        }
        Spacer(modifier = Modifier.size(if (compact) 12.dp else 20.dp))
        IconButton(onClick = viewModel::next, enabled = state.canNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.video_next),
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 字幕按钮文案（R14：关 / 加载中 / 当前轨道名 / 无字幕）。 */
@Composable
private fun subtitleChipLabel(state: VideoPlayerUiState): String = when (state.subtitleStatus) {
    SubtitleStatus.OFF -> stringResource(R.string.video_subtitle_status_off)
    SubtitleStatus.LOADING -> stringResource(R.string.video_subtitle_status_loading)
    SubtitleStatus.EMPTY -> stringResource(R.string.video_subtitle_status_empty)
    SubtitleStatus.READY -> state.subtitleLabel ?: stringResource(R.string.video_subtitle_status_empty)
}

/** 倍速 / 解码档位 / 缩放 / 字幕 / 集数（点一下换一档）。 */
@Composable
private fun ChipsRow(
    state: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    onToggleSubtitlePanel: () -> Unit,
    onOpenPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 极简模式入口（2026-10-03 用户要求：做成极简模式）
        AssistChip(
            onClick = viewModel::toggleSimpleMode,
            label = { Text(stringResource(R.string.video_simple_on)) },
            colors = playerChipColors(),
            border = playerChipBorder(),
        )
        // 循环方式（**2026-10-03 用户要求**：文件夹循环 / 单曲循环 / 随机播放）——点一下换一档
        AssistChip(
            onClick = viewModel::cycleLoopMode,
            label = { Text("循环：" + state.loopMode.zhText) },
            colors = playerChipColors(),
            border = playerChipBorder(),
        )
        // 同文件夹列表（2026-10-03 用户要求）：面板里直接跳到别的文件
        AssistChip(
            onClick = onOpenPlaylist,
            label = { Text(stringResource(R.string.video_playlist)) },
            colors = playerChipColors(),
            border = playerChipBorder(),
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.video_playlist_title, state.count),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        AssistChip(
            onClick = onToggleSubtitlePanel,
            label = { Text(stringResource(R.string.video_subtitle, subtitleChipLabel(state))) },
            colors = playerChipColors(),
            border = playerChipBorder(),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Subtitles,
                    contentDescription = stringResource(R.string.video_subtitle_open),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        AssistChip(
            onClick = viewModel::cycleSpeed,
            label = { Text(stringResource(R.string.video_speed, state.speedLabel)) },
            colors = playerChipColors(),
            border = playerChipBorder(),
        )
        AssistChip(
            onClick = viewModel::cycleDecoderMode,
            label = { Text(stringResource(R.string.video_decoder, state.decoderLabel)) },
            colors = playerChipColors(),
            border = playerChipBorder(),
        )
        AssistChip(
            onClick = viewModel::cycleResizeMode,
            label = { Text(stringResource(R.string.video_resize, state.resizeLabel)) },
            colors = playerChipColors(),
            border = playerChipBorder(),
        )
        // 时间戳重建（R3/R11）：只有 TS 家族才显示这条出口
        if (state.canRepairTimestamps) {
            AssistChip(
                onClick = viewModel::repairTimestamps,
                label = { Text(stringResource(R.string.video_repair_timestamps)) },
                colors = playerChipColors(),
                border = playerChipBorder(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = stringResource(R.string.video_repair_timestamps_hint),
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        if (state.count > 1) {
            Text(
                text = stringResource(R.string.video_queue_position, state.position, state.count),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 内核按钮（R9）：显示当前内核，点一下在 Media3 与 LibVLC 之间互切。 */
@Composable
private fun EngineChip(state: VideoPlayerUiState, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = !state.switching,
        label = { Text(stringResource(R.string.video_engine, state.engineLabel)) },
        colors = playerChipColors(),
        border = playerChipBorder(),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.video_engine_switch_to, state.otherEngineLabel),
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/** 切换遮罩（R9/R10）：显示「正在切换解码器…」与短暂黑屏提示。 */
@Composable
private fun SwitchOverlay(state: VideoPlayerUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CONTROL_SCRIM)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = state.switchMessage ?: stringResource(R.string.video_switching),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (state.blackoutHint) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.video_blackout_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 失败面板（plan 4.6）：中文原因 + 重试 + 「一键切 LibVLC 续播」。 */
@Composable
private fun ErrorPanel(
    state: VideoPlayerUiState,
    onRetry: () -> Unit,
    onFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(CONTROL_SCRIM)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = errorMessage(state.errorKind),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        state.errorDetail?.let { detail ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.video_error_detail, detail),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.video_retry))
            }
            // 只有还能降级时才给按钮：LibVLC 是链条末端，且需要回环代理（plan 4.6）
            if (state.canFallback) {
                Button(onClick = onFallback) {
                    Text(stringResource(R.string.video_fallback))
                }
            }
        }
    }
}

/** 失败分类 → 中文原因（R16）。 */
@Composable
private fun errorMessage(kind: VideoErrorKind?): String = stringResource(
    when (kind) {
        VideoErrorKind.ACCESS_DENIED -> R.string.video_error_access_denied
        VideoErrorKind.NOT_FOUND -> R.string.video_error_not_found
        VideoErrorKind.NOT_SUPPORTED -> R.string.video_error_not_supported
        VideoErrorKind.NETWORK -> R.string.video_error_network
        VideoErrorKind.AUTH -> R.string.video_error_auth
        VideoErrorKind.PLAYBACK -> R.string.video_error_playback
        else -> R.string.video_error_unknown
    },
)

// ---------------------------------------------------------------------------- 字幕（R14）

/**
 * 字幕渲染走的是**覆盖层**（R14；两条路径说明如下）。
 *
 * 1. **引擎自带渲染**：Media3 把外挂轨做成 `MediaItem.SubtitleConfiguration` 交给 PlayerView 里的
 *    SubtitleView；LibVLC 用 `addSlave(Subtitle, …)` + `setSpuDelay`。:media:engine 两条都已实现，
 *    但 Media3 1.11 **没有字幕延迟 API**（用户 ±0.5 s 的微调落不到引擎上），两个内核能表达的样式
 *    （字号 / 描边 / 底部边距）也完全不统一 —— 同一个设置换个内核就变样，违反 R9 的「字幕保持」。
 * 2. **Compose 覆盖层**（本页采用）：ViewModel 为了候选列表与时间轴微调**已经把字幕解析成**
 *    `SubtitleCue`，这里直接按「播放位置 + 偏移」挑出当前 cue 画在画面上，样式字字可控，
 *    两个内核表现完全一致，挑 cue 的逻辑还是纯函数（可 JVM 单测）。
 *
 * 所以本页**不把外挂轨交给引擎渲染**（否则同一条字幕会显示两遍）；引擎侧的
 * `SubtitleTrackController` 保持原样，留给内嵌字幕轨与 M8（画中画里显示引擎字幕）使用。
 */
@Composable
private fun SubtitleOverlay(
    cue: SubtitleCue?,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    val text = cue?.text ?: return
    val alignment = cue.style?.alignment ?: SubtitleAlignment.UNKNOWN
    val boxAlignment = when {
        alignment.isTop -> Alignment.TopCenter
        alignment.isMiddle -> Alignment.Center
        else -> Alignment.BottomCenter
    }
    // ASS 的位置提示：贴顶/居中的字幕不套底部边距，贴底的才套（R14「位置提示」）
    val margin = when {
        alignment.isTop -> Modifier.padding(top = style.bottomMarginDp.dp)
        alignment.isMiddle -> Modifier
        else -> Modifier.padding(bottom = style.bottomMarginDp.dp)
    }
    val density = LocalDensity.current
    val baseStyle = TextStyle(
        color = Color(style.textColorArgb),
        fontSize = style.fontSizeSp.sp,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SUBTITLE_HORIZONTAL_PADDING),
        contentAlignment = boxAlignment,
    ) {
        Box(modifier = margin) {
            // 描边 = 先用描边色画一遍同样文字的轮廓，再把实心文字叠在上面
            if (style.outlineWidthDp > 0f) {
                Text(
                    text = text,
                    style = baseStyle.copy(
                        color = Color(style.outlineColorArgb),
                        drawStyle = Stroke(
                            width = with(density) { style.outlineWidthDp.dp.toPx() },
                            join = StrokeJoin.Round,
                        ),
                    ),
                    textAlign = TextAlign.Center,
                )
            }
            Text(text = text, style = baseStyle, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 字幕面板（R14）：轨道候选（单轨，选了就换）＋ 样式（字号/颜色/描边/边距/字形）
 * ＋ 时间轴 ±0.5 秒微调（可长按连续）＋ 写回/另存。
 *
 * 纯展示：所有动作都转给 [VideoPlayerViewModel]，面板自身不碰 IO、不认识播放器（组合函数零 IO）。
 */
@Composable
private fun SubtitlePanel(
    state: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        color = Color.Black.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.video_subtitle_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(
                        if (state.subtitleEnabled) R.string.video_subtitle_on else R.string.video_subtitle_off,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Switch(checked = state.subtitleEnabled, onCheckedChange = viewModel::setSubtitleEnabled)
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.video_subtitle_close))
                }
            }

            state.subtitleNotice?.let { notice ->
                SubtitleNoticeRow(notice = notice, onDismiss = viewModel::clearSubtitleNotice)
            }

            if (state.subtitleLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.video_subtitle_loading),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = stringResource(R.string.video_subtitle_candidates),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            if (state.subtitleCandidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.video_subtitle_no_candidate),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                state.subtitleCandidates.forEach { candidate ->
                    SubtitleCandidateRow(
                        candidate = candidate,
                        selected = candidate.path == state.subtitlePath,
                        onClick = { viewModel.selectSubtitle(candidate) },
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            Text(
                text = stringResource(R.string.video_subtitle_style),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            LabeledSlider(
                label = stringResource(R.string.video_subtitle_font_size),
                value = state.subtitleStyle.fontSizeSp,
                range = SubtitleStyle.MIN_FONT_SIZE_SP..SubtitleStyle.MAX_FONT_SIZE_SP,
                onValueChange = viewModel::setSubtitleFontSize,
            )
            ColorPaletteRow(
                label = stringResource(R.string.video_subtitle_color),
                colors = SubtitleStyle.TEXT_COLORS,
                selected = state.subtitleStyle.textColorArgb,
                onPick = viewModel::setSubtitleTextColor,
            )
            LabeledSlider(
                label = stringResource(R.string.video_subtitle_outline),
                value = state.subtitleStyle.outlineWidthDp,
                range = SubtitleStyle.MIN_OUTLINE_WIDTH_DP..SubtitleStyle.MAX_OUTLINE_WIDTH_DP,
                onValueChange = viewModel::setSubtitleOutlineWidth,
            )
            ColorPaletteRow(
                label = stringResource(R.string.video_subtitle_outline_color),
                colors = SubtitleStyle.OUTLINE_COLORS,
                selected = state.subtitleStyle.outlineColorArgb,
                onPick = viewModel::setSubtitleOutlineColor,
            )
            LabeledSlider(
                label = stringResource(R.string.video_subtitle_bottom_margin),
                value = state.subtitleStyle.bottomMarginDp,
                range = SubtitleStyle.MIN_BOTTOM_MARGIN_DP..SubtitleStyle.MAX_BOTTOM_MARGIN_DP,
                onValueChange = viewModel::setSubtitleBottomMargin,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.subtitleStyle.bold,
                    onClick = viewModel::toggleSubtitleBold,
                    label = { Text(stringResource(R.string.video_subtitle_bold)) },
                )
                FilterChip(
                    selected = state.subtitleStyle.italic,
                    onClick = viewModel::toggleSubtitleItalic,
                    label = { Text(stringResource(R.string.video_subtitle_italic)) },
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            SubtitleOffsetRow(state = state, viewModel = viewModel)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { viewModel.writeBackSubtitle() },
                    enabled = state.canWriteBackSubtitle,
                ) {
                    Text(stringResource(R.string.video_subtitle_write_back, state.subtitleWriteFormat.label))
                }
                if (state.subtitleWriteFormat != SubtitleFormat.VTT) {
                    TextButton(
                        onClick = { viewModel.writeBackSubtitle(SubtitleFormat.VTT) },
                        enabled = state.canWriteBackSubtitle,
                    ) {
                        Text(stringResource(R.string.video_subtitle_save_as_vtt))
                    }
                }
                // 纯文本稿（R14：只导出台词，没有时间轴）
                TextButton(
                    onClick = { viewModel.writeBackSubtitle(SubtitleFormat.TXT) },
                    enabled = state.canWriteBackSubtitle,
                ) {
                    Text(stringResource(R.string.video_subtitle_save_as_txt))
                }
            }
        }
    }
}

/** 候选行（R14：自动匹配与手动候选共用一行，标签里区分来源）。 */
@Composable
private fun SubtitleCandidateRow(
    candidate: SubtitleCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (candidate.source == SubtitleSource.AUTO) {
                        R.string.video_subtitle_source_auto
                    } else {
                        R.string.video_subtitle_source_manual
                    },
                    candidate.name,
                ),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 字幕提示行（R14：加载失败 / 坏数据 / 写回结果都要看得见，不静默）。 */
@Composable
private fun SubtitleNoticeRow(notice: SubtitleNotice, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = subtitleNoticeText(notice),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.video_subtitle_notice_dismiss))
        }
    }
}

/** 提示分类 → 中文文案（R16）。 */
@Composable
private fun subtitleNoticeText(notice: SubtitleNotice): String {
    val detail = notice.detail.orEmpty()
    return when (notice.kind) {
        SubtitleNoticeKind.LOAD_FAILED ->
            if (detail.isEmpty()) {
                stringResource(R.string.video_subtitle_notice_load_failed_brief)
            } else {
                stringResource(R.string.video_subtitle_notice_load_failed, detail)
            }

        SubtitleNoticeKind.NO_CANDIDATE -> stringResource(R.string.video_subtitle_notice_no_candidate)
        SubtitleNoticeKind.PARSE_DAMAGED -> stringResource(R.string.video_subtitle_notice_damaged, detail)
        SubtitleNoticeKind.WRITE_OK -> stringResource(R.string.video_subtitle_notice_write_ok, detail)
        SubtitleNoticeKind.WRITE_LOCAL -> stringResource(R.string.video_subtitle_notice_write_local, detail)
        SubtitleNoticeKind.WRITE_FAILED -> stringResource(R.string.video_subtitle_notice_write_failed, detail)
    }
}

/** 带标签的滑块（样式调整用；纯展示）。 */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = Color.White, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "%.1f", value),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

/** 色板（R14：文字色 / 描边色可调）。 */
@Composable
private fun ColorPaletteRow(
    label: String,
    colors: List<Int>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.width(12.dp))
        colors.forEach { argb ->
            val isSelected = argb == selected
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(if (isSelected) 28.dp else 22.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .clickable { onPick(argb) },
            )
        }
    }
}

/** 时间轴微调行（R14：±0.5 秒步进；长按按钮连续微调）。 */
@Composable
private fun SubtitleOffsetRow(state: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.video_subtitle_offset),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.width(12.dp))
        SubtitleStepButton(text = "-0.5", onStep = { viewModel.nudgeSubtitle(-1) })
        Text(
            text = stringResource(R.string.video_subtitle_offset_value, state.subtitleOffsetText),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        SubtitleStepButton(text = "+0.5", onStep = { viewModel.nudgeSubtitle(1) })
    }
}

/**
 * 时间轴步进按钮：按下立即走一步，按住 [SUBTITLE_LONG_PRESS_DELAY_MS] 后每
 * [SUBTITLE_REPEAT_INTERVAL_MS] 再走一步（R14 的「长按连续微调」）。
 */
@Composable
private fun SubtitleStepButton(text: String, onStep: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(SUBTITLE_LONG_PRESS_DELAY_MS)
        while (true) {
            onStep()
            delay(SUBTITLE_REPEAT_INTERVAL_MS)
        }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onStep()
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 把引擎给的画面挂进 Compose。
 *
 * 视图由引擎创建（Media3 的 PlayerView / :app 注入给 LibVLC 的 SurfaceView），换内核时是**另一个实例**，
 * 这里的防御性 detach 是为了让「同一个 View 被重新挂载」也不会抛 already has a parent。
 */
private fun mountVideoView(view: View): View {
    (view.parent as? ViewGroup)?.removeView(view)
    if (view.layoutParams == null) {
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    return view
}
