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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **锁屏 / 按 HOME 之后播放必须继续**（R18；退出语义见 2026-11-18 用户要求）。
 *
 * 这一层钉的是播放页自己的责任边界：
 * - 锁屏（Activity 走 ON_PAUSE，永远不走 ON_STOP —— 锁屏不会销毁 Activity）**只能**标记"我在后台"，
 *   不许停播、不许释放内核、不许把后台会话收掉；
 * - 回到前台时，如果内核被系统/网络搞停了就要接着播（R18「息屏不中断」）；还在播就别重复 play()（会音画抖一下）；
 * - 只有**返回键 / 左上角**那条路（[VideoPlayerViewModel.stop]）才停播、收会话、释放内核。
 *
 * 真机才能验证的部分（澎湃 OS 的后台冻结、Wi-Fi 省电、MediaSession 前台化）不在这里，
 * 见交付说明里的「必须真机验证」清单。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSurvivalTest {

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

    private fun TestScope.player(
        env: FakeVideoPlayerEnvironment,
        path: String = "Movies/b.mp4",
    ): VideoPlayerViewModel {
        val vm = VideoPlayerViewModel(
            environment = env,
            initialPath = path,
            io = dispatcher,
            exitScope = backgroundScope,
        )
        val store = ViewModelStore()
        store.put("background-survival", vm)
        stores += store
        return vm
    }

    private fun environment(): FakeVideoPlayerEnvironment =
        FakeVideoPlayerEnvironment(entries = mapOf("Movies" to listOf(videoEntry("Movies/a.mp4"), videoEntry("Movies/b.mp4"))))

    // ---------------------------------------------------------------- 锁屏 = 继续播

    @Test
    fun 锁屏或按HOME只记后台_不停播_不释放内核_不收会话() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)
        val engine = env.last!!
        assertTrue("前提：起播后内核确实在播", engine.isPlaying)
        val sessionsBefore = env.playback.sessions.size

        // 锁屏 / 按 HOME：Activity 走 ON_PAUSE，落到 onHostPause
        vm.onHostPause()
        tick(2)

        assertTrue("锁屏不能把内核停掉（R18：息屏/切后台不中断）", engine.isPlaying)
        assertFalse("锁屏不能释放内核", engine.released)
        assertEquals("锁屏不能收掉后台会话（通知栏/锁屏还得能控这个视频）", sessionsBefore, env.playback.sessions.size)
        assertNotNull("会话源必须还在", env.playback.last)
        assertTrue("界面状态仍应是播放中（后台播放继续）", vm.state.value.playing)
    }

    @Test
    fun 回前台时内核被系统停掉就续播_还在播就不重复play() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)
        val engine = env.last!!

        vm.onHostPause()
        // 息屏时系统销毁 Surface，内核可能自己停在那一帧
        engine.pause()
        tick(1)
        assertFalse("前提：模拟内核被系统停掉", engine.isPlaying)

        vm.onHostResume()
        tick(1)
        assertTrue("回前台发现内核被停了，要接着播", engine.isPlaying)

        // 还在播的时候再回一次前台：不许重复 play（会音画抖一下）
        val plays = engine.calls.count { it == "play" }
        vm.onHostPause()
        vm.onHostResume()
        assertEquals("本来就在播就不该再 play 一次", plays, engine.calls.count { it == "play" })
    }

    @Test
    fun 只有主动退出才停播_收会话_释放内核() = hostTest {
        val env = environment()
        val vm = player(env)
        settle()
        tick(1)
        val engine = env.last!!

        vm.stop()
        settle()

        assertFalse("返回键 / 左上角退出要停播", engine.isPlaying)
        assertTrue("退出要释放内核", engine.released)
        assertNull("退出要把后台会话收掉（不留空转的通知）", env.playback.last)
        assertFalse("界面也不该再显示播放中", vm.state.value.playing)
    }

    // ---------------------------------------------------------------- 源码护栏（方向③）

    /**
     * 播放页里**没有**第二个停播落点：生命周期观察者只认 ON_PAUSE / ON_RESUME。
     *
     * 为什么用源码级护栏：多挂一处 `ON_STOP -> stop()` 编译照样过、单测照样绿（JVM 里没有真生命周期），
     * 只有真机锁屏时才会暴露成"锁屏就停"。这条护栏把它挡在提交之前。
     */
    @Test
    fun 播放页对ON_STOP没有任何停播落点() {
        val screen = File("src/main/java/io/github/gua123/mediagate/feature/player/video/VideoPlayerScreen.kt")
        assertTrue("找不到播放页源码（测试工作目录应为 :feature:player-video 模块）：" + screen.absolutePath, screen.exists())
        val text = screen.readText()

        assertFalse("播放页不能出现 ON_STOP 分支——锁屏/HOME 走 ON_PAUSE，ON_STOP 一旦挂上停播就必然「锁屏即停」", text.contains("Lifecycle.Event.ON_STOP"))
        assertTrue("ON_PAUSE 必须落到 onHostPause（只标记后台，不停播）", text.contains("Lifecycle.Event.ON_PAUSE -> viewModel.onHostPause()"))
        assertTrue("ON_RESUME 必须落到 onHostResume（回前台重挂视频输出/续播）", text.contains("Lifecycle.Event.ON_RESUME -> viewModel.onHostResume()"))
        // 停播只能由 BackHandler / 关闭按钮那条路触发
        assertTrue("返回键必须走 stop()（退出 = 停播）", text.contains("BackHandler(enabled = true) { viewModel.stop(); onBack() }"))
    }

    @Test
    fun onHostPause只标记后台_不碰内核() {
        val text = File("src/main/java/io/github/gua123/mediagate/feature/player/video/VideoPlayerViewModel.kt").readText()
        val block = Regex("fun onHostPause\\(\\) \\{[\\s\\S]*?\\n    \\}").find(text)
        assertNotNull("找不到 onHostPause 的实现（签名变了？需要同步更新本护栏）", block)
        val body = block!!.value
        assertTrue("onHostPause 应该只置 hostPaused", body.contains("hostPaused = true"))
        assertFalse("onHostPause 不许停播：发现 pause(", body.contains(".pause("))
        assertFalse("onHostPause 不许停播：发现 stop(", body.contains("stop("))
        assertFalse("onHostPause 不许释放内核：发现 releaseEngine(", body.contains("releaseEngine("))
    }
}
