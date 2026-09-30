package io.github.gua123.mediagate.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.media.asr.AsrCandidate
import io.github.gua123.mediagate.media.asr.AsrFailureKind
import io.github.gua123.mediagate.media.asr.AsrItem
import io.github.gua123.mediagate.media.asr.AsrItemState
import io.github.gua123.mediagate.media.asr.AsrProgress
import io.github.gua123.mediagate.media.asr.AsrQueue
import io.github.gua123.mediagate.media.asr.AsrQueueSnapshot
import io.github.gua123.mediagate.media.asr.AsrQueueState

/**
 * 任务中心状态归约与入队计划的 JVM 单测（**M7-B / R19**）。
 *
 * 覆盖：选择/过滤（自动过滤非视频、跳过已有字幕）/入队计划/重排与重试的可用性/
 * 暂停恢复/总进度汇总/让路与模型提示。
 */
class TasksUiStateTest {

    private fun entry(
        name: String,
        path: String = "/m/$name",
        size: Long = 100L,
        isDirectory: Boolean = false,
        hasSubtitle: Boolean = false,
        selected: Boolean = false,
    ) = TaskEntryUi(path, name, size, isDirectory, hasSubtitle, selected)

    private fun item(id: Long, state: AsrItemState = AsrItemState.QUEUED, name: String = "v$id.mkv") =
        AsrItem(id = id, path = "/m/$name", name = name, state = state)

    private fun reduce(state: TasksUiState, vararg events: TasksEvent): TasksUiState =
        events.fold(state) { acc, event -> TasksReduce.reduce(acc, event) }

    // ---------------------------------------------------------------- 加载

    @Test
    fun loadingAndLoaded_toggleLoadingFlagAndSortEntries() {
        var state = TasksReduce.reduce(TasksUiState(), TasksEvent.Loading)
        assertTrue(state.loading)

        state = TasksReduce.reduce(
            state,
            TasksEvent.Loaded(
                dir = "/m",
                label = "电影",
                entries = listOf(entry("b.mkv"), entry("Shows", isDirectory = true), entry("a.mkv")),
            ),
        )
        assertFalse(state.loading)
        assertEquals("/m", state.currentDir)
        assertEquals("电影", state.dirLabel)
        // 目录在前，其余按名称
        assertEquals(listOf("Shows", "a.mkv", "b.mkv"), state.entries.map { it.name })
    }

    @Test
    fun loadFailed_keepsChineseMessageAndClearsEntries() {
        val state = reduce(
            TasksUiState(entries = listOf(entry("a.mkv"))),
            TasksEvent.LoadFailed("没有访问权限，请先在首页授权目录"),
        )
        assertEquals("没有访问权限，请先在首页授权目录", state.errorMessage)
        assertTrue(state.entries.isEmpty())
        assertFalse(state.loading)
    }

    // ---------------------------------------------------------------- 选择与过滤

    @Test
    fun selectionToggled_onlyWorksOnSelectableRows() {
        var state = TasksUiState(
            entries = listOf(entry("a.mkv"), entry("song.mp3"), entry("Shows", isDirectory = true)),
        )
        state = TasksReduce.reduce(state, TasksEvent.SelectionToggled("/m/a.mkv"))
        assertTrue(state.entries.first { it.name == "a.mkv" }.selected)
        state = TasksReduce.reduce(state, TasksEvent.SelectionToggled("/m/song.mp3"))
        assertFalse(state.entries.first { it.name == "song.mp3" }.selected)
        state = TasksReduce.reduce(state, TasksEvent.SelectionToggled("/m/Shows"))
        assertFalse(state.entries.first { it.name == "Shows" }.selected)
        assertEquals(1, state.selectedEntries.size)
    }

    @Test
    fun folderMode_allowsSelectingDirectories() {
        var state = TasksUiState(entries = listOf(entry("Shows", isDirectory = true)))
        state = TasksReduce.reduce(state, TasksEvent.SourceKindChanged(TaskSourceKind.FOLDER))
        state = TasksReduce.reduce(state, TasksEvent.SelectionToggled("/m/Shows"))
        assertTrue(state.entries.single().selected)
    }

    @Test
    fun selectionAll_selectsOnlyVideos() {
        val state = reduce(
            TasksUiState(
                entries = listOf(
                    entry("a.mkv"),
                    entry("b.mp4"),
                    entry("c.mp3"),
                    entry("Shows", isDirectory = true),
                ),
            ),
            TasksEvent.SelectionAll,
        )
        assertEquals(listOf("a.mkv", "b.mp4"), state.selectedEntries.map { it.name })
        assertEquals(2, state.videoCount)
        assertEquals(2, state.selectableCount)
    }

    @Test
    fun selectionCleared_clearsEverything() {
        val state = reduce(
            TasksUiState(entries = listOf(entry("a.mkv", selected = true), entry("b.mkv"))),
            TasksEvent.SelectionCleared,
        )
        assertTrue(state.selectedEntries.isEmpty())
    }

    @Test
    fun sourceKindChange_clearsSelectionToAvoidHiddenCheckedRows() {
        val state = reduce(
            TasksUiState(entries = listOf(entry("a.mkv", selected = true))),
            TasksEvent.SourceKindChanged(TaskSourceKind.FOLDER),
        )
        assertEquals(TaskSourceKind.FOLDER, state.sourceKind)
        assertTrue(state.selectedEntries.isEmpty())
    }

    @Test
    fun switches_updateTheirFlags() {
        var state = TasksReduce.reduce(TasksUiState(), TasksEvent.RecursiveChanged(true))
        assertTrue(state.recursive)
        state = TasksReduce.reduce(state, TasksEvent.SkipExistingChanged(false))
        assertFalse(state.skipExisting)
        assertTrue(TasksUiState().skipExisting)
    }

    @Test
    fun enterDir_resetsEntriesAndKeepsPath() {
        val state = reduce(
            TasksUiState(entries = listOf(entry("a.mkv"))),
            TasksEvent.EnterDir("/m/sub", "子目录"),
        )
        assertEquals("/m/sub", state.currentDir)
        assertEquals("子目录", state.dirLabel)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun selectable_rulesDependOnMode() {
        assertTrue(entry("a.mkv").selectable(TaskSourceKind.FILES))
        assertFalse(entry("a.mkv").selectable(TaskSourceKind.FILES).not())
        assertFalse(entry("a.mp3").selectable(TaskSourceKind.FILES))
        assertFalse(entry("dir", isDirectory = true).selectable(TaskSourceKind.FILES))
        assertTrue(entry("dir", isDirectory = true).selectable(TaskSourceKind.FOLDER))
        assertFalse(entry("empty.mkv", size = 0L).selectable(TaskSourceKind.FILES))
    }

    // ---------------------------------------------------------------- 队列投影

    @Test
    fun queueUpdated_projectsProgressCurrentFileAndSummary() {
        var queue = AsrQueue.enqueue(AsrQueueSnapshot(), listOf(item(1), item(2)))
        queue = AsrQueue.startNext(queue).queue
        queue = AsrQueue.onProgress(queue, 1L, recognizedMs = 30_000L, totalMs = 60_000L)
        val state = TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(queue))

        assertEquals(25, state.progressPercent)
        assertEquals("v1.mkv", state.currentFileName)
        assertTrue(state.summaryText.contains("共 2 项"))
        assertTrue(state.summaryText.contains("待处理 1"))
        assertEquals(AsrQueueState.RUNNING, state.queue.state)
        assertEquals("进行中", state.queueStateText)
        assertTrue(state.canPause)
        assertFalse(state.canResume)
        assertTrue(state.canCancelAll)
    }

    @Test
    fun queueUpdated_reportsFailuresAndRetryability() {
        var queue = AsrQueue.enqueue(AsrQueueSnapshot(), listOf(item(1), item(2), item(3)))
        queue = AsrQueue.fail(queue, 1L, AsrFailureKind.NO_WRITE_PERMISSION, "只读")
        queue = AsrQueue.succeed(queue, 2L, "/m/v2.srt")
        queue = AsrQueue.restore(queue, queue.items.map { if (it.id == 3L) it.copy(state = AsrItemState.INTERRUPTED) else it })
        val state = TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(queue))

        assertTrue(state.hasRetryable)
        assertTrue(state.summaryText.contains("成功 1"))
        assertTrue(state.summaryText.contains("失败 1"))
        assertEquals(AsrFailureKind.NO_WRITE_PERMISSION, state.queue.item(1L)!!.failure)
        assertFalse(state.queue.isFinished)
    }

    @Test
    fun emptyQueue_hasFriendlySummaryAndNoProgress() {
        val state = TasksUiState()
        assertEquals("还没有任务", state.summaryText)
        assertEquals(0, state.progressPercent)
        assertNull(state.currentFileName)
        assertFalse(state.hasRetryable)
        assertFalse(state.canPause)
        assertFalse(state.canCancelAll)
        assertFalse(state.canReorder)
        assertEquals("空闲", state.queueStateText)
    }

    @Test
    fun canReorder_needsMoreThanOneQueuedItem() {
        val one = AsrQueue.enqueue(AsrQueueSnapshot(), listOf(item(1)))
        assertFalse(TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(one)).canReorder)
        val two = AsrQueue.enqueue(AsrQueueSnapshot(), listOf(item(1), item(2)))
        assertTrue(TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(two)).canReorder)
    }

    @Test
    fun canResume_whenPausedOrIdleWithPending() {
        val pending = AsrQueue.enqueue(AsrQueueSnapshot(), listOf(item(1)))
        assertTrue(TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(pending)).canResume)
        val paused = AsrQueue.pause(pending)
        val state = TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(paused))
        assertTrue(state.canResume)
        assertFalse(state.canPause)
    }

    // ---------------------------------------------------------------- 让路与模型

    @Test
    fun yieldingChanged_updatesFlag() {
        val state = TasksReduce.reduce(TasksUiState(), TasksEvent.YieldingChanged(true))
        assertTrue(state.yielding)
    }

    @Test
    fun modelsChanged_drivesModelHint() {
        var state = TasksReduce.reduce(TasksUiState(), TasksEvent.ModelsChanged(listOf("small"), "small"))
        assertTrue(state.modelReady)
        assertNull(state.modelHint)

        state = TasksReduce.reduce(state, TasksEvent.ModelsChanged(listOf("tiny"), "small"))
        assertFalse(state.modelReady)
        assertTrue(state.modelHint!!.contains("small"))
        assertTrue(state.modelHint!!.contains("下载"))
    }

    @Test
    fun notice_canBeSetAndCleared() {
        var state = TasksReduce.reduce(TasksUiState(), TasksEvent.Notice("已入队 3 项"))
        assertEquals("已入队 3 项", state.notice)
        state = TasksReduce.reduce(state, TasksEvent.Notice(null))
        assertNull(state.notice)
    }

    // ---------------------------------------------------------------- 入队可用性

    @Test
    fun canEnqueue_needsSelectionAndInstalledModel() {
        val selected = TasksUiState(
            entries = listOf(entry("a.mkv", selected = true)),
            installedModelIds = listOf("small"),
            selectedModelId = "small",
        )
        assertTrue(selected.canEnqueue)

        assertFalse(selected.copy(entries = listOf(entry("a.mkv"))).canEnqueue)
        assertFalse(selected.copy(installedModelIds = emptyList()).canEnqueue)
        assertFalse(selected.copy(queue = AsrQueueSnapshot(state = AsrQueueState.STOPPED)).canEnqueue)
    }

    // ---------------------------------------------------------------- 入队计划

    @Test
    fun candidatesOf_dropsDirectories() {
        val candidates = TasksPlan.candidatesOf(
            listOf(
                entry("Shows", isDirectory = true),
                entry("a.mkv", hasSubtitle = true),
                entry("b.mp4"),
            ),
        )
        assertEquals(listOf("a.mkv", "b.mp4"), candidates.map { it.name })
        assertTrue(candidates.first().hasSubtitle)
    }

    @Test
    fun plan_filtersNonVideoAndSkipsExisting() {
        val resolved = listOf(
            AsrCandidate("/m/a.mkv", "a.mkv", 100L, hasSubtitle = true),
            AsrCandidate("/m/b.mkv", "b.mkv", 100L, hasSubtitle = false),
            AsrCandidate("/m/c.txt", "c.txt", 10L),
        )
        val planned = TasksPlan.plan(resolved, skipExisting = true)
        assertEquals(listOf("b.mkv"), planned.accepted.map { it.name })
        assertEquals(listOf("a.mkv"), planned.skipped.map { it.name })
        assertEquals(1, planned.filteredOut)

        val kept = TasksPlan.plan(resolved, skipExisting = false)
        assertEquals(listOf("a.mkv", "b.mkv"), kept.accepted.map { it.name })
    }

    @Test
    fun isVideo_matchesAsrSelectionWhitelist() {
        assertTrue(TasksPlan.isVideo("a.mkv"))
        assertTrue(TasksPlan.isVideo("b.ts"))
        assertFalse(TasksPlan.isVideo("c.mp3"))
        assertFalse(TasksPlan.isVideo("d.m3u8"))
    }

    @Test
    fun reduce_isPureAndDoesNotMutateInput() {
        val original = TasksUiState(entries = listOf(entry("a.mkv")))
        val next = TasksReduce.reduce(original, TasksEvent.SelectionToggled("/m/a.mkv"))
        assertFalse(original.entries.single().selected)
        assertTrue(next.entries.single().selected)
        assertFalse(original === next)
    }

    @Test
    fun sortEntries_keepsDirectoriesFirst() {
        val sorted = TasksReduce.sortEntries(
            listOf(entry("z.mkv"), entry("A", isDirectory = true), entry("b.mkv")),
        )
        assertEquals(listOf("A", "b.mkv", "z.mkv"), sorted.map { it.name })
    }

    @Test
    fun progressPercent_reflectsQueueTotalProgress() {
        var queue = AsrQueue.enqueue(AsrQueueSnapshot(), listOf(item(1), item(2)))
        queue = AsrQueue.succeed(queue, 1L, "/m/v1.srt")
        val state = TasksReduce.reduce(TasksUiState(), TasksEvent.QueueUpdated(queue))
        assertEquals(50, state.progressPercent)
        assertEquals(0.5f, state.queue.totalProgress, 0.001f)
        assertEquals(AsrProgress.EMPTY, state.queue.item(2L)!!.progress)
    }
}
