package io.github.gua123.mediagate.feature.player.video

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

/** 控制层自动隐藏的等待时长（播放中才计时）。 */
private const val CONTROLS_AUTO_HIDE_MS = 3_500L

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
 * 本页不做画中画（R13 属 M8），也不做字幕设置面板（R14 属 M7）。
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
    var controlsVisible by remember { mutableStateOf(true) }

    BackHandler(enabled = true) { onBack() }

    // 播放中 3.5 秒无操作自动隐藏控制层；拖拽中与暂停时不隐藏（免得拖到一半控件消失）
    LaunchedEffect(controlsVisible, state.playing, state.dragging) {
        if (controlsVisible && state.playing && !state.dragging) {
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

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            Controls(state = state, viewModel = viewModel, onBack = onBack)
        }
    }
}

/** 控制层：顶栏（返回 / 标题 / 内核）＋ 底栏（进度、播放暂停、上下集、倍速、解码、缩放）。 */
@Composable
private fun Controls(
    state: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
    onBack: () -> Unit,
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
            ChipsRow(state = state, viewModel = viewModel)
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

/** 倍速 / 解码档位 / 缩放 / 集数（点一下换一档）。 */
@Composable
private fun ChipsRow(state: VideoPlayerUiState, viewModel: VideoPlayerViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
