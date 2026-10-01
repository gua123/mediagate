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
import io.github.gua123.mediagate.media.subtitle.SubtitleCandidate
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleSource
import io.github.gua123.mediagate.media.subtitle.SubtitleStyle
import io.github.gua123.mediagate.media.subtitle.SubtitleTimeline

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
 * - **拖拽**（R4）：拖拽中不被进度回调覆盖（[VideoPlayerEvent.SeekChanged]），松手才 seek 内核；
 * - **字幕**（R14，单轨）：自动匹配同目录候选（[SubtitleLocator] 的优先级由 [SubtitleHost] 提供），
 *   用户可在面板里换轨/关开/调样式/±0.5 秒微调；**渲染走 Compose 覆盖层**（[subtitleCue]），
 *   不交给引擎——原因见 [VideoPlayerScreen] 的字幕说明（Media3 没有字幕延迟 API，
 *   LibVLC 也没有可用的样式接口，覆盖层是唯一能让两个内核表现一致、且可纯函数单测的做法）。
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
    private var subtitleJob: Job? = null
    private var discoverJob: Job? = null
    private var subtitleTickJob: Job? = null
    private var writeBackJob: Job? = null
    private var hostJob: Job? = null
    private var msSinceSave: Long = 0L
    private var wasPlaying: Boolean = false
    private var endedSaved: Boolean = false

    /** 平移结果的缓存键（cue 列表 + 偏移），避免 100 ms 一次的字幕刷新反复做 O(n) 平移。 */
    private var shiftedKey: Pair<List<SubtitleCue>, Long>? = null
    private var shiftedValue: List<SubtitleCue> = emptyList()

    private val _subtitleCue = MutableStateFlow<SubtitleCue?>(null)

    /**
     * 覆盖层当前要显示的 cue（R14）。
     *
     * 单独一条热流而不是塞进 [VideoPlayerUiState]：字幕要按 ~100 ms 的精度跟随播放位置，
     * 而页面状态（位置/时长）是 500 ms 采样一次；两者放一起会把整份状态刷得太频繁。
     */
    val subtitleCue: StateFlow<SubtitleCue?> = _subtitleCue.asStateFlow()

    init {
        applyStoredSubtitleSettings()
        attachHosts()
        load(initialPath)
    }

    // ------------------------------------------------------------------ 宿主能力（R13 画中画 / R18 会话 / R19 让路）

    /**
     * 接上宿主能力（**R13** 画中画 + **R18** 视频会话 + **R19** 播放让路）。
     *
     * 只做三件事，全部是"把页面的状态翻译成宿主要的形式"：
     * 1. 注册 PIP 动作落点（PIP 里的播放/暂停、±10 秒点击会回调 [onPipAction]）；
     * 2. 状态一变就更新 PIP 动作按钮与"按 Home 自动进 PIP"开关（[VideoPipMath] 的纯判定）；
     * 3. 把"正在前台播放"上报给宿主——R19 的 ASR 让路闸门就吃这个信号，
     *    口径是 **READY + 在播 + 没播完**（暂停/加载中/播完/失败都不算，识别任务该跑就跑）。
     */
    private fun attachHosts() {
        environment.pip.setActionSink(VideoPipActionSink(::onPipAction))
        hostJob = viewModelScope.launch {
            state.collect { snapshot ->
                environment.pip.updateActions(VideoPipMath.showsPauseAction(snapshot))
                environment.pip.setAutoEnterEnabled(VideoPipMath.autoEnterEnabled(snapshot))
                environment.playback.setActive(isPlaybackActive(snapshot))
            }
        }
    }

    /** R19 让路口径：真的在播才算"占用前台播放"（暂停/失败/播完都让开）。 */
    private fun isPlaybackActive(snapshot: VideoPlayerUiState): Boolean =
        snapshot.playing && snapshot.status == VideoPlayerStatus.READY && !snapshot.ended

    /**
     * PIP 内的动作（**R13**）：播放/暂停 + 快退/快进 10 秒。
     *
     * 由宿主的 PIP 动作按钮（Android 13+ 的 RemoteAction）回调进来，最终驱动**当前内核**。
     */
    fun onPipAction(action: VideoPipAction) {
        when (action) {
            VideoPipAction.TOGGLE_PLAY_PAUSE -> togglePlayPause()
            VideoPipAction.REWIND_10S -> seekBy(-VideoPipMath.SEEK_STEP_MS)
            VideoPipAction.FORWARD_10S -> seekBy(VideoPipMath.SEEK_STEP_MS)
        }
    }

    /**
     * 相对跳转（**R13** PIP 内的 ±10 秒）。
     *
     * 位置以**内核现场**为准（UI 采样值最多滞后 500 ms，快退 10 秒这种小步长会明显不准），
     * 越界由 [VideoPipMath.seekTarget] 钳在 0..时长。
     */
    fun seekBy(deltaMs: Long) {
        val target = engine ?: return
        val next = VideoPipMath.seekTarget(target.positionMs(), deltaMs, target.durationMs())
        target.seekTo(next)
        // 跳到哪儿就从哪儿重新计 5 秒落盘（R18）
        msSinceSave = 0L
        _state.update { it.reduce(VideoPlayerEvent.SeekedTo(next)) }
    }

    /** 把当前视频会话交给宿主（**R18**：通知栏 / 锁屏 / 蓝牙要看它）。 */
    private fun bindSession(engine: PlayerEngine, ref: MediaSourceRef, view: View?) {
        environment.playback.bindSession(
            VideoSession(
                engine = engine,
                // 展示名与播放页标题同口径（title 为空时回落到路径末段）
                title = ref.title?.takeIf { it.isNotBlank() } ?: VideoPlayerMath.fileNameOf(ref.path),
                backendId = ref.backendId,
                path = ref.path,
                videoView = view,
            ),
        )
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

    // ------------------------------------------------------------------ 字幕（R14，单轨）

    /**
     * 用户点开字幕面板（R14）：按需重新匹配同目录候选。
     *
     * 面板是界面本地状态（不进 [VideoPlayerUiState]），这里只负责把候选列表拉回来。
     */
    fun openSubtitlePanel() {
        refreshSubtitleCandidates()
    }

    /**
     * 匹配同目录字幕候选（R14：同目录同名 → 语言后缀 → 修饰后缀 → 手动可选）。
     *
     * 远端字幕走的就是这条路：与视频**同一个 StorageBackend**，能列目录就能匹配到；
     * 列目录期间界面显示加载中（[VideoPlayerUiState.subtitleLoading] 由后续的加载事件置位）。
     *
     * 没有当前轨道时会自动加载优先级最高的候选；否则只刷新候选列表，
     * 不打断用户已经选好的那一条。
     */
    fun refreshSubtitleCandidates() {
        val path = _state.value.path.ifEmpty { source?.path ?: initialPath }
        if (path.isEmpty()) return
        discoverJob?.cancel()
        // 远端列目录也要给"加载中"（R14）：候选列表出来之前界面不是一片空白
        _state.update { it.reduce(VideoPlayerEvent.SubtitleDiscoverStarted) }
        discoverJob = viewModelScope.launch {
            val candidates = try {
                withContext(io) { environment.subtitles.discover(path) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "匹配同目录字幕失败：$path", t)
                _state.update {
                    it.reduce(VideoPlayerEvent.SubtitleNoticeRaised(SubtitleNoticeKind.LOAD_FAILED, t.message))
                }
                return@launch
            }
            _state.update { it.reduce(VideoPlayerEvent.SubtitleDiscovered(candidates)) }
            val auto = candidates.firstOrNull { it.source == SubtitleSource.AUTO }
            val current = _state.value.subtitlePath
            when {
                auto != null && current == null -> loadSubtitle(auto.path, auto.displayName)

                candidates.isEmpty() && current == null -> _state.update {
                    it.reduce(VideoPlayerEvent.SubtitleNoticeRaised(SubtitleNoticeKind.NO_CANDIDATE, null))
                }
            }
        }
    }

    /** 选一条候选（R14：**一次只加载一条轨道**，选了就替换）。 */
    fun selectSubtitle(candidate: SubtitleCandidate) {
        loadSubtitle(candidate.path, candidate.displayName)
    }

    /** 手动指定一个字幕路径（不在候选列表里的也行，R14「手动选择文件」）。 */
    fun selectSubtitle(path: String) {
        if (path.isBlank()) return
        loadSubtitle(path, VideoPlayerMath.fileNameOf(path))
    }

    /**
     * 字幕总开关（R14）。
     *
     * 关掉只是不渲染（轨道与 cue 留着，再打开立刻可用）；打开时若还没有轨道，
     * 顺手做一次自动匹配——用户不用再去面板里点一次。
     */
    fun setSubtitleEnabled(enabled: Boolean) {
        _state.update { it.reduce(VideoPlayerEvent.SubtitleEnabledChanged(enabled)) }
        persistSubtitlePreference { environment.preferences.setSubtitleEnabled(enabled) }
        if (enabled && _state.value.subtitleCues.isEmpty()) refreshSubtitleCandidates()
        refreshSubtitleCue()
    }

    /** 开关轮转（面板上的开关按钮用）。 */
    fun toggleSubtitle() {
        setSubtitleEnabled(!_state.value.subtitleEnabled)
    }

    /**
     * 时间轴微调（R14：±0.5 秒步进；长按连续微调就是连续调用本方法）。
     *
     * @param steps 步数；正数 = 字幕延后（+0.5 s/步）。
     */
    fun nudgeSubtitle(steps: Int = 1) {
        setSubtitleOffset(SubtitleTimeline.step(_state.value.subtitleOffsetMs, steps))
    }

    /** 直接设一个毫秒偏移（R14「任意毫秒偏移」；越界会被钳住）。 */
    fun setSubtitleOffset(offsetMs: Long) {
        val clamped = SubtitleTimeline.clampOffset(offsetMs)
        _state.update { it.reduce(VideoPlayerEvent.SubtitleOffsetChanged(clamped)) }
        persistSubtitlePreference { environment.preferences.setSubtitleOffsetMs(clamped) }
        refreshSubtitleCue()
    }

    /** 整体换一份样式（R14：字号 / 颜色 / 描边 / 底部边距 / 加粗斜体），越界值会被钳住并持久化。 */
    fun setSubtitleStyle(style: SubtitleStyle) {
        val clamped = style.clamped()
        _state.update { it.reduce(VideoPlayerEvent.SubtitleStyleChanged(clamped)) }
        persistSubtitlePreference { environment.preferences.setSubtitleStyle(clamped) }
    }

    /** 字号（sp，R14）。 */
    fun setSubtitleFontSize(sp: Float) = setSubtitleStyle(_state.value.subtitleStyle.copy(fontSizeSp = sp))

    /** 文字颜色（ARGB，R14）。 */
    fun setSubtitleTextColor(argb: Int) = setSubtitleStyle(_state.value.subtitleStyle.copy(textColorArgb = argb))

    /** 描边宽度（dp，R14；0 = 不描边）。 */
    fun setSubtitleOutlineWidth(dp: Float) = setSubtitleStyle(_state.value.subtitleStyle.copy(outlineWidthDp = dp))

    /** 描边颜色（ARGB，R14）。 */
    fun setSubtitleOutlineColor(argb: Int) = setSubtitleStyle(_state.value.subtitleStyle.copy(outlineColorArgb = argb))

    /** 底部边距（dp，R14）。 */
    fun setSubtitleBottomMargin(dp: Float) = setSubtitleStyle(_state.value.subtitleStyle.copy(bottomMarginDp = dp))

    /** 加粗开关（R14）。 */
    fun toggleSubtitleBold() = setSubtitleStyle(_state.value.subtitleStyle.copy(bold = !_state.value.subtitleStyle.bold))

    /** 斜体开关（R14）。 */
    fun toggleSubtitleItalic() =
        setSubtitleStyle(_state.value.subtitleStyle.copy(italic = !_state.value.subtitleStyle.italic))

    /**
     * 写回 / 另存字幕（R14）。
     *
     * 把**当前微调后的时间轴**写进视频同目录的同名文件（另存 VTT 同理）：写回成功后偏移归零，
     * 因为偏移已经"烙"进文件里了。无写权限时 [SubtitleHost] 会落到 App 私有目录并返回
     * [SubtitleWriteResult.LocalFallback]，界面提示「已保存到本地，可分享/稍后重试」——绝不静默丢弃。
     *
     * @param format 目标格式；null = 原格式（ASS/SSA 自动另存为 SRT）。
     */
    fun writeBackSubtitle(format: SubtitleFormat? = null) {
        val current = _state.value
        val path = current.subtitlePath ?: return
        if (current.subtitleCues.isEmpty()) return
        val target = format ?: current.subtitleWriteFormat
        val cues = SubtitleTimeline.shift(current.subtitleCues, current.subtitleOffsetMs)
        val videoPath = current.path
        writeBackJob?.cancel()
        writeBackJob = viewModelScope.launch {
            val result = try {
                withContext(io) { environment.subtitles.writeBack(videoPath, target, cues) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "写回字幕失败：$path", t)
                SubtitleWriteResult.Failed(t.message)
            }
            when (result) {
                is SubtitleWriteResult.Written -> {
                    _state.update { state ->
                        state
                            .reduce(
                                VideoPlayerEvent.SubtitleLoaded(
                                    path = path,
                                    label = state.subtitleLabel ?: VideoPlayerMath.fileNameOf(path),
                                    cues = cues,
                                ),
                            )
                            .reduce(VideoPlayerEvent.SubtitleOffsetChanged(0L))
                            .reduce(VideoPlayerEvent.SubtitleNoticeRaised(SubtitleNoticeKind.WRITE_OK, result.path))
                    }
                    persistSubtitlePreference { environment.preferences.setSubtitleOffsetMs(0L) }
                }

                is SubtitleWriteResult.LocalFallback -> _state.update {
                    it.reduce(VideoPlayerEvent.SubtitleNoticeRaised(SubtitleNoticeKind.WRITE_LOCAL, result.path))
                }

                is SubtitleWriteResult.Failed -> _state.update {
                    it.reduce(VideoPlayerEvent.SubtitleNoticeRaised(SubtitleNoticeKind.WRITE_FAILED, result.reason))
                }
            }
            refreshSubtitleCue()
        }
    }

    /** 清掉字幕提示（用户看过之后）。 */
    fun clearSubtitleNotice() {
        _state.update { it.reduce(VideoPlayerEvent.SubtitleNoticeCleared) }
    }

    /** 卸载当前字幕轨（回到「无字幕」；候选列表保留，用户可以再选）。 */
    fun clearSubtitle() {
        subtitleJob?.cancel()
        subtitleTickJob?.cancel()
        _state.update { it.reduce(VideoPlayerEvent.SubtitleCleared) }
        refreshSubtitleCue()
    }

    /**
     * 加载一条字幕（R14 单轨）。
     *
     * 远端字幕在这里**显示加载中**（[VideoPlayerUiState.subtitleLoading]）：整个读取都走
     * [SubtitleHost.load]（内部是 StorageBackend 的流式读取 + 解析），失败给中文提示。
     */
    private fun loadSubtitle(path: String, label: String?) {
        val name = label ?: VideoPlayerMath.fileNameOf(path)
        subtitleJob?.cancel()
        _state.update { it.reduce(VideoPlayerEvent.SubtitleLoadStarted(path, name)) }
        subtitleJob = viewModelScope.launch {
            val result = try {
                withContext(io) { environment.subtitles.load(path) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "加载字幕失败：$path", t)
                _state.update { it.reduce(VideoPlayerEvent.SubtitleLoadFailed(t.message)) }
                refreshSubtitleCue()
                return@launch
            }
            if (result.cues.isEmpty()) {
                _state.update { it.reduce(VideoPlayerEvent.SubtitleLoadFailed(EMPTY_SUBTITLE_MESSAGE)) }
                refreshSubtitleCue()
                return@launch
            }
            _state.update {
                it.reduce(
                    VideoPlayerEvent.SubtitleLoaded(
                        path = path,
                        label = name,
                        cues = result.cues,
                        dropped = result.dropped,
                        truncatedLines = result.truncatedLines,
                    ),
                )
            }
            withContext(io) { runCatching { environment.preferences.setSubtitleEnabled(true) } }
            startSubtitleTicker()
            refreshSubtitleCue()
        }
    }

    /** 换媒体后同步字幕（R14）：轨道由 reduce 清掉，这里按开关决定要不要重新自动匹配。 */
    private fun syncSubtitle() {
        _subtitleCue.value = null
        subtitleTickJob?.cancel()
        if (!_state.value.subtitleEnabled) return
        refreshSubtitleCandidates()
    }

    /** 进页面时把持久化的字幕设置读进状态（开关 / 样式 / 时间轴微调，R14）。 */
    private fun applyStoredSubtitleSettings() {
        val preferences = environment.preferences
        _state.update {
            it.reduce(VideoPlayerEvent.SubtitleEnabledChanged(preferences.subtitleEnabled.value))
                .reduce(VideoPlayerEvent.SubtitleOffsetChanged(preferences.subtitleOffsetMs.value))
                .reduce(VideoPlayerEvent.SubtitleStyleChanged(preferences.subtitleStyle.value))
        }
    }

    /** 字幕跟随播放位置的定时刷新（~100 ms）；暂停时不刷新（位置不动，刷新没意义）。 */
    private fun startSubtitleTicker() {
        if (subtitleTickJob?.isActive == true) return
        subtitleTickJob = viewModelScope.launch {
            while (isActive) {
                delay(SUBTITLE_TICK_MS)
                if (engine?.isPlaying == true) refreshSubtitleCue()
            }
        }
    }

    /** 重算覆盖层要显示的 cue（R14：先按偏移平移，再取命中项；平移结果有缓存）。 */
    private fun refreshSubtitleCue() {
        val current = _state.value
        if (!current.subtitleEnabled || current.subtitleCues.isEmpty()) {
            _subtitleCue.value = null
            return
        }
        val position = engine?.positionMs() ?: current.positionMs
        _subtitleCue.value = SubtitleTimeline.activeCue(shiftedCues(current), position)
    }

    /** 平移后的 cue 列表（缓存：字幕刷新很频繁，不能每次都重建列表）。 */
    private fun shiftedCues(current: VideoPlayerUiState): List<SubtitleCue> {
        val key = current.subtitleCues to current.subtitleOffsetMs
        if (shiftedKey != key) {
            shiftedKey = key
            shiftedValue = SubtitleTimeline.shift(key.first, key.second)
        }
        return shiftedValue
    }

    /** 写字幕偏好（R14）：失败只记日志，绝不影响播放。 */
    private fun persistSubtitlePreference(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(io) { block() } }
                .onFailure { AppLog.w(TAG, "写入字幕偏好失败", it) }
        }
    }

    override fun onCleared() {
        val ref = source
        val position = _state.value.positionMs
        val duration = _state.value.durationMs
        // R13/R18/R19：退出播放页要把宿主那一侧也收干净（不自动进 PIP、不发会话、放开让路）
        hostJob?.cancel()
        environment.pip.setActionSink(null)
        environment.pip.setAutoEnterEnabled(false)
        environment.pip.updateActions(false)
        environment.playback.setActive(false)
        environment.playback.bindSession(null)
        loadJob?.cancel()
        engineJob?.cancel()
        tickJob?.cancel()
        switchJob?.cancel()
        blackoutJob?.cancel()
        resumeJob?.cancel()
        subtitleJob?.cancel()
        discoverJob?.cancel()
        subtitleTickJob?.cancel()
        writeBackJob?.cancel()
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
        // 换媒体先把覆盖层上的旧字幕收掉（轨道已在 reduce 里清空，这里同步热流）
        refreshSubtitleCue()
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
        val view = created.videoView()
        _videoOutput.value = view
        created.setDecoderMode(mode)
        created.setMedia(ref)
        created.prepare()
        created.play()
        _state.update { it.reduce(VideoPlayerEvent.EngineAttached(created.kind, mode)) }
        // R18：会话（通知栏/锁屏）跟着当前这条视频走
        bindSession(created, ref, view)
        msSinceSave = 0L
        wasPlaying = true
        endedSaved = false
        startTicker()
        applyResume(ref, created)
        // R14：开着字幕就自动匹配同目录候选（本地与远端同一套逻辑）
        syncSubtitle()
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
            refreshSubtitleCue()
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
            // R18：换集后通知栏/锁屏要跟着换（标题与断点 key 都变了）
            bindSession(running, ref, _videoOutput.value)
            msSinceSave = 0L
            wasPlaying = true
            endedSaved = false
            startTicker()
            applyResume(ref, running)
            // R14：换集后重新匹配这一集的字幕（上一集的轨道已在 EpisodeOpened 里清掉）
            syncSubtitle()
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
        val view = created.videoView()
        _videoOutput.value = view
        VideoEngineSwitch.applyRestore(created, plan.restore)
        // R18：换了内核，会话跟着换成新内核（通知栏控制要打到新内核上，PIP 比例也从它的画面取）
        source?.let { ref -> bindSession(created, ref, view) }
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
        // 字幕覆盖层跟着采样点走（±0.5 s 微调在 refreshSubtitleCue 里应用）
        refreshSubtitleCue()
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
        // R18：内核没了就没有可会话的对象——通知栏收掉、让路闸门放开（不留悬念）
        environment.playback.bindSession(null)
        environment.playback.setActive(false)
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

        /** 字幕刷新间隔（R14）：比进度采样细一档，字幕进出场才不会明显滞后。 */
        const val SUBTITLE_TICK_MS = 100L

        /** 播放中写断点的间隔（R18 要求每 5 秒一次）。 */
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

        /** 续播提示停留时长。 */
        const val RESUME_HINT_MS = 4_000L

        /** 「切换解码模式会短暂黑屏」提示停留时长。 */
        const val BLACKOUT_HINT_MS = 2_000L

        /** 尚未选择根目录时的中文说明（R12）。 */
        const val NO_ROOT_MESSAGE = "尚未选择媒体根目录"

        /** 字幕文件读出来了却没有可用条目时的中文提示（R14：不静默丢弃）。 */
        const val EMPTY_SUBTITLE_MESSAGE = "字幕文件里没有可显示的条目"
    }
}
