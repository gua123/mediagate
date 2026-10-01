package io.github.gua123.mediagate.feature.player.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.thumbnail.FrameExtractor
import io.github.gua123.mediagate.media.thumbnail.ThumbnailCache
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 假封面（真机是 [BitmapAudioCover]，JVM 单测里造不出 Bitmap）。 */
internal class FakeAudioCover(
    override val width: Int = 512,
    override val height: Int = 512,
) : AudioCover

/** 假封面解码器：记录调用次数，可注入「没有封面」。 */
internal class FakeAudioCoverDecoder(
    private val cover: AudioCover? = FakeAudioCover(),
) : AudioCoverDecoder {

    var calls: Int = 0
        private set

    override suspend fun decode(bytes: ByteArray, targetWidth: Int): AudioCover? {
        calls++
        return cover
    }
}

/** 固定返回一段字节的抽帧/封面提取器（真机是 MMR / FFmpeg）。 */
internal class FixedBytesExtractor(private val bytes: ByteArray?) : FrameExtractor {

    override suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray? = bytes
}

/**
 * 造一个真实可用的缩略图仓库（临时目录 + 假提取器），用于验证封面链路。
 *
 * [io] 默认跟随测试调度器：缩略图流水线里所有 withContext(io) 都跑在测试调度器上，
 * advanceUntilIdle() 才能等到封面真的加载完（用真实的 Dispatchers.IO 会变成不确定的竞态）。
 */
internal fun testThumbnailRepository(
    root: File = File(System.getProperty("java.io.tmpdir"), "mediagate-audio-" + UUID.randomUUID()),
    bytes: ByteArray? = byteArrayOf(1, 2, 3),
    io: CoroutineDispatcher = Dispatchers.IO,
): ThumbnailRepository = ThumbnailRepository(
    cache = ThumbnailCache(rootDir = File(root, "thumbs"), io = io),
    primary = FixedBytesExtractor(bytes),
    audioArtwork = FixedBytesExtractor(bytes),
    io = io,
)

/** 只支持「整文件顺序读」的最小后端，够缩略图流水线打开数据源即可。 */
internal class FakeAudioBackend(
    private val bytes: ByteArray = ByteArray(64) { 1 },
    override val id: String = "fake:audio",
) : StorageBackend {

    override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 2)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()

    override suspend fun stat(path: String): RemoteEntry = RemoteEntry(name = path, path = path)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
        object : RangeStream {
            private var pos = offset.toInt()
            override val length: Long = (bytes.size - offset).coerceAtLeast(0L)

            override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                val remaining = (bytes.size - pos).coerceAtLeast(0)
                if (remaining == 0) return -1
                val n = minOf(len, remaining)
                System.arraycopy(bytes, pos, buf, off, n)
                pos += n
                return n
            }

            override suspend fun seek(position: Long) {
                pos = (offset + position).toInt()
            }

            override fun position(): Long = pos.toLong()

            override fun close() = Unit
        }

    override suspend fun write(path: String, data: InputStream) = Unit

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}

/**
 * 假宿主（:app 的实现连的是 MediaSessionService）：把命令记下来并**同步回写状态流**，
 * 这样 ViewModel 的反应与真机上「服务推状态」一致。
 */
internal class FakeAudioPlayerEnvironment(
    var entries: Map<String, List<RemoteEntry>> = emptyMap(),
    override val thumbnails: ThumbnailRepository = testThumbnailRepository(),
    backend: StorageBackend? = null,
) : AudioPlayerEnvironment {

    private val _state = MutableStateFlow(AudioPlaybackSnapshot(connected = true))

    override val state: StateFlow<AudioPlaybackSnapshot> = _state

    private val _backend = MutableStateFlow(backend)

    override val backend: StateFlow<StorageBackend?> = _backend

    /** 列目录失败注入：key 是目录路径。 */
    val failSiblings = mutableMapOf<String, Throwable>()

    /** play(paths, startIndex) 的调用记录。 */
    val playCalls = mutableListOf<Pair<List<String>, Int>>()

    /** playAt(index) 的调用记录。 */
    val playAtIndexes = mutableListOf<Int>()

    val seekCalls = mutableListOf<Long>()

    val speeds = mutableListOf<Float>()

    val repeatModes = mutableListOf<AudioRepeatMode>()

    var toggleCount: Int = 0
        private set

    override suspend fun audioSiblings(path: String): List<RemoteEntry> {
        val dir = path.substringBeforeLast('/', "")
        failSiblings[dir]?.let { throw it }
        return entries[dir].orEmpty()
    }

    override suspend fun play(paths: List<String>, startIndex: Int) {
        playCalls += paths to startIndex
        _state.value = _state.value.copy(
            queue = paths.map { AudioTrack(path = it, title = it.substringAfterLast('/')) },
            index = startIndex,
            playing = true,
            ready = true,
        )
    }

    override fun playAt(index: Int) {
        playAtIndexes += index
        _state.value = _state.value.copy(index = index)
    }

    override fun togglePlayPause() {
        toggleCount++
        _state.value = _state.value.copy(playing = !_state.value.playing)
    }

    override fun seekTo(positionMs: Long) {
        seekCalls += positionMs
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    override fun setSpeed(speed: Float) {
        speeds += speed
        _state.value = _state.value.copy(speed = speed)
    }

    override fun setRepeatMode(mode: AudioRepeatMode) {
        repeatModes += mode
        _state.value = _state.value.copy(repeatMode = mode)
    }

    /** 模拟后台服务推来一条状态（进度、时长、暂停…）。 */
    fun push(snapshot: AudioPlaybackSnapshot) {
        _state.value = snapshot
    }
}

/** 造一个音频目录项。 */
internal fun audioEntry(path: String): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = 1024L,
    mtime = 1_700_000_000_000L,
)

/** 造一个非音频目录项（列目录时要被过滤掉）。 */
internal fun otherEntry(path: String): RemoteEntry = RemoteEntry(
    name = path.substringAfterLast('/'),
    path = path,
    size = 1024L,
    mtime = 0L,
)
