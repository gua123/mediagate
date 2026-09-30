package io.github.gua123.mediagate.feature.viewer.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * [ImageViewerViewModel] 的 JVM 单测（**M1-F**，R1）。
 *
 * 用假环境 + 假解码器驱动真实的 ViewModel：验证「列同目录图片 → 定位当前图 → 按屏解码 →
 * 当前页 ±1 预取 → 翻页（含首尾边界）→ 失败分类（无权限 / 不存在 / 过大 / 解不出）→ 重试」。
 * 全部跑在测试调度器上，不依赖 Android 与真实文件系统。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        environment: FakeImageViewerEnvironment,
        path: String,
        decoder: ImageViewerDecoder = FakeImageViewerDecoder(),
    ) = ImageViewerViewModel(
        environment = environment,
        initialPath = path,
        viewportWidthPx = 1080,
        viewportHeightPx = 1920,
        decoder = decoder,
        io = dispatcher,
    )

    @Test
    fun `进入后列出同目录图片并定位当前图片`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(
            mapOf(
                "Pics" to listOf(
                    photoEntry("Pics/a.jpg"),
                    videoEntry("Pics/movie.mp4"),
                    photoEntry("Pics/b.png"),
                ),
            ),
        )
        val vm = viewModel(env, "Pics/b.png")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ViewerStatus.READY, state.status)
        assertEquals("视频不参与翻页", 2, state.count)
        assertEquals(2, state.position)
        assertEquals("b.png", state.name)
        assertEquals(listOf("Pics/a.jpg", "Pics/b.png"), state.siblings.map { it.path })
        assertEquals("当前图片必须先读", "Pics/b.png", env.opened.first())
    }

    @Test
    fun `同目录列表按名称排序后再定位`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(
            mapOf(
                "Pics" to listOf(
                    photoEntry("Pics/c.jpg"),
                    photoEntry("Pics/A.jpg"),
                    photoEntry("Pics/b.jpg"),
                ),
            ),
        )
        val vm = viewModel(env, "Pics/b.jpg")
        advanceUntilIdle()

        assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), vm.state.value.siblings.map { it.name })
        assertEquals(2, vm.state.value.position)
    }

    @Test
    fun `按屏幕尺寸解码`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(mapOf("Pics" to listOf(photoEntry("Pics/a.jpg"))))
        val decoder = FakeImageViewerDecoder()
        val vm = viewModel(env, "Pics/a.jpg", decoder)
        advanceUntilIdle()

        assertTrue("解码请求必须带屏幕尺寸（大图不许 OOM）", decoder.requests.isNotEmpty())
        assertTrue(decoder.requests.all { it == (1080 to 1920) })
    }

    @Test
    fun `预取当前页前后各一张`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(
            mapOf(
                "Pics" to listOf(
                    photoEntry("Pics/a.jpg"),
                    photoEntry("Pics/b.jpg"),
                    photoEntry("Pics/c.jpg"),
                    photoEntry("Pics/d.jpg"),
                ),
            ),
        )
        val vm = viewModel(env, "Pics/c.jpg")
        advanceUntilIdle()

        assertEquals(ViewerStatus.READY, vm.state.value.status)
        assertEquals(
            setOf("Pics/b.jpg", "Pics/c.jpg", "Pics/d.jpg"),
            env.opened.toSet(),
        )
        assertFalse("只预取 ±1，不碰更远的", env.opened.contains("Pics/a.jpg"))
    }

    @Test
    fun `翻到已预取的页不再读远端`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(
            mapOf(
                "Pics" to listOf(
                    photoEntry("Pics/a.jpg"),
                    photoEntry("Pics/b.jpg"),
                    photoEntry("Pics/c.jpg"),
                ),
            ),
        )
        val vm = viewModel(env, "Pics/b.jpg")
        advanceUntilIdle()
        val openedAfterPrefetch = env.opened.size
        assertTrue(openedAfterPrefetch >= 3)

        vm.prev()
        advanceUntilIdle()

        assertEquals(ViewerStatus.READY, vm.state.value.status)
        assertEquals(1, vm.state.value.position)
        assertEquals("a.jpg", vm.state.value.name)
        assertEquals("预取过的页不该再读一次远端", openedAfterPrefetch, env.opened.size)
    }

    @Test
    fun `首尾翻页不越界`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(
            mapOf(
                "Pics" to listOf(
                    photoEntry("Pics/a.jpg"),
                    photoEntry("Pics/b.jpg"),
                    photoEntry("Pics/c.jpg"),
                ),
            ),
        )
        val vm = viewModel(env, "Pics/c.jpg")
        advanceUntilIdle()

        // 已在最后一张：再往后翻应停在原地
        vm.next()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.position)
        assertEquals("Pics/c.jpg", vm.state.value.path)

        vm.prev()
        advanceUntilIdle()
        vm.prev()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.position)
        assertEquals("Pics/a.jpg", vm.state.value.path)

        // 已在第一张：再往前翻也停在原地
        vm.prev()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.position)
        assertEquals("Pics/a.jpg", vm.state.value.path)
    }

    @Test
    fun `读取失败按分类给原因且可重试`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(mapOf("Pics" to listOf(photoEntry("Pics/a.jpg"))))
        env.failOpen["Pics/a.jpg"] = StorageException.AccessDenied("SAF 未授权")
        val vm = viewModel(env, "Pics/a.jpg")
        advanceUntilIdle()

        assertEquals(ViewerStatus.ERROR, vm.state.value.status)
        assertEquals(ViewerErrorKind.ACCESS_DENIED, vm.state.value.errorKind)
        assertEquals("SAF 未授权", vm.state.value.errorDetail)
        assertNull(vm.state.value.image)

        env.failOpen.clear()
        vm.retry()
        advanceUntilIdle()
        assertEquals(ViewerStatus.READY, vm.state.value.status)
        assertNull(vm.state.value.errorKind)
    }

    @Test
    fun `列目录失败归类为不存在`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment()
        env.failSiblings["Pics"] = StorageException.NotFound("目录不存在")
        val vm = viewModel(env, "Pics/a.jpg")
        advanceUntilIdle()

        assertEquals(ViewerStatus.ERROR, vm.state.value.status)
        assertEquals(ViewerErrorKind.NOT_FOUND, vm.state.value.errorKind)
        assertEquals("目录不存在", vm.state.value.errorDetail)
    }

    @Test
    fun `解码失败提示无法解码`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(mapOf("Pics" to listOf(photoEntry("Pics/a.jpg"))))
        val vm = viewModel(env, "Pics/a.jpg", FakeImageViewerDecoder(image = null))
        advanceUntilIdle()

        assertEquals(ViewerStatus.ERROR, vm.state.value.status)
        assertEquals(ViewerErrorKind.DECODE_FAILED, vm.state.value.errorKind)
    }

    @Test
    fun `超过体积上限时不读字节`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(
            mapOf(
                "Pics" to listOf(
                    photoEntry("Pics/a.jpg"),
                    photoEntry("Pics/big.jpg", size = 100L * 1024 * 1024),
                ),
            ),
        )
        val vm = viewModel(env, "Pics/big.jpg")
        advanceUntilIdle()

        assertEquals(ViewerStatus.ERROR, vm.state.value.status)
        assertEquals(ViewerErrorKind.TOO_LARGE, vm.state.value.errorKind)
        assertTrue("超限的图片不该被读进内存", env.opened.isEmpty())
    }

    @Test
    fun `目录里没有图片时按单张显示`() = runTest(dispatcher) {
        val env = FakeImageViewerEnvironment(mapOf("Pics" to listOf(videoEntry("Pics/movie.mp4"))))
        val vm = viewModel(env, "Pics/movie.mp4")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ViewerStatus.READY, state.status)
        assertEquals(1, state.count)
        assertFalse(state.hasPages)
        assertEquals("movie.mp4", state.name)
        assertEquals(listOf("Pics/movie.mp4"), env.opened)
    }
}
