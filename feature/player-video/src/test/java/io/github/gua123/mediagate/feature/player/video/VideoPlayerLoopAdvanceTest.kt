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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineState

/** 播完续播（循环四档）在真实 ViewModel 上的落地：假内核把状态推到 Ended，看页面会不会换集。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerLoopAdvanceTest {

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

    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
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

    @Test
    fun 文件夹循环_播完自动下一集() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        assertEquals(1, vm.state.value.siblingIndex)
        vm.cycleLoopMode() // OFF -> FOLDER
        settle()
        assertEquals(VideoLoopMode.FOLDER, vm.state.value.loopMode)

        val engine = env.last!!
        engine.position = 9_000L
        engine.duration = 9_000L
        engine.isPlaying = false
        engine.state.value = EngineState.Ended
        tick(1)

        assertEquals("Movies/c.mp3", vm.state.value.path)
        assertEquals(2, vm.state.value.siblingIndex)
        assertTrue(engine.calls.contains("setMedia:Movies/c.mp3"))
        assertEquals("换集复用同一个内核", listOf(EngineKind.VLC), env.createCalls)
    }

    @Test
    fun 文件夹循环_最后一集播完回到第一个() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/c.mp3")
        settle()
        assertEquals(2, vm.state.value.siblingIndex)
        vm.cycleLoopMode()
        settle()

        val engine = env.last!!
        engine.isPlaying = false
        engine.state.value = EngineState.Ended
        tick(1)

        assertEquals("Movies/a.mp4", vm.state.value.path)
        assertEquals(0, vm.state.value.siblingIndex)
    }

    @Test
    fun 单曲循环_播完重播自己() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        repeat(2) { vm.cycleLoopMode(); settle() } // OFF -> FOLDER -> SINGLE
        assertEquals(VideoLoopMode.SINGLE, vm.state.value.loopMode)

        val engine = env.last!!
        engine.isPlaying = false
        engine.state.value = EngineState.Ended
        tick(1)

        assertEquals("Movies/b.mp4", vm.state.value.path)
        assertTrue("单曲循环要回到开头再播", engine.seekCalls.contains(0L))
        assertTrue("重播要真的把内核拉起来", engine.isPlaying)
        assertFalse(vm.state.value.ended)
    }

    @Test
    fun 不循环_播完就停() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        assertEquals(VideoLoopMode.OFF, vm.state.value.loopMode)

        val engine = env.last!!
        engine.isPlaying = false
        engine.state.value = EngineState.Ended
        tick(1)

        assertEquals("Movies/b.mp4", vm.state.value.path)
        assertTrue(vm.state.value.ended)
        assertFalse("不循环不该偷偷续播", engine.calls.contains("setMedia:Movies/c.mp3"))
    }

    // ------------------------------------------------------------------ 脚手架

    private fun TestScope.player(env: FakeVideoPlayerEnvironment, path: String): VideoPlayerViewModel {
        val vm = VideoPlayerViewModel(
            environment = env,
            initialPath = path,
            io = dispatcher,
            exitScope = backgroundScope,
        )
        val store = ViewModelStore()
        store.put("player-video", vm)
        stores += store
        return vm
    }

    private fun environment(): FakeVideoPlayerEnvironment = FakeVideoPlayerEnvironment(
        entries = mapOf(
            "Movies" to listOf(
                videoEntry("Movies/a.mp4"),
                otherEntry("Movies/cover.jpg"),
                videoEntry("Movies/b.mp4"),
                audioEntry("Movies/c.mp3"),
            ),
        ),
    )
}
