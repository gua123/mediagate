package io.github.gua123.mediagate.feature.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.thumbnail.FrameExtractor
import io.github.gua123.mediagate.media.thumbnail.ThumbnailCache
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.File
import java.io.InputStream

/**
 * [BrowserViewModel] 的 JVM 单测（R2 列目录 / R12 双模式）。
 *
 * 用假后端驱动真实的 ViewModel：验证「未选根目录 → 引导态」「选好根目录 → 列目录」
 * 「进子目录 → 换路径」「后端异常 → 分类错误态」「换根目录 → 回到根路径」，
 * 全部跑在测试调度器上，不依赖 Android 与真实文件系统。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `未选根目录时为引导态而不是错误`() = runTest(dispatcher) {
        val viewModel = BrowserViewModel(FakeEnvironment(), initialPath = "", io = dispatcher)
        advanceUntilIdle()

        assertEquals(BrowserStatus.NO_ROOT, viewModel.state.value.status)
        assertEquals(null, viewModel.state.value.errorKind)
    }

    @Test
    fun `选好根目录后列出根目录并进入内容态`() = runTest(dispatcher) {
        val backend = FakeBackend(
            mapOf(
                "" to listOf(dir("Movies"), file("a.mp4"), file("b.mp3")),
                "Movies" to listOf(file("Movies/c.jpg")),
            ),
        )
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()

        environment.root.value = root(backend)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(BrowserStatus.CONTENT, state.status)
        assertEquals(listOf("Movies", "a.mp4", "b.mp3"), state.visibleEntries.map { it.name })
        assertEquals(listOf(""), backend.listed)
        assertEquals("内部存储", state.rootLabel)
    }

    @Test
    fun `进入子目录继续 list 并能返回上级`() = runTest(dispatcher) {
        val backend = FakeBackend(
            mapOf(
                "" to listOf(dir("Movies")),
                "Movies" to listOf(dir("Movies/2026", path = "Movies/2026")),
                "Movies/2026" to listOf(file("Movies/2026/a.mp4")),
            ),
        )
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()
        environment.root.value = root(backend)
        advanceUntilIdle()

        viewModel.open("Movies/2026")
        advanceUntilIdle()
        assertEquals("Movies/2026", viewModel.state.value.path)
        assertEquals(listOf("a.mp4"), viewModel.state.value.visibleEntries.map { it.name })
        assertEquals(listOf("", "Movies/2026"), backend.listed)

        viewModel.up()
        advanceUntilIdle()
        assertEquals("Movies", viewModel.state.value.path)
        assertEquals(listOf("2026"), viewModel.state.value.visibleEntries.map { it.name })

        viewModel.up()
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.path)

        // 已在根目录：up() 不应再发请求
        val before = backend.listed.size
        viewModel.up()
        advanceUntilIdle()
        assertEquals(before, backend.listed.size)
        assertTrue(viewModel.state.value.canGoUp.not())
    }

    @Test
    fun `无权限异常归类为去授权提示`() = runTest(dispatcher) {
        val backend = FakeBackend(mapOf("" to emptyList()))
        backend.failures["Secret"] = StorageException.AccessDenied("SAF 未授权")
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()
        environment.root.value = root(backend)
        advanceUntilIdle()

        viewModel.open("Secret")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(BrowserStatus.ERROR, state.status)
        assertEquals(BrowserErrorKind.ACCESS_DENIED, state.errorKind)
        assertEquals("SAF 未授权", state.errorDetail)
    }

    @Test
    fun `认证失败单独归类（界面据此给"去哪儿改密码"的指引）`() = runTest(dispatcher) {
        val backend = FakeBackend(mapOf("" to emptyList()))
        backend.failures["Locked"] = StorageException.Auth("SFTP 认证失败（账号或密码错误）：hml@nas.local:2222")
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()
        environment.root.value = root(backend)
        advanceUntilIdle()

        viewModel.open("Locked")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(BrowserStatus.ERROR, state.status)
        assertEquals(
            "认证失败不能混进 UNKNOWN——那样界面只会说「加载失败，请重试」，用户不知道该改密码",
            BrowserErrorKind.AUTH_FAILED,
            state.errorKind,
        )
        assertTrue(state.errorDetail.orEmpty().contains("认证失败"))
    }

    @Test
    fun `目录不存在归类为不存在错误`() = runTest(dispatcher) {
        val backend = FakeBackend(mapOf("" to emptyList()))
        backend.failures["Gone"] = StorageException.NotFound("目录不存在：Gone")
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()
        environment.root.value = root(backend)
        advanceUntilIdle()

        viewModel.open("Gone")
        advanceUntilIdle()

        assertEquals(BrowserErrorKind.NOT_FOUND, viewModel.state.value.errorKind)
    }

    @Test
    fun `换根目录会回到根路径并重新列目录`() = runTest(dispatcher) {
        val first = FakeBackend(mapOf("" to listOf(dir("Movies")), "Movies" to emptyList()))
        val second = FakeBackend(mapOf("" to listOf(file("x.jpg"))))
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()

        environment.root.value = root(first)
        advanceUntilIdle()
        viewModel.open("Movies")
        advanceUntilIdle()
        assertEquals("Movies", viewModel.state.value.path)

        environment.root.value = root(second, mode = RootModeKind.SAF, label = "已授权目录")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("", state.path)
        assertEquals("已授权目录", state.rootLabel)
        assertEquals(listOf("x.jpg"), state.visibleEntries.map { it.name })
    }

    @Test
    fun `带类型过滤进入时只展示该类媒体且目录保留`() = runTest(dispatcher) {
        val backend = FakeBackend(
            mapOf("" to listOf(dir("Movies"), file("a.mp4"), file("b.mp3"), file("c.jpg"))),
        )
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(
            environment = environment,
            initialFilter = MediaKind.VIDEO,
            io = dispatcher,
        )
        advanceUntilIdle()
        environment.root.value = root(backend)
        advanceUntilIdle()

        assertEquals(listOf("Movies", "a.mp4"), viewModel.state.value.visibleEntries.map { it.name })

        viewModel.clearFilter()
        assertEquals(4, viewModel.state.value.visibleEntries.size)
    }

    @Test
    fun `路由参数解析`() {
        assertEquals("browser", BrowserRoutes.route())
        assertEquals("browser?kind=VIDEO", BrowserRoutes.route(kind = MediaKind.VIDEO))
        assertEquals("browser?path=Movies%2F2026", BrowserRoutes.route(path = "Movies/2026"))
        assertEquals(
            "browser?path=a%2Fb&kind=IMAGE",
            BrowserRoutes.route(kind = MediaKind.IMAGE, path = "a/b"),
        )
        assertEquals(MediaKind.AUDIO, BrowserRoutes.kindOf("AUDIO"))
        assertEquals(null, BrowserRoutes.kindOf("不存在"))
        assertEquals(null, BrowserRoutes.kindOf(null))
        assertEquals("", BrowserRoutes.pathOf(null))
    }

    // ------------------------------------------------------------ 测试替身

    private fun root(
        backend: StorageBackend,
        mode: RootModeKind = RootModeKind.ALL_FILES,
        label: String = "内部存储",
    ) = BrowserRootState(
        mode = mode,
        label = label,
        displayPath = "/storage/emulated/0",
        backend = backend,
    )

    private fun dir(name: String, path: String = name) = RemoteEntry(
        name = name.substringAfterLast('/'),
        path = path,
        isDirectory = true,
        size = -1L,
        mtime = 0L,
    )

    private fun file(path: String, size: Long = 1024L, mtime: Long = 0L) = RemoteEntry(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        size = size,
        mtime = mtime,
    )

    @Test
    fun `改排序后列表按新排法重排（不打远端）`() = runTest(dispatcher) {
        val backend = FakeBackend(
            mapOf(
                "" to listOf(
                    file("/dir/z.mp4", size = 900L),
                    file("/dir/a.mp4", size = 100L),
                ),
            ),
        )
        val environment = FakeEnvironment()
        val viewModel = BrowserViewModel(environment, io = dispatcher)
        advanceUntilIdle()
        environment.root.value = root(backend)
        advanceUntilIdle()

        val listedBefore = backend.listed.size
        assertEquals(listOf("a.mp4", "z.mp4"), viewModel.state.value.visibleEntries.map { it.name })

        viewModel.setSort(EntrySort(mode = EntrySortMode.SIZE, ascending = false))
        advanceUntilIdle()

        assertEquals("降序：大的在前", listOf("z.mp4", "a.mp4"), viewModel.state.value.visibleEntries.map { it.name })
        assertEquals("重排只动内存，不该再列一次目录", listedBefore, backend.listed.size)
        assertEquals(EntrySortMode.SIZE, environment.sort.value.mode)
    }

    /** 假环境：根目录可手动切换（模拟用户在首页换目录）。 */
    private class FakeEnvironment : BrowserEnvironment {

        override val root = MutableStateFlow<BrowserRootState?>(null)

        /** 排序（2026-10-03）：假环境里可手动改，验证"改排序后列表跟着变"。 */
        override val sort = MutableStateFlow(EntrySort())

        override suspend fun setSort(sort: EntrySort) {
            this.sort.value = sort
        }

        override val thumbnails: ThumbnailRepository = ThumbnailRepository(
            cache = ThumbnailCache(rootDir = File(System.getProperty("java.io.tmpdir"), "mediagate-test-thumbs")),
            primary = object : FrameExtractor {
                override suspend fun extract(
                    source: RandomAccessSource,
                    mimeHint: String?,
                    positionMs: Long,
                    targetWidth: Int,
                ): ByteArray? = null
            },
        )
    }

    /** 假后端：按目录返回预设列表，可注入异常。 */
    private class FakeBackend(
        private val children: Map<String, List<RemoteEntry>>,
    ) : StorageBackend {

        val listed = mutableListOf<String>()
        val failures = mutableMapOf<String, Throwable>()

        override val id: String = "fake:test"

        override val caps: Caps = Caps(randomAccess = true, writable = false)

        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> {
            listed += dir
            failures[dir]?.let { throw it }
            return children[dir].orEmpty()
        }

        override suspend fun stat(path: String): RemoteEntry = throw StorageException.NotFound(path)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            throw StorageException.NotSupported(path)

        override suspend fun write(path: String, data: InputStream): Unit =
            throw StorageException.AccessDenied(path)

        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

        override fun close() = Unit
    }
}
