package io.github.gua123.mediagate.feature.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * [AudioPlayerUiState.reduce] 的 JVM 单测（R1/R18）：状态迁移与派生文案。
 *
 * 重点覆盖「服务推状态」与「用户拖进度条」这两股力量的优先级：
 * 拖拽中用户的手指是权威，松手后播放器才接管。
 */
class AudioPlayerUiStateTest {

    private val snapshot = AudioPlaybackSnapshot(
        queue = listOf(AudioTrack("a.mp3", "a.mp3"), AudioTrack("b.mp3", "b.mp3")),
        index = 1,
        playing = true,
        buffering = false,
        ready = true,
        positionMs = 10_000,
        durationMs = 240_000,
        speed = 1f,
        repeatMode = AudioRepeatMode.ALL,
        connected = true,
    )

    @Test
    fun `服务状态覆盖队列下标时长与播放标志`() {
        val state = AudioPlayerUiState().reduce(AudioPlayerEvent.SnapshotChanged(snapshot))

        assertEquals(AudioPlayerStatus.READY, state.status)
        assertEquals(2, state.count)
        assertEquals(2, state.position)
        assertEquals("b.mp3", state.title)
        assertEquals("b.mp3", state.path)
        assertTrue(state.playing)
        assertEquals(10_000L, state.positionMs)
        assertEquals(240_000L, state.durationMs)
        assertEquals(AudioRepeatMode.ALL, state.repeatMode)
        assertTrue(state.canSwitch)
    }

    @Test
    fun `服务队列为空时不把状态顶成 READY`() {
        val state = AudioPlayerUiState().reduce(
            AudioPlayerEvent.SnapshotChanged(AudioPlaybackSnapshot(connected = false)),
        )

        assertEquals(AudioPlayerStatus.LOADING, state.status)
        assertNull(state.track)
        assertFalse(state.hasQueue)
        assertEquals(0, state.position)
    }

    @Test
    fun `下标越界被钳进合法范围`() {
        val state = AudioPlayerUiState().reduce(
            AudioPlayerEvent.SnapshotChanged(snapshot.copy(index = 9)),
        )

        assertEquals(1, state.index)
        assertEquals("b.mp3", state.path)
    }

    @Test
    fun `拖拽中不被播放进度覆盖，松手才提交位置`() {
        var state = AudioPlayerUiState().reduce(AudioPlayerEvent.SnapshotChanged(snapshot))

        state = state.reduce(AudioPlayerEvent.SeekChanged(0.25f))
        assertTrue(state.dragging)
        assertEquals(60_000L, state.dragPositionMs)
        assertEquals("拖拽中显示的是拖到的位置", 60_000L, state.displayPositionMs)

        // 拖拽期间服务推来旧进度：不能把手指的位置顶掉
        state = state.reduce(AudioPlayerEvent.SnapshotChanged(snapshot.copy(positionMs = 10_500)))
        assertEquals(60_000L, state.displayPositionMs)
        assertEquals(10_500L, state.positionMs)

        state = state.reduce(AudioPlayerEvent.SeekFinished)
        assertFalse(state.dragging)
        assertEquals(60_000L, state.positionMs)
        assertEquals(60_000L, state.displayPositionMs)
    }

    @Test
    fun `时长未知时比例归零`() {
        val state = AudioPlayerUiState().reduce(
            AudioPlayerEvent.SnapshotChanged(snapshot.copy(durationMs = 0, positionMs = 5_000)),
        )

        assertEquals(0f, state.progress, 0.0001f)
        assertEquals(AudioPlayerMath.UNKNOWN_TIME, state.durationText)
    }

    @Test
    fun `进度与文案派生`() {
        val state = AudioPlayerUiState().reduce(AudioPlayerEvent.SnapshotChanged(snapshot))

        assertEquals(10_000f / 240_000f, state.progress, 0.0001f)
        assertEquals("0:10", state.positionText)
        assertEquals("4:00", state.durationText)
        assertEquals("1×", state.speedLabel)
    }

    @Test
    fun `加载失败给出分类与详情`() {
        val state = AudioPlayerUiState()
            .reduce(AudioPlayerEvent.LoadStarted("Music/a.mp3"))
            .reduce(AudioPlayerEvent.LoadFailed(AudioPlayerErrorKind.ACCESS_DENIED, "无读权限"))

        assertEquals(AudioPlayerStatus.ERROR, state.status)
        assertEquals(AudioPlayerErrorKind.ACCESS_DENIED, state.errorKind)
        assertEquals("无读权限", state.errorDetail)
    }

    @Test
    fun `封面只认当前曲目的结果`() {
        val base = AudioPlayerUiState(coverPath = "b.mp3")

        val stale = base.reduce(AudioPlayerEvent.CoverLoaded("a.mp3", FakeAudioCover()))
        assertNull("过期的封面结果要丢掉", stale.cover)

        val fresh = base.reduce(AudioPlayerEvent.CoverLoaded("b.mp3", FakeAudioCover(width = 8, height = 8)))
        assertEquals(8, fresh.cover?.width)
    }

    @Test
    fun `服务错误信息进入详情`() {
        val state = AudioPlayerUiState().reduce(
            AudioPlayerEvent.SnapshotChanged(snapshot.copy(errorMessage = "文件不存在")),
        )

        assertEquals("文件不存在", state.errorDetail)
    }

    @Test
    fun `后端异常分类`() {
        assertEquals(AudioPlayerErrorKind.ACCESS_DENIED, AudioPlayerErrors.classify(StorageException.AccessDenied()))
        assertEquals(AudioPlayerErrorKind.NOT_FOUND, AudioPlayerErrors.classify(StorageException.NotFound()))
        assertEquals(AudioPlayerErrorKind.NOT_SUPPORTED, AudioPlayerErrors.classify(StorageException.NotSupported()))
        assertEquals(AudioPlayerErrorKind.NETWORK, AudioPlayerErrors.classify(StorageException.Network()))
        assertEquals(AudioPlayerErrorKind.AUTH, AudioPlayerErrors.classify(StorageException.Auth()))
        assertEquals(AudioPlayerErrorKind.UNKNOWN, AudioPlayerErrors.classify(IllegalStateException("其它")))
    }
}
