package io.github.gua123.mediagate.feature.player.video

import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineState
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.engine.SwitchReason
import io.github.gua123.mediagate.media.playback.PlaybackProgress

/** 日志 TAG。 */
private const val TAG = "player-video"

/**
 * 视频播放页的 ViewModel（R4 拖拽 / R9 多内核 / R10 硬软解 / R18 断点续播）。
 *
 * 职责边界：
 * - **造/换内核**交给宿主（[VideoPlayerEnvironment.createEngine]）：Media3 的数据源、LibVLC 的回环代理
 *   与视频输出视图都在 :app 侧，本模块只持有 [PlayerEngine] 抽象；
 * - **状态**：内核状态机（StateFlow）由 [observe] 收敛成 [VideoPlayerUiState]，位置/时长/缓冲由
 *   [startTicker] 定时采样（Media3 与 LibVLC 都没有统一的进度回调，轮询是两个内核都成立的做法）；
 * - **切换**（R9/R10）：读现场 → [VideoEngineSwitch.planFor] 出计划 → 释放旧内核 → 建新内核 →
 *   按计划恢复；切换期间状态为 [VideoPlayerUiState.switching]（页面显示「正在切换解码器…」）；
 * - **降级**（plan 4.6）：内核报 [EngineState.Error] 时给出「一键切 LibVLC 续播」
 *   （[fallbackFromFailure]，判定与 PlayerEngineSwitcher 同一份纯逻辑，位置用 UI 观测值兜底）；
 * - **断点续播**（R18）：进入时读一次、播放中每 5 秒写一次、暂停补写一次、[onCleared] 再写一次；
 * - **拖拽**（R4）：拖拽中不被进度回调覆盖（[VideoPlayerEvent.SeekChanged]），松手才 seek 内核。
 *
 * 线程约定：所有内核调用都发生在 viewModelScope（主线程）上；读偏好、列目录、读写断点走 [io]。
 *
 * @param environment 宿主能力（:app 实现）。
 * @param initialPath 进入时要播的视频路径（来自路由参数）。
 * @param io 列目录 / 断点读写 / 偏好写入所在的调度器；单测注入测试调度器。
 * @param tickMs 进度采样间隔（毫秒）；单测用虚拟时间推进。
 * @param exitScope onCleared 时最后一次写进度用的作用域（此时 viewModelScope 已被取消）。
 */
class VideoPlayerViewModel(
    private val environment: VideoPlayerEnvironment,
    private val initialPath: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = TICK_MS,
    private val exitScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ViewModel() {

    private val _state = MutableStateFlow(VideoPlayerUiState())

    /** 页面唯一状态源（StateFlow，见 plan 第 3 章）。 */
    val state: StateFlow<VideoPlayerUiState> = _state.asStateFlow()

    private val _videoOutput = MutableStateFlow<View?>(null)

    /**
     * 当前内核的视频输出视图：ExoPlayer 是它自己懒建的 PlayerView，LibVLC 是 :app 注入的 SurfaceView。
     *
     * 单独一条流而不是塞进 [VideoPlayerUiState]：View 不是纯数据，放进去会让状态归约没法纯 JVM 测。
     */
    val videoOutput: StateFlow<View?> = _videoOutput.asStateFlow()

    private var engine: PlayerEngine? = null

    /** 当前播放源（断点续播的 key 与换集都要它）。 */
    private var source: MediaSourceRef? = null

    /** 回环代理是否可用（决定能不能切 LibVLC / 能不能给降级动作），进页面时读一次。 */
    private var proxyAvailable: Boolean = false

    private var loadJob: Job? = null
    private var engineJob: Job? = null
    private var tickJob: Job? = null
    private var switchJob: Job? = null
    private var blackoutJob: Job? = null
    private var resumeJob: Job? = null
    private var msSinceSave: Long = 0L
    private var wasPlaying: Boolean = false
    private var endedSaved: Boolean = false

    init {
        load(initialPath)
    }

    // ------------------------------------------------------------------ 页面动作

    /** 失败后重试（释放现有内核，按当前路径重新来一遍）。 */
    fun retry() {
        reload(_state.value.path.ifEmpty { initialPath })
    }

    /** 播放 / 暂停；已经播完时先回到开头再播。 */
    fun togglePlayPause() {
        val target = engine ?: return
        if (target.isPlaying) {
            target.pause()
        } else {
            if (_state.value.ended) target.seekTo(0L)
            target.play()
        }
        sampleNow()
    }

    /** 倍速轮转：0.5 → 1 → 1.5 → 2 → 0.5。 */
    fun cycleSpeed() {
        val next = VideoPlayerMath.nextSpeed(_state.value.speed)
        engine?.setSpeed(next)
        _state.update { it.reduce(VideoPlayerEvent.SpeedChanged(next)) }
    }

    /** 缩放档位轮转（适应屏幕 → 裁切填充 → 拉伸铺满 → 原始尺寸）。 */
    fun cycleResizeMode() {
        val next = VideoPlayerMath.nextResizeMode(_state.value.resizeMode)
        engine?.setResizeMode(next)
        _state.update { it.reduce(VideoPlayerEvent.ResizeModeChanged(next)) }
    }

    /** 内核互切（R9）：Media3 ⇄ LibVLC，位置/倍速/解码档位/字幕全部保持。 */
    fun switchEngine() {
        launchSwitch(
            target = _state.value.otherEngine,
            reason = SwitchReason.USER_REQUEST,
        )
    }

    /** 解码档位轮转（R10）：自动（硬解优先）→ 强制软解 → 强制硬解 → 自动；选择会持久化。 */
    fun cycleDecoderMode() {
        setDecoderMode(VideoPlayerMath.nextDecoderMode(_state.value.decoderMode))
    }

    /**
     * 切到指定解码档位（R10）：先持久化，再决定是换内核还是原地重建解码器。
     *
     * 原地重建也要给「短暂黑屏」提示——档位是渲染器构建期参数，引擎内部会重建。
     */
    fun setDecoderMode(mode: DecoderMode) {
        if (_state.value.switching) return
        viewModelScope.launch {
            withContext(io) { runCatching { environment.preferences.setDecoderMode(mode) } }
            val current = engine
            val action = VideoEngineSwitch.decoderAction(
                current = current?.kind ?: _state.value.engineKind,
                mode = mode,
                canUseVlc = proxyAvailable,
            )
            when (action) {
                is DecoderAction.SwitchTo -> launchSwitch(
                    target = action.kind,
                    reason = SwitchReason.DECODER_MODE_CHANGE,
                    decoderMode = mode,
                )

                DecoderAction.RebuildInPlace -> {
                    if (current == null) {
                        // 还没有内核（建内核失败/已释放）：先把档位反映到界面，起播时按它建内核
                        _state.update { it.reduce(VideoPlayerEvent.DecoderModeChanged(mode)) }
                        return@launch
                    }
                    val plan = VideoEngineSwitch.planFor(
                        engine = current,
                        target = current.kind,
                        reason = SwitchReason.DECODER_MODE_CHANGE,
                        decoderMode = mode,
                    )
                    _state.update { it.reduce(VideoPlayerEvent.SwitchPlanned(plan)) }
                    current.setDecoderMode(mode)
                    _state.update {
                        it.reduce(
                            VideoPlayerEvent.SwitchCompleted(
                                kind = current.kind,
                                decoderMode = mode,
                                positionMs = current.positionMs(),
                                playing = current.isPlaying,
                            ),
                        )
                    }
                    holdBlackoutHint()
                }
            }
        }
    }

    /**
     * 一键切 LibVLC 续播（plan 4.6 自动降级）。
     *
     * 判定与 PlayerEngineSwitcher.fallbackFromFailure() 相同（LibVLC → 无处可退；代理不可用则不给动作），
     * 位置用 UI 最后观测到的值兜底，避免出错的内核把进度报成 0。
     */
    fun fallbackFromFailure() {
        val target = VideoEngineSwitch.fallbackTarget(_state.value.engineKind, proxyAvailable) ?: return
        launchSwitch(target = target, reason = SwitchReason.ERROR_FALLBACK)
    }

    /** 下一集（已在最后一集时不动）。 */
    fun next() {
        val current = _state.value
        openEpisode(VideoPlayerMath.nextIndex(current.siblingIndex, current.siblingPaths.size) ?: return)
    }

    /** 上一集（已在第一集时不动）。 */
    fun previous() {
        openEpisode(VideoPlayerMath.previousIndex(_state.value.siblingIndex) ?: return)
    }

    /** 拖拽中：只更新本地状态，不打扰内核（松手才 seek，R4）。 */
    fun onSeekChange(ratio: Float) {
        _state.update { it.reduce(VideoPlayerEvent.SeekChanged(ratio)) }
    }

    /** 松手：把拖到的位置提交给内核。 */
    fun onSeekFinished() {
        _state.update { it.reduce(VideoPlayerEvent.SeekFinished) }
        val position = _state.value.positionMs
        engine?.seekTo(position)
        msSinceSave = 0L
        sampleNow()
    }

    override fun onCleared() {
        val ref = source
        val position = _state.value.positionMs
        val duration = _state.value.durationMs
        loadJob?.cancel()
        engineJob?.cancel()
        tickJob?.cancel()
        switchJob?.cancel()
        blackoutJob?.cancel()
        resumeJob?.cancel()
        releaseEngine()
        if (ref == null || position <= 0L) return
        // 播完后退出时记成总时长：下次进入按「已看完」从头开始（与 EngineSwitchPlanner 的位置口径一致）
        val finalPosition = if (duration > 0L && position >= duration) duration else position
        exitScope.launch {
            runCatching { environment.progress.save(progressOf(ref, finalPosition)) }
        }
    }

    // ------------------------------------------------------------------ 加载与起播

    /** 进入页面/重试：列同目录 → 建内核 → 装载 → 准备 → 起播（R9 默认内核来自偏好）。 */
    private fun load(path: String) {
        loadJob?.cancel()
        _state.update { it.reduce(VideoPlayerEvent.LoadStarted(path)) }
        loadJob = viewModelScope.launch {
            proxyAvailable = withContext(io) { runCatching { environment.proxyBaseUrl }.getOrNull() != null }
            val backendId = environment.backend.value?.id
            if (backendId == null) {
                fail(VideoErrorKind.ACCESS_DENIED, NO_ROOT_MESSAGE)
                return@launch
            }
            val preferences = environment.preferences
            val kind = preferences.engine.value
            val mode = preferences.decoderMode.value
            val entries = try {
                withContext(io) { environment.siblings(path) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "列出同目录文件失败：" + path, t)
                fail(VideoPlayerErrors.classify(t), t.message)
                return@launch
            }
            val paths = entries.map { it.path }
            val index = VideoPlayerMath.indexOfPath(paths, path)
            val queue = if (index >= 0) paths else listOf(path)
            _state.update { it.reduce(VideoPlayerEvent.SiblingsLoaded(queue, index.coerceAtLeast(0))) }
            open(queue.getOrNull(index.coerceAtLeast(0)) ?: path, backendId, kind, mode)
        }
    }

    /** 重新来一遍（换内核失败后重试也走它）。 */
    private fun reload(path: String) {
        loadJob?.cancel()
        tickJob?.cancel()
        engineJob?.cancel()
        releaseEngine()
        load(path)
    }

    /** 建内核 → 挂视图 → 装载媒体 → 准备 → 起播 → 读断点（R18）。 */
    private suspend fun open(path: String, backendId: String, kind: EngineKind, mode: DecoderMode) {
        val ref = MediaSourceRef(backendId = backendId, path = path, title = VideoPlayerMath.fileNameOf(path))
        source = ref
        val created = try {
            environment.createEngine(kind)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "创建播放内核失败：" + kind.label, t)
            fail(VideoPlayerErrors.classify(t), t.message)
            return
        }
        engine = created
        observe(created)
        _videoOutput.value = created.videoView()
        created.setDecoderMode(mode)
        created.setMedia(ref)
        created.prepare()
        created.play()
        _state.update { it.reduce(VideoPlayerEvent.EngineAttached(created.kind, mode)) }
        msSinceSave = 0L
        wasPlaying = true
        endedSaved = false
        startTicker()
        applyResume(ref, created)
    }

    /** 读断点并 seek（R18「进入时读一次，有则 seek 并提示」）。 */
    private suspend fun applyResume(ref: MediaSourceRef, target: PlayerEngine) {
        val record = try {
            withContext(io) { environment.progress.load(ref.backendId, ref.path) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "读取断点失败：" + ref.path, t)
            return
        }
        val position = record?.positionMs ?: return
        if (position <= 0L) return
        target.seekTo(position)
        _state.update { it.reduce(VideoPlayerEvent.ResumeAvailable(position)) }
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            delay(RESUME_HINT_MS)
            _state.update { it.reduce(VideoPlayerEvent.ResumeHintCleared) }
        }
    }

    /** 切到队列里的另一集：复用同一个内核，只换媒体（不重建解码器）。 */
    private fun openEpisode(index: Int) {
        // 切换内核的过程中不换集：此时旧内核已释放、新内核还没建好，插进来会多造一个内核
        if (_state.value.switching) return
        val current = _state.value
        val path = current.siblingPaths.getOrNull(index) ?: return
        val backendId = source?.backendId ?: environment.backend.value?.id ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            saveProgress(current.positionMs)
            _state.update { it.reduce(VideoPlayerEvent.EpisodeOpened(current.siblingPaths, index)) }
            val running = engine
            if (running == null) {
                // 内核已经不在了（切换失败/已释放）：按偏好重新建一个
                val preferences = environment.preferences
                open(path, backendId, preferences.engine.value, preferences.decoderMode.value)
                return@launch
            }
            val ref = MediaSourceRef(backendId = backendId, path = path, title = VideoPlayerMath.fileNameOf(path))
            source = ref
            running.setMedia(ref)
            running.prepare()
            running.play()
            _state.update { it.reduce(VideoPlayerEvent.EngineAttached(running.kind, running.decoderMode)) }
            msSinceSave = 0L
            wasPlaying = true
            endedSaved = false
            startTicker()
            applyResume(ref, running)
        }
    }

    // ------------------------------------------------------------------ 内核切换（R9/R10/降级）

    /**
     * 切换内核（R9/R10/plan 4.6 降级）：位置、倍速、解码档位、画面模式、字幕、播放状态全保持。
     *
     * @param decoderMode 目标解码档位；null = 沿用旧内核当前档位（手动换内核）。
     */
    private fun launchSwitch(target: EngineKind, reason: SwitchReason, decoderMode: DecoderMode? = null) {
        if (switchJob?.isActive == true) return
        switchJob = viewModelScope.launch { performSwitch(target, reason, decoderMode) }
    }

    private suspend fun performSwitch(target: EngineKind, reason: SwitchReason, decoderMode: DecoderMode?) {
        val current = engine
        if (current == null) {
            // 还没有内核（例如建内核失败后直接点降级）：按目标内核从头打开
            val ref = source ?: return
            open(ref.path, ref.backendId, target, decoderMode ?: _state.value.decoderMode)
            return
        }
        val failed = current.state.value is EngineState.Error
        val plan = VideoEngineSwitch.planFor(
            engine = current,
            target = target,
            reason = reason,
            decoderMode = decoderMode,
            // 出错的内核可能把位置报成 0：用 UI 最后观测到的位置/播放状态兜底（「位置不丢」）
            positionFloorMs = if (failed) _state.value.positionMs else 0L,
            playWhenReady = if (failed) _state.value.playing else current.isPlaying,
        )
        _state.update { it.reduce(VideoPlayerEvent.SwitchPlanned(plan)) }
        saveProgress(plan.stopAtMs)
        runCatching { current.pause() }
        releaseEngine()
        val created = try {
            environment.createEngine(target)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "切换内核失败：" + target.label, t)
            _state.update {
                it.reduce(
                    VideoPlayerEvent.SwitchFailed(
                        detail = t.message,
                        canFallback = VideoEngineSwitch.fallbackTarget(target, proxyAvailable) != null,
                    ),
                )
            }
            return
        }
        engine = created
        observe(created)
        _videoOutput.value = created.videoView()
        VideoEngineSwitch.applyRestore(created, plan.restore)
        if (reason == SwitchReason.USER_REQUEST) {
            withContext(io) { runCatching { environment.preferences.setEngine(target) } }
        }
        _state.update {
            it.reduce(
                VideoPlayerEvent.SwitchCompleted(
                    kind = created.kind,
                    decoderMode = plan.restore.decoderMode,
                    positionMs = plan.restore.positionMs,
                    playing = plan.restore.playWhenReady,
                ),
            )
        }
        msSinceSave = 0L
        wasPlaying = plan.restore.playWhenReady
        endedSaved = false
        startTicker()
    }

    // ------------------------------------------------------------------ 内核状态与进度

    /** 订阅内核状态机：出错就暴露「一键切 LibVLC」动作（plan 4.6）。 */
    private fun observe(target: PlayerEngine) {
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            target.state.collect { engineState ->
                if (engineState is EngineState.Error) {
                    AppLog.w(TAG, "内核报错：" + engineState.message)
                    val fallback = VideoEngineSwitch.fallbackTarget(target.kind, proxyAvailable) != null
                    _state.update {
                        it.reduce(VideoPlayerEvent.Failed(VideoErrorKind.PLAYBACK, engineState.message, fallback))
                    }
                }
            }
        }
    }

    /** 定时采样内核进度：Media3 与 LibVLC 都没有统一的进度回调，轮询是两个内核都成立的做法。 */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(tickMs)
                val tick = sampleNow() ?: continue
                saveOnTick(tick)
            }
        }
    }

    /** 立刻采一次样（播放/暂停/seek 后给即时反馈）。 */
    private fun sampleNow(): VideoPlayerEvent.Tick? {
        val target = engine ?: return null
        val engineState = target.state.value
        val tick = VideoPlayerEvent.Tick(
            positionMs = target.positionMs(),
            durationMs = target.durationMs(),
            playing = target.isPlaying,
            buffering = engineState is EngineState.Preparing,
            ended = engineState is EngineState.Ended,
        )
        _state.update { it.reduce(tick) }
        return tick
    }

    /** 断点写入策略（R18）：播放中每 5 秒一次；暂停补写一次；播完把位置记成总时长。 */
    private suspend fun saveOnTick(tick: VideoPlayerEvent.Tick) {
        msSinceSave += tickMs
        when {
            tick.ended -> {
                if (!endedSaved) {
                    endedSaved = true
                    if (tick.durationMs > 0L) {
                        msSinceSave = 0L
                        saveProgress(tick.durationMs)
                    }
                }
            }

            tick.playing -> if (msSinceSave >= PROGRESS_SAVE_INTERVAL_MS) {
                msSinceSave = 0L
                saveProgress(tick.positionMs)
            }

            wasPlaying -> {
                msSinceSave = 0L
                saveProgress(tick.positionMs)
            }
        }
        wasPlaying = tick.playing
    }

    /** 写一次断点；失败只记日志，绝不影响播放（与媒体服务同一约定）。 */
    private suspend fun saveProgress(positionMs: Long) {
        val ref = source ?: return
        if (positionMs <= 0L) return
        runCatching { withContext(io) { environment.progress.save(progressOf(ref, positionMs)) } }
            .onFailure { AppLog.w(TAG, "写入断点失败：" + ref.path, it) }
    }

    /** 同内核内重建解码器后，把「短暂黑屏」提示留一小会儿再收掉（R10）。 */
    private fun holdBlackoutHint() {
        blackoutJob?.cancel()
        blackoutJob = viewModelScope.launch {
            delay(BLACKOUT_HINT_MS)
            _state.update { it.reduce(VideoPlayerEvent.SwitchHintCleared) }
        }
    }

    private fun fail(kind: VideoErrorKind, detail: String?) {
        val fallback = VideoEngineSwitch.fallbackTarget(_state.value.engineKind, proxyAvailable) != null
        _state.update { it.reduce(VideoPlayerEvent.Failed(kind, detail, fallback)) }
    }

    private fun releaseEngine() {
        val current = engine
        engine = null
        _videoOutput.value = null
        if (current == null) return
        runCatching { current.release() }.onFailure { AppLog.w(TAG, "释放内核失败", it) }
    }

    private fun progressOf(ref: MediaSourceRef, positionMs: Long): PlaybackProgress = PlaybackProgress(
        backendId = ref.backendId,
        path = ref.path,
        positionMs = positionMs,
        updatedAt = System.currentTimeMillis(),
    )

    private companion object {

        /** 进度采样间隔：500 ms 足够跟手，也不会把主线程吵醒得太频繁。 */
        const val TICK_MS = 500L

        /** 播放中写断点的间隔（R18 要求每 5 秒一次）。 */
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

        /** 续播提示停留时长。 */
        const val RESUME_HINT_MS = 4_000L

        /** 「切换解码模式会短暂黑屏」提示停留时长。 */
        const val BLACKOUT_HINT_MS = 2_000L

        /** 尚未选择根目录时的中文说明（R12）。 */
        const val NO_ROOT_MESSAGE = "尚未选择媒体根目录"
    }
}
