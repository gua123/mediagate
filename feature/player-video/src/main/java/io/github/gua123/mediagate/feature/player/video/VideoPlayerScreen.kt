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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
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

/** 控制层自动隐藏的等待时长（播放中才计时）。 */
// 控制层自动隐藏的秒数改成用户可配置（见 ControlsAutoHide 与设置 → 播放）

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

    /** 记忆操作（旋转方向之类）用；不涉及界面状态，失败也只记日志。 */
    val scope = rememberCoroutineScope()

    // 亮度：值一变就报给宿主（宿主负责写窗口属性；退出播放页由宿主恢复系统亮度）
    LaunchedEffect(state.brightness) { onBrightnessChanged(state.brightness) }

    // R13：是否在画中画里（由 :app 的宿主能力给出，页面只读）
    val inPip by environment.pip.isInPip.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }

    // 同文件夹列表（2026-10-03 用户要求）：面板里直接跳到别的文件
    var playlistVisible by remember { mutableStateOf(false) }
    // LibVLC 上次把进程带走过 → 再切之前先弹一句（不是禁止，是告知）
    var vlcWarningVisible by remember { mutableStateOf(false) }
    // 启动探针判定"本机跑不了 LibVLC" → 直接不让切，并给「重新测试」出口
    var vlcUnavailableVisible by remember { mutableStateOf(false) }
    // 切到**自研引擎**之前先说清能力差异（2026-10-15：它还没有倍速与硬解档位）——
    // 口径是"不做静默降级"：宁可多一次确认，也不让用户切过去发现"点了没反应"
    var nativeNoticeVisible by remember { mutableStateOf(false) }
    // 自检没过（独立进程探针）：不让切，并给「重新测试」出口（与 LibVLC 那套一致）
    var nativeUnavailableVisible by remember { mutableStateOf(false) }
    // **2026-10-03 用户要求**：「记住上一次播放时是横屏还是竖屏」
    //   ① 进播放页：有记录就直接套用（没有才跟随系统）；
    //   ② 点旋转按钮：切换并落盘；
    //   ③ 离开播放页：恢复"跟随系统"（见 ResetOrientationOnLeave）——只记住"播放时"的方向。
    ApplyRememberedOrientation(environment.preferences.lastOrientationValue)
    ResetOrientationOnLeave()

    BackHandler(enabled = true) { onBack() }

    // 进 PIP 就把控制层与字幕面板收掉：小窗里放不下，留着只会挡住画面（R13）
    LaunchedEffect(inPip) {
        if (inPip) {
            controlsVisible = false
        }
    }

    // **2026-10-03 用户要求**：「播放视频时，弹出的窗口，无操作多少秒时才隐藏，有操作时不能隐藏」。
    // 做法两条：① interactionTick 每次触摸/操作都自增 ⇒ 计时重新开始（手指还按着就一直不会隐藏）；
    // ② 有没有面板开着、是不是在拖、是不是暂停，全部交给纯函数 ControlsAutoHide 判定。
    var interactionTick by remember { mutableIntStateOf(0) }
    val interacting = state.dragging || playlistVisible ||
        vlcWarningVisible || vlcUnavailableVisible
    val hideTimeoutMs = ControlsAutoHide.timeoutMs(state.controlsHideSeconds)

    LaunchedEffect(
        controlsVisible,
        state.playing,
        interacting,
        inPip,
        interactionTick,
        hideTimeoutMs,
    ) {
        if (hideTimeoutMs == null) return@LaunchedEffect
        if (!ControlsAutoHide.shouldStartCountdown(state.playing, interacting, inPip, controlsVisible)) {
            return@LaunchedEffect
        }
        delay(hideTimeoutMs)
        controlsVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // 任何触摸（按下/拖动）都算"有操作"：只观察不消费，不影响下层手势
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) interactionTick++
                    }
                }
            },
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
        // **竖直手势**（2026-10-03 用户要求）：左 1/3 调亮度、右 1/3 调音量。
        // 三个区各挂各的手势检测，互不抢事件（中间仍是横滑调进度）。
        val zoneHeight = remember { FloatArray(1) }
        val startValue = remember { FloatArray(1) }
        val verticalDrag = remember { FloatArray(1) }
        // 手势处理器用 pointerInput(Unit) 注册（见下），所以它拿到的是**首次组合时**的 state 快照；
        // 这里用 rememberUpdatedState 保证读到的永远是最新值（亮度/音量会边拖边变）。
        val latestState by rememberUpdatedState(state)
        val onVerticalStart = { zone: PlayerGestureZone ->
            verticalDrag[0] = 0f
            viewModel.beginVerticalGesture(zone)
            startValue[0] = when (zone) {
                PlayerGestureZone.VOLUME -> latestState.volume
                else -> if (latestState.brightness < 0f) 0.5f else latestState.brightness
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
                    detectTapGestures {
                        controlsVisible = !controlsVisible
                        interactionTick++
                    }
                }
                // **一个手势处理器搞定三件事**（2026-10-03 用户要求：「全屏幕部分都可以左右滑动
                // 调整进度而不是只有中间三分之一才可以」）：先按主要方向判定——
                // 横向占优＝调进度（全屏任意位置）；纵向占优才看起始位置：左＝亮度、右＝音量、中间不做事。
                // ⚠️ key 必须是 Unit：**拖动过程中亮度/音量一直在变**，若把它们当 key，pointerInput 会在
                // 手势中途被取消重建 ⇒ 收尾的 commitVerticalGesture() 永远不会执行 ⇒ HUD 永久留在屏幕上
                //（**2026-10-03 真机反馈「调整亮度和音量时，屏幕中间的提示会一直存在」的真因**）。
                // 需要最新状态时用 rememberUpdatedState 取，而不是把值当 key。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        var mode: DragMode? = null
                        var totalDx = 0f
                        var totalDy = 0f
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            if (mode == null) {
                                when (
                                    PlayerGestureMath.dragModeOf(startX, size.width, totalDx, totalDy, slop)
                                ) {
                                    DragMode.SEEK -> {
                                        mode = DragMode.SEEK
                                        viewModel.beginSeekGesture()
                                    }
                                    DragMode.BRIGHTNESS -> {
                                        mode = DragMode.BRIGHTNESS
                                        onVerticalStart(PlayerGestureZone.BRIGHTNESS)
                                    }
                                    DragMode.VOLUME -> {
                                        mode = DragMode.VOLUME
                                        onVerticalStart(PlayerGestureZone.VOLUME)
                                    }
                                    DragMode.NONE -> Unit
                                }
                            }
                            when (mode) {
                                DragMode.SEEK -> {
                                    change.consume()
                                    interactionTick++
                                    viewModel.seekGestureBy(totalDx / size.width.coerceAtLeast(1))
                                }
                                DragMode.BRIGHTNESS, DragMode.VOLUME -> {
                                    change.consume()
                                    interactionTick++
                                    onVerticalDrag(
                                        if (mode == DragMode.BRIGHTNESS) PlayerGestureZone.BRIGHTNESS
                                        else PlayerGestureZone.VOLUME,
                                        delta.y,
                                    )
                                }
                                else -> Unit
                            }
                        }
                        // 收尾：正常抬手走这里；**手势被取消（协程撤销/事件被上层抢走）时 finally 兜底**，
                        // 否则 HUD 会一直挂在中屏（2026-10-03 修的那条真机反馈）。commit 是幂等的
                        //（第二次调用时 verticalZone 已为 null，直接返回），重复调用无害。
                        try {
                            when (mode) {
                                DragMode.SEEK -> viewModel.commitSeekGesture()
                                DragMode.BRIGHTNESS, DragMode.VOLUME -> viewModel.commitVerticalGesture()
                                else -> Unit
                            }
                        } finally {
                            if (mode == DragMode.BRIGHTNESS || mode == DragMode.VOLUME) {
                                viewModel.commitVerticalGesture()
                            }
                        }
                    }
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
                    onOpenPlaylist = { playlistVisible = true },
                    onRequestSwitchEngine = {
                        if (state.otherEngine == EngineKind.NATIVE) {
                            // 自研引擎：① 自检没过就**先拦住**（独立进程测出来的，别让用户踩坑）；
                            // ② 自检过了也要先告知能力差异（还没有倍速/硬解档位），确认后再切。
                            if (state.engineUsable == false) nativeUnavailableVisible = true else nativeNoticeVisible = true
                        } else {
                            // 判定收敛在纯函数里（VlcSwitchDecision，有单测）：
                            // 已验证可用 → 直接切；未知（含"上次崩过"）→ 弹窗，主按钮是「先测试再切换」；
                            // 已验证不可用 → 弹窗说明原因，不给切换按钮。
                            when (vlcKernelTap(state.otherEngine == EngineKind.VLC, state.vlcUsable)) {
                                VlcKernelTap.DIRECT -> viewModel.switchEngine()
                                VlcKernelTap.ASK_WITH_TEST -> vlcWarningVisible = true
                                VlcKernelTap.BLOCKED -> vlcUnavailableVisible = true
                            }
                        }
                    },
                    // 记住这次旋转的结果（2026-10-03 用户要求：上次横屏下次还是横屏）
                    onRotated = { orientation ->
                        scope.launch {
                            runCatching { environment.preferences.setLastOrientation(orientation) }
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
            // 自研引擎自检没过：说明原因 + 给「重新测试」（**不让切**，免得进去就播不了）
            if (nativeUnavailableVisible) {
                AlertDialog(
                    onDismissRequest = { nativeUnavailableVisible = false },
                    title = { Text(stringResource(R.string.video_native_unavailable_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(
                                    R.string.video_native_unavailable_body,
                                    state.engineProbeReason ?: stringResource(R.string.video_native_unavailable_unknown),
                                ),
                            )
                            if (state.engineProbeRunning) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.video_native_probe_running))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { viewModel.retestEngine() },
                            enabled = !state.engineProbeRunning,
                        ) { Text(stringResource(R.string.video_native_unavailable_retest)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { nativeUnavailableVisible = false }) {
                            Text(stringResource(R.string.video_native_engine_cancel))
                        }
                    },
                )
            }

            // 自研引擎的能力告知（2026-10-15）：确认后按同一套切换流程换内核
            if (nativeNoticeVisible) {
                AlertDialog(
                    onDismissRequest = { nativeNoticeVisible = false },
                    title = { Text(stringResource(R.string.video_native_engine_title)) },
                    text = { Text(stringResource(R.string.video_native_engine_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                nativeNoticeVisible = false
                                viewModel.switchEngine()
                            },
                        ) { Text(stringResource(R.string.video_native_engine_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { nativeNoticeVisible = false }) {
                            Text(stringResource(R.string.video_native_engine_cancel))
                        }
                    },
                )
            }

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
    onOpenPlaylist: () -> Unit,
    /** 请求切内核（是否要先弹 LibVLC 风险提示由上层决定，它才拿得到那个状态）。 */
    onRequestSwitchEngine: () -> Unit,
    /** 用户点了旋转按钮（上层负责落盘"上次的方向"，2026-10-03 用户要求）。 */
    onRotated: (PlayerOrientation) -> Unit = {},
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
            RotateButton(onRotated = onRotated)
            // 只有一个内核时不给「内核」按钮（2026-10-15：自研引擎与 LibVLC 下线，只剩 Media3）
            if (state.canSwitchEngine) {
                EngineChip(state = state, onClick = onRequestSwitchEngine)
            }
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
private fun RotateButton(onRotated: (PlayerOrientation) -> Unit = {}) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    IconButton(
        onClick = {
            val activity = context.findActivity() ?: return@IconButton
            val target = PlayerOrientation.ofLandscape(landscape).toggled()
            activity.requestedOrientation = target.toActivityInfo()
            // 记住这次的选择（2026-10-03 用户要求：下次播放沿用）
            onRotated(target)
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

/** 方向枚举 → ActivityInfo 的常量（只在这一个地方映射，免得两处各写一遍）。 */
private fun PlayerOrientation.toActivityInfo(): Int = when (this) {
    PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
}

/**
 * 进播放页时套用"上次播放的方向"（**2026-10-03 用户要求**：记住横屏还是竖屏）。
 *
 * null ＝没有记录 ⇒ 什么都不做，跟随系统；离开播放页仍由 [ResetOrientationOnLeave] 恢复系统方向，
 * 所以首页/浏览页不会被锁在横屏。
 */
@Composable
private fun ApplyRememberedOrientation(remembered: PlayerOrientation?) {
    val context = LocalContext.current
    LaunchedEffect(remembered) {
        val target = remembered ?: return@LaunchedEffect
        context.findActivity()?.requestedOrientation = target.toActivityInfo()
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
                // **2026-10-03 用户要求**：打开面板自动跳到"当前正在播的那一条"，而不是从最上边开始
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = PlaylistScroll.firstVisibleIndex(
                        currentIndex = state.siblingIndex,
                        count = state.siblingPaths.size,
                    ),
                )
                // 面板开着的时候换了集（点别的 / 自动续播），列表跟着滚过去
                LaunchedEffect(state.siblingIndex, state.siblingPaths.size) {
                    if (state.siblingPaths.isNotEmpty()) {
                        listState.animateScrollToItem(
                            PlaylistScroll.firstVisibleIndex(state.siblingIndex, state.siblingPaths.size),
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                ) {
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

/** 倍速 / 解码档位 / 缩放 / 字幕 / 集数（点一下换一档）。 */
@Composable
private fun ChipsRow(
    state: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
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
        // 倍速 / 解码档位：内核如实声明不支持时**不显示芯片**（点了也没用），
        // 原因写在下面那行说明里（项目红线：控件不可用要写原因，不能让人以为按钮丢了）
        if (state.canChangeSpeed) {
            AssistChip(
                onClick = viewModel::cycleSpeed,
                label = { Text(stringResource(R.string.video_speed, state.speedLabel)) },
                colors = playerChipColors(),
                border = playerChipBorder(),
            )
        }
        if (state.canChangeDecoder) {
            AssistChip(
                onClick = viewModel::cycleDecoderMode,
                label = { Text(stringResource(R.string.video_decoder, state.decoderLabel)) },
                colors = playerChipColors(),
                border = playerChipBorder(),
            )
        }
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
        state.capsNotice?.let { notice ->
            Text(
                text = notice,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
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

/**
 * 内核按钮（R9）：显示当前内核，点一下**轮转**到下一档
 * （Media3 → 自研引擎 → LibVLC → Media3；LibVLC 不可用时跳过它）。
 */
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
