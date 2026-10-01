package io.github.gua123.mediagate.feature.player.video

import androidx.lifecycle.ViewModelStore
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TS 索引接线（**R3 / R4**）。
 *
 * 钉住两件事：① 没有 PCR 的 TS 要**如实提示**（拖拽会不准，并指出「修复时间戳」出口）；
 * ② 松手拖拽后要按索引预取落点。有 PCR 的文件不该打扰用户。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerTsIndexTest {

    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun `没有 PCR 的 TS 会提示拖拽不准`() = playerTest {
        val env = environment(tsIndexInfo = TsIndexInfo(hasPcr = false, keyframeCount = 12, complete = false))
        val vm = player(env, "Movies/Show.ts")
        settle()

        assertEquals(listOf("Movies/Show.ts"), env.tsIndexCalls)
        assertTrue(
            "要如实说明：${vm.state.value.tsIndexNotice}",
            vm.state.value.tsIndexNotice.orEmpty().contains("PCR"),
        )
    }

    @Test
    fun `有 PCR 的文件不打扰用户`() = playerTest {
        val env = environment(tsIndexInfo = TsIndexInfo(hasPcr = true, keyframeCount = 99, complete = true))
        val vm = player(env, "Movies/Show.ts")
        settle()

        assertEquals(1, env.tsIndexCalls.size)
        assertNull(vm.state.value.tsIndexNotice)
    }

    @Test
    fun `非 TS 文件既不准备索引也不提示`() = playerTest {
        val env = environment(tsIndexInfo = TsIndexInfo(hasPcr = false, keyframeCount = 0, complete = false))
        val vm = player(env, "Movies/Movie.mp4")
        settle()

        assertTrue("非 TS 不该去准备索引", env.tsIndexCalls.isEmpty())
        assertNull("没有 PCR 结论就不该提示", vm.state.value.tsIndexNotice)
    }

    @Test
    fun `松手拖拽后按索引预取落点`() = playerTest {
        val env = environment(tsIndexInfo = TsIndexInfo(hasPcr = true, keyframeCount = 99, complete = true))
        val vm = player(env, "Movies/Show.ts")
        settle()

        vm.onSeekChange(0.5f)
        vm.onSeekFinished()
        settle()

        assertEquals(1, env.prefetchCalls.size)
        assertEquals("Movies/Show.ts", env.prefetchCalls[0].first)
        assertTrue("预取位置要落在时间轴上：${env.prefetchCalls[0].second}", env.prefetchCalls[0].second >= 0L)
    }

    @Test
    fun `提示可以关掉`() = playerTest {
        val env = environment(tsIndexInfo = TsIndexInfo(hasPcr = false, keyframeCount = 0, complete = false))
        val vm = player(env, "Movies/Show.ts")
        settle()
        assertTrue(vm.state.value.tsIndexNotice != null)

        vm.dismissTsIndexNotice()
        settle()

        assertNull(vm.state.value.tsIndexNotice)
    }

    // ------------------------------------------------------------------ 脚手架

    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            stores.forEach { it.clear() }
        }
    }

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

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

    private fun environment(tsIndexInfo: TsIndexInfo?): FakeVideoPlayerEnvironment =
        FakeVideoPlayerEnvironment(
            entries = mapOf(
                "Movies" to listOf(entry("Movies/Show.ts"), entry("Movies/Movie.mp4")),
            ),
        ).also { it.tsIndexInfo = tsIndexInfo }

    private fun entry(path: String): RemoteEntry = RemoteEntry(
        name = path.substringAfterLast('/'),
        path = path,
        size = 10_000L,
        mtime = 1L,
    )
}
