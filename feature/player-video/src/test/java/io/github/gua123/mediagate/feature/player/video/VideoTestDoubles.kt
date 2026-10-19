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
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineState
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.engine.ResizeMode
import io.github.gua123.mediagate.media.playback.PlaybackProgress
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore
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

    override var isPlaying: Boolean = false

    /** 倍速的真身：speed 是只读覆盖，可写属性会与 PlayerEngine.setSpeed 撞 JVM 签名。 */
    var speedValue: Float = 1f

    override val speed: Float get() = speedValue

    /** 音量真身（同上，setVolume 是接口方法）。 */
    var volumeValue: Float = 1f

    override val volume: Float get() = volumeValue

    /** 记录每一次音量设置，便于断言"切内核后音量被重新套上"。 */
    val volumeCalls = mutableListOf<Float>()

    override fun setVolume(volume: Float) {
        volumeValue = PlayerEngine.sanitizeVolume(volume)
        volumeCalls += volumeValue
    }

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

    /** 内核自报的播放指标（null = 不提供，与两个旧内核一致）。 */
    var metrics: String? = null

    override fun metricsLine(): String? = metrics

    /** 装载媒体时抛出的异常（模拟真机上内核/解码器初始化失败）。null = 正常。 */
    var failOnSetMedia: Throwable? = null

    override fun setMedia(src: MediaSourceRef) {
        failOnSetMedia?.let { throw it }
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

    override fun videoView(): View? = view

    override fun positionMs(): Long = position

    override fun durationMs(): Long = duration

    override fun release() {
        released = true
        isPlaying = false
        calls += "release"
    }
}

/**
 * 假画中画宿主（R13）：记录"页面告诉宿主什么"，并允许测试模拟"用户在 PIP 里点了按钮"。
 *
 * 真宿主是 :app 的 Activity（`enterPictureInPictureMode` + RemoteAction），
 * JVM 单测里只关心**页面这一侧的行为**：注册落点、更新动作、开关自动进入。
 */
internal class FakeVideoPipHost : VideoPipHost {

    private val _isInPip = MutableStateFlow(false)

    override val isInPip: StateFlow<Boolean> = _isInPip

    /** 注册进来的动作落点（页面在 init 里注册，onCleared 注销）。 */
    var sink: VideoPipActionSink? = null

    /** enterPip 的调用记录（宽高比）。 */
    val enterCalls = mutableListOf<Float>()

    /** updateActions 的调用记录（是否在播）。 */
    val actionUpdates = mutableListOf<Boolean>()

    /** setAutoEnterEnabled 的调用记录（R13 自动进 PIP）。 */
    val autoEnterUpdates = mutableListOf<Boolean>()

    /** enterPip 的返回值（模拟系统允许/拒绝）。 */
    var enterResult: Boolean = true

    override fun enterPip(aspectRatio: Float): Boolean {
        enterCalls += aspectRatio
        return enterResult
    }

    override fun updateActions(isPlaying: Boolean) {
        actionUpdates += isPlaying
    }

    override fun setAutoEnterEnabled(enabled: Boolean) {
        autoEnterUpdates += enabled
    }

    override fun setActionSink(sink: VideoPipActionSink?) {
        this.sink = sink
    }

    /** 模拟"系统进了 PIP"。 */
    fun enterPipForTest() {
        _isInPip.value = true
    }

    /** 模拟"用户在 PIP 里点了某个动作按钮"。 */
    fun press(action: VideoPipAction) {
        sink?.onAction(action)
    }
}

/** 假视频后台播放宿主（R18/R19）：记录会话绑定与"正在播放"上报。 */
internal class FakeVideoPlaybackHost : VideoPlaybackHost {

    /** 会话绑定/解绑流水（null = 收掉会话）。 */
    val sessions = mutableListOf<VideoSession?>()

    /** setActive 的调用记录（R19 让路）。 */
    val activeUpdates = mutableListOf<Boolean>()

    /** 最后一次绑定的会话。 */
    val last: VideoSession? get() = sessions.lastOrNull()

    override fun setActive(active: Boolean) {
        activeUpdates += active
    }

    /** 绑定播放会话时抛的异常（模拟"会话服务连不上"：清单缺 action、服务被系统限制…）。 */
    var failBind: Throwable? = null

    override fun bindSession(session: VideoSession?) {
        sessions += session
        failBind?.let { throw it }
    }
}

/** 假偏好：把每次写方法记下来，验证 R9/R10/R14 的持久化。 */
internal class FakeVideoPreferences(
    engine: EngineKind = EngineKind.MEDIA3,
    decoderMode: DecoderMode = DecoderMode.AUTO_HW,
) : VideoPlayerPreferences {

    private val _engine = MutableStateFlow(engine)

    private val _decoderMode = MutableStateFlow(decoderMode)

    override val engine: StateFlow<EngineKind> = _engine

    override val decoderMode: StateFlow<DecoderMode> = _decoderMode

    val engineWrites = mutableListOf<EngineKind>()

    val decoderWrites = mutableListOf<DecoderMode>()

    override suspend fun setEngine(kind: EngineKind) {
        engineWrites += kind
        _engine.value = kind
    }

    override suspend fun setDecoderMode(mode: DecoderMode) {
        decoderWrites += mode
        _decoderMode.value = mode
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
    override val pip: FakeVideoPipHost = FakeVideoPipHost(),
    override val playback: FakeVideoPlaybackHost = FakeVideoPlaybackHost(),
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

    /** 播放页上报的播放指标（起播耗时那一行），按上报顺序。 */
    val playbackMetrics = mutableListOf<String>()

    override fun reportPlaybackMetrics(line: String) {
        playbackMetrics += line
    }

    /** 列目录失败注入：key 是目录路径。 */
    val failSiblings = mutableMapOf<String, Throwable>()

    /** 最近建出来的内核。 */
    val last: FakePlayerEngine? get() = created.lastOrNull()

    /** 下一次建内核时给假内核挂上"装载抛异常"的注入点（用完即清）。 */
    var failSetMediaNext: Throwable? = null

    override suspend fun createEngine(kind: EngineKind): PlayerEngine {
        failCreateFor(kind)?.let { throw it }
        createCalls += kind
        val engine = FakePlayerEngine(kind, view = videoViewFor(kind))
        failSetMediaNext?.let { error ->
            engine.failOnSetMedia = error
            failSetMediaNext = null
        }
        created += engine
        return engine
    }

    override suspend fun siblings(path: String): List<RemoteEntry> {
        val dir = path.substringBeforeLast('/', "")
        failSiblings[dir]?.let { throw it }
        return VideoPlayerMath.playableEntries(entries[dir].orEmpty())
    }

    // ---- 时间戳重建（R3/R11）：结果与进度由测试摆布 ----

    /** 修复请求记录（按调用顺序）。 */
    val repairCalls = mutableListOf<String>()

    /** 修复结果注入（默认成功，指向修复根目录里的文件名）。 */
    var repairResult: TimestampRepairOutcome = TimestampRepairOutcome.Repaired("Show.repaired.mp4")

    /** 修复进度回调序列。 */
    var repairProgress: List<Int> = listOf(0, 42, 100)

    /** exitRepairRoot 调用次数。 */
    var exitRepairCalls = 0

    override suspend fun repairTimestamps(path: String, onProgress: (Int) -> Unit): TimestampRepairOutcome {
        repairCalls += path
        repairProgress.forEach(onProgress)
        return repairResult
    }

    override fun exitRepairRoot() {
        exitRepairCalls++
    }

    // ---- TS 索引（R3/R4）：准备结果与预取记录由测试摆布 ----

    /** prepareTsIndex 的调用记录。 */
    val tsIndexCalls = mutableListOf<String>()

    /** prepareTsIndex 的返回值（默认 null = 没有索引）。 */
    var tsIndexInfo: TsIndexInfo? = null

    /** prefetchSeek 的调用记录（路径 to 位置毫秒）。 */
    val prefetchCalls = mutableListOf<Pair<String, Long>>()

    override suspend fun prepareTsIndex(path: String): TsIndexInfo? {
        tsIndexCalls += path
        return tsIndexInfo
    }

    override suspend fun prefetchSeek(path: String, positionMs: Long, durationMs: Long) {
        prefetchCalls += path to positionMs
    }
}

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
