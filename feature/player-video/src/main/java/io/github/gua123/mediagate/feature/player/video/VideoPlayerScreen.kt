package io.github.gua123.mediagate.feature.player.video

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
) {
    val environment = LocalVideoPlayerEnvironment.current
    val viewModel: VideoPlayerViewModel = viewModel(key = path) {
        VideoPlayerViewModel(environment = environment, initialPath = path)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val output by viewModel.videoOutput.collectAsStateWithLifecycle()
    val subtitleCue by viewModel.subtitleCue.collectAsStateWithLifecycle()

    // R13：是否在画中画里（由 :app 的宿主能力给出，页面只读）
    val inPip by environment.pip.isInPip.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }

    /** 字幕面板是否展开（界面本地状态：不进 ViewModel 状态机，R14 面板纯展示）。 */
    var subtitlePanelVisible by remember { mutableStateOf(false) }

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

        // 2) 点按显隐控制层（在画面之上、控制层之下）
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                },
        )

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
                )
            }

            // 4) 字幕面板（R14：轨道列表 + 样式 + 时间轴微调 + 写回）
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

/** 控制层：顶栏（返回 / 标题 / 内核）＋ 底栏（进度、播放暂停、上下集、倍速、解码、缩放、字幕）。 */
@Composable
private fun Controls(
    state: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    onBack: () -> Unit,
    onToggleSubtitlePanel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CONTROL_SCRIM)
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
            EngineChip(state = state, onClick = viewModel::switchEngine)
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CONTROL_SCRIM)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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

            ProgressRow(state = state, onSeek = viewModel::onSeekChange, onSeekFinished = viewModel::onSeekFinished)
            PlaybackRow(state = state, viewModel = viewModel)
            ChipsRow(state = state, viewModel = viewModel, onToggleSubtitlePanel = onToggleSubtitlePanel)
        }
    }
}

/** 可拖拽进度条 + 两侧时间（R4）。 */
@Composable
private fun ProgressRow(
    state: VideoPlayerUiState,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
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
private fun PlaybackRow(state: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::previous, enabled = state.canPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.video_previous),
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(modifier = Modifier.size(20.dp))
        FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(64.dp)) {
            Icon(
                imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (state.playing) R.string.video_pause else R.string.video_play),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(modifier = Modifier.size(20.dp))
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onToggleSubtitlePanel,
            label = { Text(stringResource(R.string.video_subtitle, subtitleChipLabel(state))) },
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
        )
        AssistChip(
            onClick = viewModel::cycleDecoderMode,
            label = { Text(stringResource(R.string.video_decoder, state.decoderLabel)) },
        )
        AssistChip(
            onClick = viewModel::cycleResizeMode,
            label = { Text(stringResource(R.string.video_resize, state.resizeLabel)) },
        )
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
