package io.github.gua123.mediagate.feature.player.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.RemoteEntry

/** 日志 TAG。 */
private const val TAG = "player-audio"

/**
 * 音频播放页的 ViewModel（R1 音频 / R18 后台播放）。
 *
 * 职责边界：
 * - **列同目录音频** → 交给宿主（[AudioPlayerEnvironment.audioSiblings]，走同一个 StorageBackend）；
 * - **开始播放** → [AudioPlayerEnvironment.play]（真正的播放器在 :app 的 MediaController + 后台服务里）；
 * - **状态** → 订阅宿主推来的 [AudioPlaybackSnapshot]，把「点一下会发生什么」交给 [AudioPlayerMath]
 *   的纯函数（上一首/下一首下标轮转、倍速档位、循环模式）；
 * - **封面** → 走 ThumbnailRepository 的音频内嵌封面分支（R5），解码在 [io] 上，组合函数零 IO；
 * - 进度条拖拽：拖拽中不被播放进度覆盖（[AudioPlayerUiState.dragging]），松手才 [AudioPlayerEnvironment.seekTo]。
 *
 * 所有 IO（列目录 / 取封面 / 解码）都在 [io] 或 viewModelScope 的挂起调用里完成。
 *
 * @param environment 宿主能力（:app 实现，连后台 MediaSessionService）。
 * @param initialPath 进入时播放的音频路径（来自路由参数）。
 * @param decoder 封面解码器；单测注入假实现（真机用 [BitmapAudioCoverDecoder]）。
 * @param io 列目录 / 取封面 / 解码所在的调度器；单测注入测试调度器。
 */
class AudioPlayerViewModel(
    private val environment: AudioPlayerEnvironment,
    private val initialPath: String,
    private val decoder: AudioCoverDecoder = BitmapAudioCoverDecoder(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(AudioPlayerUiState())

    /** 页面唯一状态源（StateFlow，见 plan 第 3 章）。 */
    val state: StateFlow<AudioPlayerUiState> = _state.asStateFlow()

    /** 当前目录的音频目录项（封面要 size/mtime 才能命中缩略图缓存 key）。 */
    private var entries: Map<String, RemoteEntry> = emptyMap()

    private var loadJob: Job? = null

    private var coverJob: Job? = null

    init {
        viewModelScope.launch {
            environment.state.collect { snapshot -> applySnapshot(snapshot) }
        }
        load(initialPath)
    }

    /** 失败后重试（重新列目录并重新开始播放）。 */
    fun retry() = load(initialPath)

    /** 播放 / 暂停。 */
    fun togglePlayPause() = environment.togglePlayPause()

    /** 下一首（下标轮转见 [AudioPlayerMath.nextIndex]）。 */
    fun next() {
        val current = _state.value
        environment.playAt(AudioPlayerMath.nextIndex(current.index, current.queue.size, current.repeatMode))
    }

    /** 上一首。 */
    fun previous() {
        val current = _state.value
        environment.playAt(AudioPlayerMath.previousIndex(current.index, current.queue.size, current.repeatMode))
    }

    /** 倍速轮转：0.5 → 1 → 1.5 → 2 → 0.5。 */
    fun cycleSpeed() = environment.setSpeed(AudioPlayerMath.nextSpeed(_state.value.speed))

    /** 循环模式轮转：顺序 → 列表循环 → 单曲循环。 */
    fun cycleRepeatMode() = environment.setRepeatMode(AudioPlayerMath.nextRepeatMode(_state.value.repeatMode))

    /** 拖拽中：只更新本地状态，不打扰播放器（松手才 seek）。 */
    fun onSeekChange(ratio: Float) {
        _state.update { it.reduce(AudioPlayerEvent.SeekChanged(ratio)) }
    }

    /** 松手：把拖到的位置提交给播放器。 */
    fun onSeekFinished() {
        val duration = _state.value.durationMs
        _state.update { it.reduce(AudioPlayerEvent.SeekFinished) }
        if (duration > 0L) environment.seekTo(_state.value.positionMs)
    }

    // ------------------------------------------------------------------ 内部

    /** 列同目录音频 → 计算起始下标 → 开始播放。 */
    private fun load(path: String) {
        loadJob?.cancel()
        coverJob?.cancel()
        entries = emptyMap()
        _state.update { it.reduce(AudioPlayerEvent.LoadStarted(path)) }
        loadJob = viewModelScope.launch {
            when (val outcome = withContext(io) { listAudio(path) }) {
                is SiblingsOutcome.Failure -> _state.update {
                    it.reduce(AudioPlayerEvent.LoadFailed(outcome.kind, outcome.detail))
                }

                is SiblingsOutcome.Success -> {
                    entries = outcome.entries.associateBy { it.path }
                    _state.update { it.reduce(AudioPlayerEvent.SiblingsLoaded(outcome.entries.size)) }
                    start(outcome.entries.map { it.path }, path)
                }
            }
        }
    }

    /** 交给宿主开始播放；目录里一首音频都没列到时至少播用户点的那首。 */
    private suspend fun start(paths: List<String>, clicked: String) {
        val queue = paths.ifEmpty { listOf(clicked) }
        val index = AudioPlayerMath.indexOfPath(queue, clicked).takeIf { it >= 0 } ?: 0
        try {
            environment.play(queue, index)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "开始播放失败：" + clicked, t)
            _state.update { it.reduce(AudioPlayerEvent.LoadFailed(AudioPlayerErrors.classify(t), t.message)) }
        }
    }

    /** 服务推来新状态：归约 + 必要时换封面。 */
    private fun applySnapshot(snapshot: AudioPlaybackSnapshot) {
        _state.update { it.reduce(AudioPlayerEvent.SnapshotChanged(snapshot)) }
        maybeLoadCover()
    }

    /** 曲目变了就异步取一次封面；同一首不重复取。 */
    private fun maybeLoadCover() {
        val current = _state.value
        val path = current.path
        if (path.isEmpty() || path == current.coverPath) return
        _state.update { it.copy(coverPath = path, cover = null) }
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            val entry = entries[path] ?: RemoteEntry(name = AudioPlayerMath.fileNameOf(path), path = path)
            val cover = withContext(io) { loadCover(entry) }
            // 期间可能已经切歌：过期结果直接丢掉
            if (_state.value.coverPath != path) return@launch
            _state.update { it.reduce(AudioPlayerEvent.CoverLoaded(path, cover)) }
        }
    }

    /** 取封面字节（R5：音频走 MMR 内嵌封面）+ 按封面尺寸解码；失败返回 null。 */
    private suspend fun loadCover(entry: RemoteEntry): AudioCover? = try {
        val backend = environment.backend.value
        if (backend == null) {
            null
        } else {
            val bytes = environment.thumbnails.thumbnail(entry, backend)
            if (bytes == null) null else decoder.decode(bytes, AudioPlayerMath.COVER_TARGET_WIDTH)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.w(TAG, "加载封面失败：" + entry.path, t)
        null
    }

    /** 列同目录音频（异常翻译成分类，不向 UI 抛）。 */
    private suspend fun listAudio(path: String): SiblingsOutcome = try {
        SiblingsOutcome.Success(AudioPlayerMath.audioEntries(environment.audioSiblings(path)))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.w(TAG, "列出同目录音频失败：" + path, t)
        SiblingsOutcome.Failure(AudioPlayerErrors.classify(t), t.message)
    }

    /** 列目录结果（成功 / 失败二选一，避免在协程里做异常控制流）。 */
    private sealed interface SiblingsOutcome {
        data class Success(val entries: List<RemoteEntry>) : SiblingsOutcome
        data class Failure(val kind: AudioPlayerErrorKind, val detail: String?) : SiblingsOutcome
    }
}
