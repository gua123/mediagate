package io.github.gua123.mediagate.feature.asrmodel

import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.media.asr.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AsrModelViewModel] 的 JVM 单测（**R14** 模型管理界面）。
 *
 * 补齐的正是之前"后端齐备、界面全无"那块：档位列表、下载进度、取消保留断点、删除、选中。
 */
class AsrModelViewModelTest {

    @Test
    fun `列出全部档位并标记已安装与当前选中`() = runTest {
        val environment = FakeEnvironment(installed = mutableSetOf("small"), selected = "small")
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(3, state.items.size)
        assertEquals(listOf("tiny", "base", "small"), state.items.map { it.id })
        assertTrue(state.items.first { it.id == "small" }.installed)
        assertTrue(state.items.first { it.id == "small" }.selected)
        assertFalse(state.items.first { it.id == "tiny" }.installed)
        assertEquals(listOf("small"), state.installedIds)
    }

    @Test
    fun `下载会推进进度并在完成后标记为已安装`() = runTest {
        val environment = FakeEnvironment()
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.download("tiny")
        advanceUntilIdle()

        val item = viewModel.state.value.items.first { it.id == "tiny" }
        assertTrue("完成后应标记已安装", item.installed)
        assertFalse(item.busy)
        assertTrue("要给一句完成提示", viewModel.state.value.notice.orEmpty().contains("下载完成"))
        assertTrue("环境里也要真的装上", environment.installed.contains("tiny"))
    }

    @Test
    fun `下载时带上镜像前缀且进度会反映到界面`() = runTest {
        val environment = FakeEnvironment()
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.download("base")
        advanceUntilIdle()

        assertEquals(WhisperModel.DEFAULT_MIRROR, environment.lastMirror)
        assertTrue("进度回调至少被调用一次", environment.progressCalls > 0)
    }

    @Test
    fun `关掉镜像时直连官方`() = runTest {
        val environment = FakeEnvironment()
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.setMirrorEnabled(false)
        viewModel.download("tiny")
        advanceUntilIdle()

        assertEquals(null, environment.lastMirror)
    }

    @Test
    fun `下载失败时给中文原因`() = runTest {
        val environment = FakeEnvironment(failWith = IllegalStateException("下载不完整"))
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.download("tiny")
        advanceUntilIdle()

        val item = viewModel.state.value.items.first { it.id == "tiny" }
        assertFalse(item.installed)
        assertTrue("失败原因要展示：${item.failed}", item.failed.orEmpty().contains("下载不完整"))
    }

    @Test
    fun `取消下载保留断点并提示可继续`() = runTest {
        val environment = FakeEnvironment(cancelAfterFirstProgress = true)
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.download("small")
        advanceUntilIdle()

        val item = viewModel.state.value.items.first { it.id == "small" }
        assertFalse(item.busy)
        assertFalse("没下完不算已安装", item.installed)
        assertTrue("要说清断点保留：${item.failed}", item.failed.orEmpty().contains("保留"))
    }

    @Test
    fun `删除会清掉已安装状态并刷新占用`() = runTest {
        val environment = FakeEnvironment(installed = mutableSetOf("tiny"), usedBytes = 74L * 1024 * 1024)
        val viewModel = viewModel(environment)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.items.first { it.id == "tiny" }.installed)
        assertTrue(viewModel.state.value.usedMb > 0L)

        viewModel.delete("tiny")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.items.first { it.id == "tiny" }.installed)
        assertEquals(0L, viewModel.state.value.usedMb)
        assertTrue(viewModel.state.value.notice.orEmpty().contains("已删除"))
    }

    @Test
    fun `选中会落盘并更新界面标记`() = runTest {
        val environment = FakeEnvironment(installed = mutableSetOf("tiny", "base"), selected = "tiny")
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.select("base")
        advanceUntilIdle()

        assertEquals("base", environment.selectedId.value)
        assertTrue(viewModel.state.value.items.first { it.id == "base" }.selected)
        assertFalse(viewModel.state.value.items.first { it.id == "tiny" }.selected)
    }

    @Test
    fun `同时只允许一个下载任务`() = runTest {
        // 让第一个下载挂起（模拟"还在传"），第二次点下载必须被忽略
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val environment = FakeEnvironment(holdDownload = hold)
        val viewModel = viewModel(environment)
        advanceUntilIdle()

        viewModel.download("tiny")
        viewModel.download("base")
        hold.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, environment.downloadCount)
        assertTrue("tiny 应下完", environment.installed.contains("tiny"))
        assertFalse("base 不该被启动", environment.installed.contains("base"))
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(environment: AsrModelEnvironment) =
        AsrModelViewModel(
            environment = environment,
            io = Dispatchers.Unconfined,
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )
}

/** 假宿主：内存里维护"已安装集合"，可配置失败 / 取消 / 进度次数。 */
private class FakeEnvironment(
    val installed: MutableSet<String> = mutableSetOf(),
    selected: String = WhisperModel.DEFAULT.id,
    private val usedBytes: Long = 0L,
    private val failWith: Throwable? = null,
    private val cancelAfterFirstProgress: Boolean = false,
    private val holdDownload: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : AsrModelEnvironment {

    private val selectedFlow = MutableStateFlow(selected)
    var lastMirror: String? = "未设置"
    var progressCalls = 0
    var downloadCount = 0

    override fun models(): List<WhisperModel> = WhisperModel.ALL

    override fun installedIds(): List<String> = WhisperModel.ALL.map { it.id }.filter { it in installed }

    override fun usedBytes(): Long = if (installed.isEmpty()) 0L else usedBytes

    override val selectedId: StateFlow<String> get() = selectedFlow

    override suspend fun select(id: String) {
        selectedFlow.value = id
    }

    override suspend fun delete(model: WhisperModel): Boolean = installed.remove(model.id)

    override suspend fun download(model: WhisperModel, mirror: String?, onProgress: (DownloadProgress) -> Unit) {
        downloadCount++
        lastMirror = mirror
        onProgress(DownloadProgress(model.sizeBytes / 4, model.sizeBytes))
        progressCalls++
        holdDownload?.await()
        if (cancelAfterFirstProgress) throw kotlinx.coroutines.CancellationException("测试取消")
        failWith?.let { throw it }
        installed.add(model.id)
        onProgress(DownloadProgress(model.sizeBytes, model.sizeBytes))
    }

    override val defaultMirror: String? = WhisperModel.DEFAULT_MIRROR
}
