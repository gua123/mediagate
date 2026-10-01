package io.github.gua123.mediagate.feature.player.video

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.engine.EngineKind

/**
 * 播放页与宿主能力之间的接线（**R13 画中画 / R18 视频会话 / R19 让路**）。
 *
 * 全部走真实的 [VideoPlayerViewModel] + 假宿主 / 假内核：验证"页面在什么时候告诉宿主什么"。
 * 真机上真正的 PIP 行为（系统小窗、RemoteAction 点击）与真机通知栏不在这里，见交付清单。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerHostTest {

    private val dispatcher = StandardTestDispatcher()

    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        clearViewModels()
        Dispatchers.resetMain()
    }

    /** 与 VideoPlayerViewModelTest 同一套路：收尾前必须先清 ViewModel（采样循环永不结束）。 */
    private fun hostTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            clearViewModels()
        }
    }

    private fun clearViewModels() {
        stores.forEach { it.clear() }
        stores.clear()
    }

    private fun TestScope.player(env: FakeVideoPlayerEnvironment, path: String = "Movies/b.mp4"): VideoPlayerViewModel {
        val vm = VideoPlayerViewModel(
            environment = env,
            initialPath = path,
            io = dispatcher,
            exitScope = backgroundScope,
        )
        val store = ViewModelStore()
        store.put("player-video-host", vm)
        stores += store
        return vm
    }

    private fun environment(): FakeVideoPlayerEnvironment = FakeVideoPlayerEnvironment(entries = movieEntries())

    private fun movieEntries(): Map<String, List<RemoteEntry>> = mapOf(
        "Movies" to listOf(
            videoEntry("Movies/a.mp4"),
            videoEntry("Movies/b.mp4"),
            audioEntry("Movies/c.mp3"),
        ),
    )

    // ---------------------------------------------------------------- R13 画中画

    @Test
    fun pipSinkIsRegisteredOnEntryAndDroppedOnClear() = hostTest {
        val env = environment()
        player(env)
        settle()

        assertNotNull("进播放页就要把 PIP 动作落点交给宿主", env.pip.sink)

        clearViewModels()
        assertNull("退出播放页必须注销落点（否则小窗按钮会打到已销毁的页面）", env.pip.sink)
    }

    @Test
    fun autoEnterPipFollowsPlayingState() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)

        assertEquals("播放中要允许按 Home 自动进 PIP", true, env.pip.autoEnterUpdates.last())

        vm.togglePlayPause()
        tick(1)

        assertFalse("暂停后不能再自动进 PIP", env.pip.autoEnterUpdates.last())
    }

    @Test
    fun autoEnterIsOffWhileLoading() = hostTest {
        val env = environment()
        env.failSiblings["Movies"] = io.github.gua123.mediagate.data.storage.api.StorageException.NotFound("没有这个目录")

        player(env)
        settle()

        assertFalse("加载失败时不该允许自动进 PIP", env.pip.autoEnterUpdates.last())
    }

    @Test
    fun pipActionButtonsFollowPlayingState() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)

        assertEquals(true, env.pip.actionUpdates.last())

        vm.togglePlayPause()
        tick(1)

        assertEquals(false, env.pip.actionUpdates.last())
    }

    @Test
    fun leavingThePageTurnsAutoEnterOff() = hostTest {
        val env = environment()
        player(env)
        settle()
        tick(1)

        clearViewModels()

        assertFalse(env.pip.autoEnterUpdates.last())
        assertEquals(false, env.pip.actionUpdates.last())
    }

    @Test
    fun pipTogglePauseDrivesTheCurrentEngine() = hostTest {
        val env = environment()
        player(env)
        settle()
        tick(1)
        val engine = env.last!!
        assertTrue(engine.isPlaying)

        env.pip.press(VideoPipAction.TOGGLE_PLAY_PAUSE)
        tick(1)

        assertFalse("PIP 里的暂停要真的打到内核上", engine.isPlaying)
    }

    @Test
    fun pipRewindSeeksBackTenSeconds() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)
        val engine = env.last!!
        engine.position = 30_000L
        engine.duration = 600_000L

        env.pip.press(VideoPipAction.REWIND_10S)

        assertEquals(listOf(20_000L), engine.seekCalls)
        assertEquals(20_000L, vm.state.value.positionMs)
    }

    @Test
    fun pipRewindClampsAtZero() = hostTest {
        val env = environment()
        player(env)
        settle()
        tick(1)
        val engine = env.last!!
        engine.position = 4_000L
        engine.duration = 600_000L

        env.pip.press(VideoPipAction.REWIND_10S)

        assertEquals(listOf(0L), engine.seekCalls)
    }

    @Test
    fun pipForwardClampsAtDuration() = hostTest {
        val env = environment()
        player(env)
        settle()
        tick(1)
        val engine = env.last!!
        engine.position = 595_000L
        engine.duration = 600_000L

        env.pip.press(VideoPipAction.FORWARD_10S)

        assertEquals(listOf(600_000L), engine.seekCalls)
    }

    @Test
    fun seekByUsesTheLiveEnginePosition() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)
        val engine = env.last!!
        // UI 采样值还停在 0，但内核已经走到 100 秒：跳转必须以内核为准
        engine.position = 100_000L
        engine.duration = 600_000L

        vm.seekBy(-VideoPipMath.SEEK_STEP_MS)

        assertEquals(listOf(90_000L), engine.seekCalls)
    }

    // ---------------------------------------------------------------- R18 视频会话

    @Test
    fun sessionIsBoundToTheCurrentEngineAndMedia() = hostTest {
        val env = environment()
        player(env)
        settle()

        val session = env.playback.last
        assertNotNull("起播后必须把会话交给宿主（通知栏/锁屏要用）", session)
        assertSame(env.last, session!!.engine)
        assertEquals("Movies/b.mp4", session.path)
        assertEquals("b.mp4", session.title)
        assertEquals("fake:video", session.backendId)
    }

    @Test
    fun sessionIsDroppedWhenLeavingThePage() = hostTest {
        val env = environment()
        player(env)
        settle()

        clearViewModels()

        assertNull("退出播放页要收掉会话（通知栏不该留一条点不动的条目）", env.playback.last)
    }

    @Test
    fun openingAnotherEpisodeRebindsTheSession() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()

        vm.next()
        settle()

        val session = env.playback.last
        assertNotNull(session)
        assertEquals("Movies/c.mp3", session!!.path)
        assertEquals("c.mp3", session.title)
    }

    @Test
    fun switchingEngineRebindsTheSessionToTheNewEngine() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        val first = env.last

        vm.switchEngine()
        settle()

        val session = env.playback.last
        assertNotNull(session)
        assertEquals(EngineKind.VLC, session!!.engine.kind)
        assertFalse("旧内核不该再被会话持有", session.engine === first)
    }

    // ---------------------------------------------------------------- R19 让路

    @Test
    fun playbackActiveIsReportedWhilePlaying() = hostTest {
        val env = environment()
        player(env)
        settle()
        tick(1)

        assertEquals(true, env.playback.activeUpdates.last())
    }

    @Test
    fun playbackActiveIsReleasedOnPause() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)

        vm.togglePlayPause()
        tick(1)

        assertEquals(false, env.playback.activeUpdates.last())
    }

    @Test
    fun playbackActiveIsReleasedWhenPlaybackEnds() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)
        val engine = env.last!!
        engine.isPlaying = false
        engine.state.value = io.github.gua123.mediagate.media.engine.EngineState.Ended

        tick(1)

        assertTrue(vm.state.value.ended)
        assertEquals(false, env.playback.activeUpdates.last())
    }

    @Test
    fun playbackActiveIsReleasedWhenLeavingThePage() = hostTest {
        val env = environment()
        player(env)
        settle()
        tick(1)

        clearViewModels()

        assertEquals(false, env.playback.activeUpdates.last())
    }
}
