package io.github.gua123.mediagate.feature.player.video

import android.view.View
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.DefaultSubtitleTrackController
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineState
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.engine.ResizeMode
import io.github.gua123.mediagate.media.engine.SubtitleTrackController
import io.github.gua123.mediagate.media.playback.PlaybackProgress
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore
import io.github.gua123.mediagate.media.subtitle.SubtitleCandidate
import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleLocator
import io.github.gua123.mediagate.media.subtitle.SubtitleMatchKind
import io.github.gua123.mediagate.media.subtitle.SubtitleParseResult
import io.github.gua123.mediagate.media.subtitle.SubtitleParser
import io.github.gua123.mediagate.media.subtitle.SubtitleSource
import io.github.gua123.mediagate.media.subtitle.SubtitleStyle
import io.github.gua123.mediagate.media.subtitle.SubtitleWriter
import java.io.InputStream

/** 进度采样的默认间隔（与 ViewModel 的默认值一致）。 */
internal const val TEST_TICK_MS = 500L

/**
 * 让当前已排队的协程跑完。
 *
 * **刻意不用 advanceUntilIdle**：播放页有一个永不结束的进度采样循环（每 500 ms 一次），
 * advanceUntilIdle 会一直推进虚拟时间不返回。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() {
    runCurrent()
}

/** 推进 [times] 个采样周期（每个周期 500 ms），让轮询真的跑起来。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.tick(times: Int = 1, tickMs: Long = TEST_TICK_MS) {
    repeat(times) {
        advanceTimeBy(tickMs)
        runCurrent()
    }
}

/**
 * 假内核（JVM 单测用）：记录每一次调用，方便断言「切换后恢复了什么、按什么顺序恢复」。
 *
 * 位置/时长不会自己走，由测试直接改 [position]/[duration] 模拟播放进度。
 */
internal class FakePlayerEngine(
    override val kind: EngineKind,
    initialState: EngineState = EngineState.Idle,
    private val view: View? = null,
) : PlayerEngine {

    override val state = MutableStateFlow(initialState)

    private val subtitleController = DefaultSubtitleTrackController()

    override var isPlaying: Boolean = false

    /** 倍速的真身：speed 是只读覆盖，可写属性会与 PlayerEngine.setSpeed 撞 JVM 签名。 */
    var speedValue: Float = 1f

    override val speed: Float get() = speedValue

    /** 解码档位真身（同上，setDecoderMode 是接口方法）。 */
    var decoderModeValue: DecoderMode = DecoderMode.AUTO_HW

    override val decoderMode: DecoderMode get() = decoderModeValue

    /** 缩放档位真身（同上，setResizeMode 是接口方法）。 */
    var resizeModeValue: ResizeMode = ResizeMode.FIT

    override val resizeMode: ResizeMode get() = resizeModeValue

    override var currentMedia: MediaSourceRef? = null

    /** 当前播放位置（毫秒），测试直接改。 */
    var position: Long = 0L

    /** 总时长（毫秒），测试直接改。 */
    var duration: Long = 0L

    /** 是否已被释放（切换内核时必须先释放旧的）。 */
    var released: Boolean = false

    /** 调用流水（顺序即代码顺序）。 */
    val calls = mutableListOf<String>()

    /** seekTo 的历史。 */
    val seekCalls = mutableListOf<Long>()

    override fun setMedia(src: MediaSourceRef) {
        currentMedia = src
        calls += "setMedia:" + src.path
    }

    override fun prepare() {
        calls += "prepare"
    }

    override fun play() {
        isPlaying = true
        calls += "play"
    }

    override fun pause() {
        isPlaying = false
        calls += "pause"
    }

    override fun seekTo(positionMs: Long) {
        position = positionMs
        seekCalls += positionMs
        calls += "seekTo:" + positionMs
    }

    override fun setSpeed(x: Float) {
        speedValue = x
        calls += "speed:" + x
    }

    override fun setResizeMode(mode: ResizeMode) {
        resizeModeValue = mode
        calls += "resize:" + mode.name
    }

    override fun setDecoderMode(mode: DecoderMode) {
        decoderModeValue = mode
        calls += "decoder:" + mode.name
    }

    override fun subtitles(): SubtitleTrackController = subtitleController

    override fun videoView(): View? = view

    override fun positionMs(): Long = position

    override fun durationMs(): Long = duration

    override fun release() {
        released = true
        isPlaying = false
        calls += "release"
    }
}

/** 假偏好：把每次写方法记下来，验证 R9/R10/R14 的持久化。 */
internal class FakeVideoPreferences(
    engine: EngineKind = EngineKind.MEDIA3,
    decoderMode: DecoderMode = DecoderMode.AUTO_HW,
    subtitleEnabled: Boolean = false,
    subtitleStyle: SubtitleStyle = SubtitleStyle(),
    subtitleOffsetMs: Long = 0L,
) : VideoPlayerPreferences {

    private val _engine = MutableStateFlow(engine)

    private val _decoderMode = MutableStateFlow(decoderMode)

    private val _subtitleEnabled = MutableStateFlow(subtitleEnabled)

    private val _subtitleStyle = MutableStateFlow(subtitleStyle)

    private val _subtitleOffsetMs = MutableStateFlow(subtitleOffsetMs)

    override val engine: StateFlow<EngineKind> = _engine

    override val decoderMode: StateFlow<DecoderMode> = _decoderMode

    override val subtitleEnabled: StateFlow<Boolean> = _subtitleEnabled

    override val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle

    override val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs

    val engineWrites = mutableListOf<EngineKind>()

    val decoderWrites = mutableListOf<DecoderMode>()

    val subtitleEnabledWrites = mutableListOf<Boolean>()

    val subtitleStyleWrites = mutableListOf<SubtitleStyle>()

    val subtitleOffsetWrites = mutableListOf<Long>()

    override suspend fun setEngine(kind: EngineKind) {
        engineWrites += kind
        _engine.value = kind
    }

    override suspend fun setDecoderMode(mode: DecoderMode) {
        decoderWrites += mode
        _decoderMode.value = mode
    }

    override suspend fun setSubtitleEnabled(enabled: Boolean) {
        subtitleEnabledWrites += enabled
        _subtitleEnabled.value = enabled
    }

    override suspend fun setSubtitleStyle(style: SubtitleStyle) {
        subtitleStyleWrites += style
        _subtitleStyle.value = style
    }

    override suspend fun setSubtitleOffsetMs(offsetMs: Long) {
        subtitleOffsetWrites += offsetMs
        _subtitleOffsetMs.value = offsetMs
    }
}

/** 假断点存储（R18）：内存 map + 写入流水。 */
internal class FakeProgressStore : PlaybackProgressStore {

    val saved = mutableListOf<PlaybackProgress>()

    private val records = mutableMapOf<String, PlaybackProgress>()

    override suspend fun load(backendId: String, path: String): PlaybackProgress? = records[key(backendId, path)]

    override suspend fun save(progress: PlaybackProgress) {
        saved += progress
        records[key(progress.backendId, progress.path)] = progress
    }

    /** 预置一条断点（模拟上次看过）。 */
    fun seed(backendId: String, path: String, positionMs: Long) {
        records[key(backendId, path)] = PlaybackProgress(backendId, path, positionMs, 0L)
    }

    /** 某个路径最后一次写入的位置。 */
    fun lastPositionOf(path: String): Long? = saved.lastOrNull { it.path == path }?.positionMs

    private fun key(backendId: String, path: String): String = backendId + "|" + path
}

/** 最小可用后端：列目录不参与（假环境直接给目录项），只为让 env.backend 非空。 */
internal class FakeVideoBackend(
    override val id: String = "fake:video",
) : StorageBackend {

    override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 2)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

    override suspend fun stat(path: String): RemoteEntry = RemoteEntry(name = path, path = path)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
        object : RangeStream {
            override val length: Long = 0L

            override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = -1

            override suspend fun seek(position: Long) = Unit

            override fun position(): Long = 0L

            override fun close() = Unit
        }

    override suspend fun write(path: String, data: InputStream) = Unit

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}

/**
 * 假宿主（:app 的实现要 Context、回环代理与 Media3 数据源工厂）：这里只造假内核，
 * 并把「同目录可播条目」按与 :app 相同的口径（[VideoPlayerMath.playableEntries]）返回。
 */
internal class FakeVideoPlayerEnvironment(
    var entries: Map<String, List<RemoteEntry>> = emptyMap(),
    backend: StorageBackend? = FakeVideoBackend(),
    override val proxyBaseUrl: String? = "http://127.0.0.1:1",
    override val preferences: FakeVideoPreferences = FakeVideoPreferences(),
    override val progress: FakeProgressStore = FakeProgressStore(),
    override val subtitles: FakeSubtitleHost = FakeSubtitleHost(),
    private val videoViewFor: (EngineKind) -> View? = { null },
) : VideoPlayerEnvironment {

    private val _backend = MutableStateFlow(backend)

    override val backend: StateFlow<StorageBackend?> = _backend

    /** 建出来的内核（按创建顺序）。 */
    val created = mutableListOf<FakePlayerEngine>()

    /** createEngine 的调用记录。 */
    val createCalls = mutableListOf<EngineKind>()

    /** 建内核失败注入。 */
    var failCreateFor: (EngineKind) -> Throwable? = { null }

    /** 列目录失败注入：key 是目录路径。 */
    val failSiblings = mutableMapOf<String, Throwable>()

    /** 最近建出来的内核。 */
    val last: FakePlayerEngine? get() = created.lastOrNull()

    override suspend fun createEngine(kind: EngineKind): PlayerEngine {
        failCreateFor(kind)?.let { throw it }
        createCalls += kind
        val engine = FakePlayerEngine(kind, view = videoViewFor(kind))
        created += engine
        return engine
    }

    override suspend fun siblings(path: String): List<RemoteEntry> {
        val dir = path.substringBeforeLast('/', "")
        failSiblings[dir]?.let { throw it }
        return VideoPlayerMath.playableEntries(entries[dir].orEmpty())
    }
}

/**
 * 假字幕宿主（R14）：候选 / 内容 / 写回结果全部由测试摆布，并记录调用顺序。
 *
 * 相当于把 :app 里那份「SubtitleLocator + SubtitleReader + SubtitleWriter（含本地兜底）」
 * 换成内存实现，ViewModel 的字幕流程因此可以纯 JVM 覆盖。
 */
internal class FakeSubtitleHost(
    var candidates: List<SubtitleCandidate> = emptyList(),
) : SubtitleHost {

    /** 路径 → 字幕原文（load 用真实解析器解析，保持与线上一致的语义）。 */
    private val contents = mutableMapOf<String, String>()

    /** discover 的调用记录（目录路径）。 */
    val discovered = mutableListOf<String>()

    /** load 的调用记录（字幕路径）。 */
    val loaded = mutableListOf<String>()

    /** writeBack 的调用记录（视频路径, 目标格式, 写回的 cue）。 */
    val writes = mutableListOf<Triple<String, SubtitleFormat, List<SubtitleCue>>>()

    /** discover 失败注入。 */
    var discoverFailure: Throwable? = null

    /** load 失败注入。 */
    var loadFailure: Throwable? = null

    /** 写回结果注入；null = 按「已写回原目录」返回。 */
    var writeResult: SubtitleWriteResult? = null

    /** 按视频路径动态给候选（换集测试用）；null = 固定返回 [candidates]。 */
    var candidatesFor: ((String) -> List<SubtitleCandidate>)? = null

    /** 放一份字幕内容。 */
    fun put(path: String, text: String) {
        contents[path] = text
    }

    override suspend fun list(dir: String): List<RemoteEntry> = emptyList()

    override suspend fun discover(videoPath: String): List<SubtitleCandidate> {
        discoverFailure?.let { throw it }
        discovered += SubtitleLocator.directoryOf(videoPath)
        return candidatesFor?.invoke(videoPath) ?: candidates
    }

    override suspend fun load(path: String): SubtitleParseResult {
        loadFailure?.let { throw it }
        loaded += path
        val format = SubtitleFormat.fromFileName(path) ?: SubtitleFormat.SRT
        val text = contents[path] ?: return SubtitleParseResult(emptyList(), format)
        return SubtitleParser.parse(text, format)
    }

    override suspend fun writeBack(
        videoPath: String,
        format: SubtitleFormat,
        cues: List<SubtitleCue>,
    ): SubtitleWriteResult {
        writes += Triple(videoPath, format, cues)
        return writeResult ?: SubtitleWriteResult.Written(SubtitleWriter.siblingPathOf(videoPath, format))
    }
}

/** 造一条自动匹配候选。 */
internal fun autoCandidate(
    path: String,
    language: String? = "zh",
    languageLabel: String? = "中文（简体）",
): SubtitleCandidate = SubtitleCandidate(
    path = path,
    name = path.substringAfterLast('/'),
    format = SubtitleFormat.fromFileName(path) ?: SubtitleFormat.SRT,
    source = SubtitleSource.AUTO,
    matchKind = SubtitleMatchKind.LANGUAGE_SUFFIX,
    language = language,
    languageLabel = languageLabel,
)

/** 造一条手动候选。 */
internal fun manualCandidate(path: String): SubtitleCandidate = SubtitleCandidate(
    path = path,
    name = path.substringAfterLast('/'),
    format = SubtitleFormat.fromFileName(path) ?: SubtitleFormat.SRT,
    source = SubtitleSource.MANUAL,
    matchKind = SubtitleMatchKind.MODIFIER,
)

/** 标准三条字幕的 SRT 文本。 */
internal const val SAMPLE_SRT: String = "1\n00:00:01,000 --> 00:00:03,000\n第一条\n\n2\n00:00:05,000 --> 00:00:07,000\n第二条\n\n3\n00:00:09,000 --> 00:00:10,000\n第三条\n"

/** 造一个视频目录项。 */
internal fun videoEntry(path: String): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = 4_096L,
    mtime = 1_700_000_000_000L,
)

/** 造一个音频目录项（音频也算一集）。 */
internal fun audioEntry(path: String): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = 2_048L,
    mtime = 1_700_000_000_000L,
)

/** 造一个非媒体目录项（列队列时要被过滤掉）。 */
internal fun otherEntry(path: String): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = 512L,
    mtime = 1_700_000_000_000L,
)
