package io.github.gua123.mediagate.feature.player.audio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 音频播放页（R1 音频 / R18 后台播放 / R16 全中文）。
 *
 * 结构：顶栏（返回）→ 封面（内嵌封面，拿不到就渐变底 + 首字母）→ 标题/路径/队列位置 →
 * 进度条（可拖拽 seek）→ 上一首 / 播放暂停 / 下一首 → 倍速与循环模式。
 *
 * 依赖注入：宿主能力由 :app 通过 [LocalAudioPlayerEnvironment] 注入（AppContainer 持有
 * MediaController 与后台服务连接）；本页**不碰 Media3**，也不做任何 IO——封面、时长、
 * 播放状态全部来自 ViewModel 的 StateFlow。
 *
 * @param path 要播放的音频路径（相对根目录，来自路由参数）。
 * @param onBack 返回上一页（系统返回键也走它）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    path: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val environment = LocalAudioPlayerEnvironment.current
    val viewModel: AudioPlayerViewModel = viewModel(key = path) {
        AudioPlayerViewModel(environment = environment, initialPath = path)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audio_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.audio_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoverBox(state = state)
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = state.title.ifEmpty { stringResource(R.string.audio_loading) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (state.path.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.count > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.audio_queue_position, state.position, state.count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            ProgressRow(state = state, onSeek = viewModel::onSeekChange, onSeekFinished = viewModel::onSeekFinished)
            Spacer(modifier = Modifier.height(8.dp))
            ControlsRow(state = state, viewModel = viewModel)
            Spacer(modifier = Modifier.height(12.dp))
            OptionsRow(state = state, viewModel = viewModel)
            Spacer(modifier = Modifier.height(16.dp))

            when {
                state.status == AudioPlayerStatus.ERROR -> ErrorPanel(state = state, onRetry = viewModel::retry)
                state.buffering -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.audio_buffering),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 封面（R5）：内嵌封面优先，拿不到就渐变底 + 标题首字母。 */
@Composable
private fun CoverBox(state: AudioPlayerUiState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .size(260.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = (state.cover as? BitmapAudioCover)?.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = state.title.take(1),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 进度条（R1：可拖拽 seek）+ 两侧时间。 */
@Composable
private fun ProgressRow(
    state: AudioPlayerUiState,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = state.positionText, style = MaterialTheme.typography.labelMedium)
            Text(text = state.durationText, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 上一首 / 播放暂停 / 下一首。 */
@Composable
private fun ControlsRow(state: AudioPlayerUiState, viewModel: AudioPlayerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::previous, enabled = state.canSwitch) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.audio_previous),
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
            Icon(
                imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (state.playing) R.string.audio_pause else R.string.audio_play,
                ),
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        IconButton(onClick = viewModel::next, enabled = state.canSwitch) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.audio_next),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** 倍速与循环模式（点一下换一档）。 */
@Composable
private fun OptionsRow(state: AudioPlayerUiState, viewModel: AudioPlayerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = viewModel::cycleSpeed,
            label = { Text(stringResource(R.string.audio_speed_value, state.speedLabel)) },
        )
        Spacer(modifier = Modifier.size(12.dp))
        AssistChip(
            onClick = viewModel::cycleRepeatMode,
            label = { Text(repeatLabel(state.repeatMode)) },
            leadingIcon = {
                Icon(
                    imageVector = when (state.repeatMode) {
                        AudioRepeatMode.ONE -> Icons.Default.RepeatOne
                        else -> Icons.Default.Repeat
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

/** 失败提示（R1）：按分类给中文原因，并给「重试」。 */
@Composable
private fun ErrorPanel(state: AudioPlayerUiState, onRetry: () -> Unit) {
    val message = when (state.errorKind) {
        AudioPlayerErrorKind.ACCESS_DENIED -> stringResource(R.string.audio_error_access_denied)
        AudioPlayerErrorKind.NOT_FOUND -> stringResource(R.string.audio_error_not_found)
        AudioPlayerErrorKind.NOT_SUPPORTED -> stringResource(R.string.audio_error_not_supported)
        AudioPlayerErrorKind.NETWORK -> stringResource(R.string.audio_error_network)
        AudioPlayerErrorKind.AUTH -> stringResource(R.string.audio_error_auth)
        else -> stringResource(R.string.audio_error_unknown)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        state.errorDetail?.let { detail ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.audio_error_detail, detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.audio_retry))
        }
    }
}

/** 循环模式 → 中文文案（R16）。 */
@Composable
private fun repeatLabel(mode: AudioRepeatMode): String = stringResource(
    when (mode) {
        AudioRepeatMode.OFF -> R.string.audio_repeat_off
        AudioRepeatMode.ALL -> R.string.audio_repeat_all
        AudioRepeatMode.ONE -> R.string.audio_repeat_one
    },
)
