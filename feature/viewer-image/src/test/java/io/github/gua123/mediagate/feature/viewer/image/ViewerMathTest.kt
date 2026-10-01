package io.github.gua123.mediagate.feature.viewer.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException

/**
 * [ViewerMath] / [ViewerErrors] / [ViewerUiState.reduce] / [ViewerRoutes] 的 JVM 单测（**M1-F**，R1）。
 *
 * 覆盖：翻页索引边界、缩放范围钳制、位移钳制、适配尺寸、采样率、预取 LRU、路由拼接、错误分类、
 * UiState 归约。全部是纯逻辑，不依赖 Android 与真实文件系统。
 */
class ViewerMathTest {

    @Test
    fun `只保留图片并按名称排序`() {
        val entries = listOf(
            photoEntry("Pics/b.JPG"),
            photoEntry("Pics/a.png"),
            videoEntry("Pics/movie.mp4"),
            videoEntry("Pics/song.mp3"),
            videoEntry("Pics/sub.srt"),
            photoEntry("Pics/c.webp"),
        )
        assertEquals(listOf("a.png", "b.JPG", "c.webp"), ViewerMath.imageEntries(entries).map { it.name })
        assertTrue(ViewerMath.imageEntries(emptyList()).isEmpty())
    }

    @Test
    fun `翻页下标钳在首尾之间`() {
        assertEquals(0, ViewerMath.clampIndex(-5, 3))
        assertEquals(2, ViewerMath.clampIndex(9, 3))
        assertEquals(1, ViewerMath.clampIndex(1, 3))
        assertEquals(0, ViewerMath.clampIndex(2, 0))
        assertEquals(1, ViewerMath.nextIndex(0, 3))
        assertEquals(2, ViewerMath.nextIndex(2, 3))
        assertEquals(0, ViewerMath.prevIndex(0, 3))
        assertEquals("上一张是 1 而不是 0", 1, ViewerMath.prevIndex(2, 3))
    }

    @Test
    fun `首尾不能再翻页`() {
        assertFalse(ViewerMath.canGoPrev(0))
        assertTrue(ViewerMath.canGoPrev(1))
        assertFalse("只有一张时不能往后翻", ViewerMath.canGoNext(0, 1))
        assertFalse("最后一张不能往后翻", ViewerMath.canGoNext(2, 3))
        assertTrue(ViewerMath.canGoNext(1, 3))
        assertFalse("空列表不能往后翻", ViewerMath.canGoNext(0, 0))
    }

    @Test
    fun `定位当前图片找不到返回负一`() {
        val images = listOf(photoEntry("Pics/a.jpg"), photoEntry("Pics/b.jpg"))
        assertEquals(1, ViewerMath.indexOfPath("Pics/b.jpg", images))
        assertEquals(-1, ViewerMath.indexOfPath("Pics/zz.jpg", images))
    }

    @Test
    fun `文件名取最后一段`() {
        assertEquals("a.jpg", ViewerMath.fileNameOf("Pics/2026/a.jpg"))
        assertEquals("a.jpg", ViewerMath.fileNameOf("a.jpg"))
        assertEquals("", ViewerMath.fileNameOf(""))
    }

    @Test
    fun `缩放钳在 1 到 6 倍之间`() {
        assertEquals(1f, ViewerMath.clampScale(0.2f), 0.0001f)
        assertEquals(1f, ViewerMath.clampScale(-3f), 0.0001f)
        assertEquals(3f, ViewerMath.clampScale(3f), 0.0001f)
        assertEquals(6f, ViewerMath.clampScale(99f), 0.0001f)
        assertEquals("NaN 退化为最小缩放", 1f, ViewerMath.clampScale(Float.NaN), 0.0001f)
        assertTrue(ViewerMath.DOUBLE_TAP_SCALE in ViewerMath.MIN_SCALE..ViewerMath.MAX_SCALE)
    }

    @Test
    fun `位移钳在内容与视口的差额内`() {
        // 内容 1000、视口 400：左右各能移动 (1000-400)/2 = 300
        assertEquals(300f, ViewerMath.maxOffset(1000f, 400f), 0.0001f)
        assertEquals(0f, ViewerMath.maxOffset(300f, 400f), 0.0001f)
        assertEquals(300f, ViewerMath.clampOffset(5000f, 1000f, 400f), 0.0001f)
        assertEquals(-300f, ViewerMath.clampOffset(-5000f, 1000f, 400f), 0.0001f)
        assertEquals("内容比视口小只能居中", 0f, ViewerMath.clampOffset(120f, 300f, 400f), 0.0001f)
        assertEquals(0f, ViewerMath.clampOffset(Float.NaN, 1000f, 400f), 0.0001f)
    }

    @Test
    fun `适配尺寸按短边等比缩放`() {
        val wide = ViewerMath.fitSize(4000f, 3000f, 1080f, 1920f)
        assertEquals(1080f, wide.width, 0.01f)
        assertEquals(810f, wide.height, 0.01f)

        val tall = ViewerMath.fitSize(3000f, 4000f, 1080f, 1920f)
        assertEquals(1080f, tall.width, 0.01f)
        assertEquals(1440f, tall.height, 0.01f)

        assertEquals(SizeF(0f, 0f), ViewerMath.fitSize(0f, 0f, 1080f, 1920f))
        assertEquals("视口未知时按原尺寸，不产生 NaN", SizeF(4000f, 3000f), ViewerMath.fitSize(4000f, 3000f, 0f, 0f))
    }

    @Test
    fun `只有未放大时的横向滑动才翻页`() {
        assertTrue(ViewerMath.shouldTurnPage(1f, -200f))
        assertTrue(ViewerMath.shouldTurnPage(1f, 200f))
        assertFalse("位移不够不翻页", ViewerMath.shouldTurnPage(1f, -50f))
        assertFalse("放大后横向拖动是平移画面", ViewerMath.shouldTurnPage(2f, -500f))
    }

    @Test
    fun `采样率按屏幕尺寸约束宽高`() {
        // 只看宽能到 4（8000/4=2000>=1080），但高 6000/4=1500 已经小于 1920，所以只能取 2
        assertEquals("8000x6000 放进 1080x1920", 2, ViewerMath.sampleSize(8000, 6000, 1080, 1920))
        assertEquals("同样的大图放进 1080x1080", 4, ViewerMath.sampleSize(8000, 6000, 1080, 1080))
        assertEquals("小图不采样", 1, ViewerMath.sampleSize(800, 600, 1080, 1920))
        assertEquals("尺寸未知不采样", 1, ViewerMath.sampleSize(0, 0, 1080, 1920))
    }

    @Test
    fun `预取缓存按条数做 LRU 淘汰`() {
        val cache = ViewerLruCache<String>(capacity = 3)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals(3, cache.size())

        // 命中 a 让它变成最近使用，再塞 d 时被淘汰的应该是 b
        assertEquals("A", cache.get("a"))
        cache.put("d", "D")
        assertEquals(3, cache.size())
        assertFalse(cache.contains("b"))
        assertTrue(cache.contains("a"))
        assertTrue(cache.contains("d"))
        assertNull(cache.get("b"))

        val none = ViewerLruCache<String>(capacity = 0)
        none.put("x", "X")
        assertEquals(0, none.size())

        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `路由拼接与解析`() {
        assertEquals("viewer-image", ViewerRoutes.route(""))
        assertEquals("viewer-image?path=Pics%2Fa.jpg", ViewerRoutes.route("Pics/a.jpg"))
        assertEquals("viewer-image?path=%E4%B8%AD%E6%96%87%2Fb.png", ViewerRoutes.route("中文/b.png"))
        assertEquals("Pics/a.jpg", ViewerRoutes.pathOf("Pics/a.jpg"))
        assertEquals("", ViewerRoutes.pathOf(null))
    }

    @Test
    fun `错误分类覆盖 StorageException 各子类`() {
        assertEquals(ViewerErrorKind.ACCESS_DENIED, ViewerErrors.classify(StorageException.AccessDenied()))
        assertEquals(ViewerErrorKind.NOT_FOUND, ViewerErrors.classify(StorageException.NotFound()))
        assertEquals(ViewerErrorKind.NOT_SUPPORTED, ViewerErrors.classify(StorageException.NotSupported()))
        assertEquals(ViewerErrorKind.NETWORK, ViewerErrors.classify(StorageException.Network()))
        assertEquals(ViewerErrorKind.AUTH, ViewerErrors.classify(StorageException.Auth()))
        assertEquals(ViewerErrorKind.UNKNOWN, ViewerErrors.classify(StorageException.Unknown()))
        assertEquals(ViewerErrorKind.UNKNOWN, ViewerErrors.classify(IllegalStateException("boom")))
        assertEquals(
            "查看器自己的「图片过大」也要能分类",
            ViewerErrorKind.TOO_LARGE,
            ViewerErrors.classify(ImageTooLargeException(sizeBytes = 1L)),
        )
    }

    @Test
    fun `UiState 归约覆盖加载成功与失败`() {
        var state = ViewerUiState()
        state = state.reduce(ViewerEvent.LoadStarted("Pics/b.jpg", "b.jpg"))
        assertEquals(ViewerStatus.LOADING, state.status)
        assertEquals("b.jpg", state.name)
        assertNull(state.image)

        val images = listOf(photoEntry("Pics/a.jpg"), photoEntry("Pics/b.jpg"))
        state = state.reduce(ViewerEvent.SiblingsLoaded(images, 1))
        assertEquals(2, state.count)
        assertEquals(2, state.position)
        assertTrue(state.canGoPrev)
        assertFalse(state.canGoNext)
        assertTrue(state.hasPages)

        state = state.reduce(ViewerEvent.LoadSucceeded(FakeDecodedImage(width = 10, height = 20)))
        assertEquals(ViewerStatus.READY, state.status)
        assertEquals(20, state.image?.height)

        state = state.reduce(ViewerEvent.LoadFailed(ViewerErrorKind.DECODE_FAILED, "坏文件"))
        assertEquals(ViewerStatus.ERROR, state.status)
        assertEquals(ViewerErrorKind.DECODE_FAILED, state.errorKind)
        assertEquals("坏文件", state.errorDetail)
        assertNull(state.image)
    }

    @Test
    fun `UiState 列表为空时按单张算`() {
        val single = ViewerUiState().reduce(ViewerEvent.SiblingsLoaded(emptyList(), 0))
        assertEquals("不能显示「第 1 / 0 张」", 1, single.count)
        assertEquals(1, single.position)
        assertFalse(single.hasPages)
    }

    @Test
    fun `翻页事件会重置缩放态相关的加载状态`() {
        val images = listOf(photoEntry("Pics/a.jpg"), photoEntry("Pics/b.jpg"), photoEntry("Pics/c.jpg"))
        var state = ViewerUiState()
            .reduce(ViewerEvent.SiblingsLoaded(images, 0))
            .reduce(ViewerEvent.LoadSucceeded(FakeDecodedImage()))
        assertEquals(ViewerStatus.READY, state.status)

        state = state.reduce(ViewerEvent.PageShown(images, 2, "Pics/c.jpg", "c.jpg"))
        assertEquals(ViewerStatus.LOADING, state.status)
        assertEquals("c.jpg", state.name)
        assertEquals(3, state.position)
        assertNull("新的一页必须先清掉上一张，避免闪回", state.image)
        assertFalse(state.canGoNext)
        assertTrue(state.canGoPrev)
    }
}
