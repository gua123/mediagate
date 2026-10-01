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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 时间戳重建的接线单测（**R3 / R11**）。
 *
 * 覆盖：TS 家族才给入口 → 重建成功自动换成修复版续播 → 失败给中文原因 → 退出播放页恢复后端。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerTimestampRepairTest {

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
    fun tsFamilyGetsRepairEntryOthersDoNot() = playerTest {
        val ts = player(environment(), "Movies/Show.ts")
        settle()
        assertTrue("TS 家族应给入口：${ts.state.value.path}", ts.state.value.canRepairTimestamps)

        val mp4 = player(environment(), "Movies/Movie.mp4")
        settle()
        assertFalse("MP4 不需要这条出口", mp4.state.value.canRepairTimestamps)
    }

    @Test
    fun repairSuccessSwitchesToRepairedVersion() = playerTest {
        val env = environment()
        env.repairResult = TimestampRepairOutcome.Repaired("Show.repaired.mp4")
        val vm = player(env, "Movies/Show.ts")
        settle()

        vm.repairTimestamps()
        settle()

        assertEquals(listOf("Movies/Show.ts"), env.repairCalls)
        val notice = vm.state.value.timestampRepairNotice.orEmpty()
        assertTrue("应提示已换成修复版：$notice", notice.contains("修复"))
        assertEquals("播放路径要切到修复产物", "Show.repaired.mp4", vm.state.value.path)
        assertFalse("重建结束后不该还在跑", vm.state.value.timestampRepairRunning)
    }

    @Test
    fun repairFailureKeepsOriginalAndShowsChineseReason() = playerTest {
        val env = environment()
        val reason = "时间戳重建失败：FFmpeg 执行失败（return code=1）"
        env.repairResult = TimestampRepairOutcome.Failed(reason)
        val vm = player(env, "Movies/Show.ts")
        settle()

        vm.repairTimestamps()
        settle()

        assertEquals(reason, vm.state.value.timestampRepairNotice)
        assertEquals("失败时保持原路径", "Movies/Show.ts", vm.state.value.path)
        assertFalse(vm.state.value.timestampRepairRunning)
    }

    @Test
    fun clearingPlayerExitsRepairRoot() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/Show.ts")
        settle()
        vm.repairTimestamps()
        settle()

        stores.forEach { it.clear() }
        assertTrue("退出播放页要恢复视频后端", env.exitRepairCalls >= 1)
    }

    @Test
    fun repairNoticeCanBeDismissed() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/Show.ts")
        settle()
        vm.repairTimestamps()
        settle()

        vm.dismissTimestampRepairNotice()
        settle()

        assertEquals(null, vm.state.value.timestampRepairNotice)
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

    private fun environment(): FakeVideoPlayerEnvironment = FakeVideoPlayerEnvironment(
        entries = mapOf(
            "Movies" to listOf(
                entry("Movies/Show.ts"),
                entry("Movies/Show.srt"),
                entry("Movies/Movie.mp4"),
            ),
        ),
    )

    private fun entry(path: String): RemoteEntry = RemoteEntry(
        name = path.substringAfterLast('/'),
        path = path,
        size = 1_000L,
        mtime = 0L,
    )
}
