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
 * 装载媒体失败要"降级成错误卡片"而不是让 App 消失（**2026-10-03 真机"一播 MP4 就闪退"**）。
 *
 * 真机上内核/解码器抛异常时（MediaCodec 初始化失败、容器不支持…），
 * 之前异常会顺着 viewModelScope 冒到默认处理器 → 整机闪退；现在必须落到播放页的错误状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerLoadFailureTest {

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
    fun `装载抛异常时进错误状态而不是崩掉`() = playerTest {
        val env = FakeVideoPlayerEnvironment(entries = mapOf("Movies" to listOf(entry("Movies/a.mp4"))))
        val vm = player(env, "Movies/a.mp4")
        // 先让它完成一次建内核，再把注入点挂上（下一次装载就会抛）
        settle()
        env.failSetMediaNext = IllegalStateException("解码器初始化失败")

        vm.retry()
        settle()

        assertEquals(VideoPlayerStatus.ERROR, vm.state.value.status)
        assertTrue(
            "错误详情要带上原因：" + vm.state.value.errorDetail,
            vm.state.value.errorDetail.orEmpty().contains("解码器初始化失败"),
        )
        assertFalse("不该还显示在播放", vm.state.value.playing)
    }

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
        val vm = VideoPlayerViewModel(environment = env, initialPath = path, io = dispatcher, exitScope = backgroundScope)
        val store = ViewModelStore()
        store.put("player-video", vm)
        stores += store
        return vm
    }

    private fun entry(path: String) = RemoteEntry(name = path.substringAfterLast('/'), path = path, size = 1_000L, mtime = 1L)
}
