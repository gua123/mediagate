package io.github.gua123.mediagate.feature.player.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * [AudioPlayerViewModel] 的 JVM 单测（R1 音频 / R18）。
 *
 * 用假宿主 + 假解码器驱动真实 ViewModel：验证「列同目录音频 → 从点中的那首起播 →
 * 上一首/下一首下标轮转 → 倍速与循环模式 → 拖拽 seek 的提交时机 → 封面链路 → 失败分类」。
 * 全部跑在测试调度器上，不依赖 Android 与真实文件系统。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        environment: FakeAudioPlayerEnvironment,
        path: String,
        decoder: AudioCoverDecoder = FakeAudioCoverDecoder(),
    ) = AudioPlayerViewModel(
        environment = environment,
        initialPath = path,
        decoder = decoder,
        io = dispatcher,
    )

    /** 三首音频的目录（顺序即队列顺序）。 */
    private fun musicEntries(): Map<String, List<RemoteEntry>> = mapOf(
        "Music" to listOf(
            audioEntry("Music/a.mp3"),
            otherEntry("Music/cover.jpg"),
            audioEntry("Music/b.flac"),
            audioEntry("Music/c.ogg"),
        ),
    )

    @Test
    fun `进入页面后按同目录音频建队列并从点中的那首开始`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())

        val vm = viewModel(env, "Music/b.flac")
        advanceUntilIdle()

        assertEquals(
            listOf(listOf("Music/a.mp3", "Music/b.flac", "Music/c.ogg") to 1),
            env.playCalls,
        )
        val state = vm.state.value
        assertEquals(AudioPlayerStatus.READY, state.status)
        assertEquals(3, state.count)
        assertEquals(2, state.position)
        assertEquals("Music/b.flac", state.path)
        assertEquals("b.flac", state.title)
    }

    @Test
    fun `目录里没有音频时至少播点中的那一首`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(mapOf("Music" to listOf(otherEntry("Music/readme.txt"))))

        val vm = viewModel(env, "Music/only.mp3")
        advanceUntilIdle()

        assertEquals(listOf(listOf("Music/only.mp3") to 0), env.playCalls)
        assertEquals(1, vm.state.value.count)
    }

    @Test
    fun `列目录失败给出分类错误且不开始播放`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        env.failSiblings["Music"] = StorageException.AccessDenied("无读权限")

        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()

        assertEquals(AudioPlayerStatus.ERROR, vm.state.value.status)
        assertEquals(AudioPlayerErrorKind.ACCESS_DENIED, vm.state.value.errorKind)
        assertTrue("失败时不应该提交播放", env.playCalls.isEmpty())
    }

    @Test
    fun `下一首按轮转规则提交下标`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()

        // 顺序播放：1 → 2
        vm.next()
        // 顺序播放的最后一首：停在原地（仍是 2）
        env.push(env.state.value.copy(index = 2, repeatMode = AudioRepeatMode.OFF))
        advanceUntilIdle()
        vm.next()
        // 列表循环：最后一首绕回第一首
        env.push(env.state.value.copy(index = 2, repeatMode = AudioRepeatMode.ALL))
        advanceUntilIdle()
        vm.next()

        assertEquals(listOf(1, 2, 0), env.playAtIndexes)
    }

    @Test
    fun `上一首按轮转规则提交下标`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()

        env.push(env.state.value.copy(index = 1, repeatMode = AudioRepeatMode.OFF))
        advanceUntilIdle()
        vm.previous()
        env.push(env.state.value.copy(index = 0, repeatMode = AudioRepeatMode.ALL))
        advanceUntilIdle()
        vm.previous()

        assertEquals(listOf(0, 2), env.playAtIndexes)
    }

    @Test
    fun `倍速与循环模式轮转提交给宿主`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()

        vm.cycleSpeed()
        advanceUntilIdle()
        vm.cycleSpeed()
        advanceUntilIdle()
        vm.cycleRepeatMode()
        advanceUntilIdle()
        vm.cycleRepeatMode()
        advanceUntilIdle()

        assertEquals(listOf(1.5f, 2f), env.speeds)
        assertEquals(listOf(AudioRepeatMode.ALL, AudioRepeatMode.ONE), env.repeatModes)
        assertEquals("2×", vm.state.value.speedLabel)
        assertEquals(AudioRepeatMode.ONE, vm.state.value.repeatMode)
    }

    @Test
    fun `播放暂停切换交给宿主`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()

        vm.togglePlayPause()
        advanceUntilIdle()

        assertEquals(1, env.toggleCount)
    }

    @Test
    fun `拖拽进度条不打扰播放器，松手才 seek`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()
        env.push(env.state.value.copy(durationMs = 100_000, positionMs = 0))
        advanceUntilIdle()

        vm.onSeekChange(0.5f)
        advanceUntilIdle()

        assertTrue("拖拽中不能 seek", env.seekCalls.isEmpty())
        assertEquals(50_000L, vm.state.value.displayPositionMs)

        vm.onSeekFinished()
        advanceUntilIdle()

        assertEquals(listOf(50_000L), env.seekCalls)
    }

    @Test
    fun `服务推来的进度与时长进入状态`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()

        env.push(
            env.state.value.copy(
                playing = true,
                positionMs = 30_000,
                durationMs = 60_000,
                buffering = true,
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(30_000L, state.positionMs)
        assertEquals(60_000L, state.durationMs)
        assertEquals("0:30", state.positionText)
        assertEquals("1:00", state.durationText)
        assertEquals(0.5f, state.progress, 0.0001f)
        assertTrue(state.playing)
        assertTrue(state.buffering)
    }

    @Test
    fun `封面走缩略图仓库并在解码后进入状态`() = runTest(dispatcher) {
        val repo = testThumbnailRepository(root = tmp.newFolder("thumbs"), io = dispatcher)
        val env = FakeAudioPlayerEnvironment(
            entries = musicEntries(),
            thumbnails = repo,
            backend = FakeAudioBackend(),
        )
        val decoder = FakeAudioCoverDecoder(FakeAudioCover(width = 42, height = 24))

        val vm = viewModel(env, "Music/a.mp3", decoder)
        advanceUntilIdle()

        assertEquals("封面只解码一次", 1, decoder.calls)
        assertEquals(42, vm.state.value.cover?.width)
        assertEquals(24, vm.state.value.cover?.height)
    }

    @Test
    fun `没有封面字节时不解码`() = runTest(dispatcher) {
        val repo = testThumbnailRepository(root = tmp.newFolder("thumbs"), bytes = null, io = dispatcher)
        val env = FakeAudioPlayerEnvironment(
            entries = musicEntries(),
            thumbnails = repo,
            backend = FakeAudioBackend(),
        )
        val decoder = FakeAudioCoverDecoder()

        val vm = viewModel(env, "Music/a.mp3", decoder)
        advanceUntilIdle()

        assertEquals(0, decoder.calls)
        assertNull(vm.state.value.cover)
    }

    @Test
    fun `还没选根目录时也能播（封面为空不报错）`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries(), backend = null)
        val decoder = FakeAudioCoverDecoder()

        val vm = viewModel(env, "Music/a.mp3", decoder)
        advanceUntilIdle()

        assertEquals(0, decoder.calls)
        assertNull(vm.state.value.cover)
        assertEquals(AudioPlayerStatus.READY, vm.state.value.status)
    }

    @Test
    fun `重试会重新列目录并重新起播`() = runTest(dispatcher) {
        val env = FakeAudioPlayerEnvironment(musicEntries())
        env.failSiblings["Music"] = StorageException.Network("断网")
        val vm = viewModel(env, "Music/a.mp3")
        advanceUntilIdle()
        assertEquals(AudioPlayerStatus.ERROR, vm.state.value.status)

        env.failSiblings.clear()
        vm.retry()
        advanceUntilIdle()

        assertEquals(AudioPlayerStatus.READY, vm.state.value.status)
        assertEquals(1, env.playCalls.size)
        assertEquals(0, env.playCalls.first().second)
    }
}
