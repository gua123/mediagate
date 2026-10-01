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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleStyle

/**
 * [VideoPlayerViewModel] 的字幕行为（R14：自动匹配 / 手动选轨 / 开关 / 样式 / ±0.5 s 微调 / 写回）。
 *
 * 与 [VideoPlayerViewModelTest] 分开成文件：那边 48 个用例覆盖 R4/R9/R10/R18，这边只测 R14，
 * 两边共用 [VideoTestDoubles] 里的假内核 / 假偏好 / 假字幕宿主。
 *
 * 时间控制同样只用 runCurrent/advanceTimeBy（播放页有永不结束的采样循环，advanceUntilIdle 会卡死）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerSubtitleTest {

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

    // ------------------------------------------------------------------ 自动匹配与手动选轨

    @Test
    fun autoLoadsTopCandidateWhenSubtitlesWereEnabled() = playerTest {
        val host = FakeSubtitleHost(
            listOf(autoCandidate("Movies/b.zh.srt"), autoCandidate("Movies/b.eng.srt", "eng", "英语")),
        )
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(listOf("Movies"), host.discovered)
        assertEquals(listOf("Movies/b.zh.srt"), host.loaded)
        assertEquals(3, vm.state.value.subtitleCues.size)
        // 自动匹配到的候选带语言标签，界面显示「中文（简体） · SRT」比文件名更好认
        assertEquals("中文（简体） · SRT", vm.state.value.subtitleLabel)
        assertEquals(SubtitleStatus.READY, vm.state.value.subtitleStatus)
        assertTrue(vm.state.value.subtitleEnabled)
    }

    @Test
    fun doesNotTouchSubtitlesWhenDisabled() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = false))

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertTrue(host.discovered.isEmpty())
        assertTrue(host.loaded.isEmpty())
        assertTrue(vm.state.value.subtitleCues.isEmpty())
        assertEquals(SubtitleStatus.OFF, vm.state.value.subtitleStatus)
    }

    @Test
    fun openingPanelDiscoversAndAutoLoadsFirstCandidate() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val prefs = FakeVideoPreferences(subtitleEnabled = false)
        val env = environment(host, prefs)
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.openSubtitlePanel()
        settle()

        assertEquals(listOf("Movies"), host.discovered)
        assertEquals(listOf("Movies/b.zh.srt"), host.loaded)
        assertEquals(listOf(true), prefs.subtitleEnabledWrites)
        assertEquals(SubtitleStatus.READY, vm.state.value.subtitleStatus)
    }

    @Test
    fun discoveryShowsLoadingBeforeCandidatesArrive() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = false))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.openSubtitlePanel()
        // 远端列目录可能很慢：候选还没回来时界面要显示"加载中"
        assertTrue(vm.state.value.subtitleLoading)
        assertEquals(SubtitleStatus.LOADING, vm.state.value.subtitleStatus)

        settle()
        assertFalse(vm.state.value.subtitleLoading)
        assertEquals(SubtitleStatus.READY, vm.state.value.subtitleStatus)
    }

    @Test
    fun manualCandidateReplacesTheCurrentTrack() = playerTest {
        val host = FakeSubtitleHost(
            listOf(autoCandidate("Movies/b.zh.srt"), manualCandidate("Movies/other.srt")),
        )
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        host.put("Movies/other.srt", "1\n00:00:01,000 --> 00:00:02,000\n只有一条\n")
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.selectSubtitle(host.candidates[1])
        settle()

        // 单轨：选了新的就替换旧的（不会两轨同时显示）
        assertEquals("Movies/other.srt", vm.state.value.subtitlePath)
        assertEquals(1, vm.state.value.subtitleCues.size)
        assertEquals(listOf("Movies/b.zh.srt", "Movies/other.srt"), host.loaded)
    }

    @Test
    fun selectingByPathUsesFileNameAsLabel() = playerTest {
        val host = FakeSubtitleHost()
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = false))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.selectSubtitle("Movies/b.zh.srt")
        settle()

        assertEquals("b.zh.srt", vm.state.value.subtitleLabel)
        assertEquals(SubtitleStatus.READY, vm.state.value.subtitleStatus)
    }

    @Test
    fun enablingWithoutTrackTriggersAutoMatch() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = false))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.setSubtitleEnabled(true)
        settle()

        assertEquals(listOf("Movies/b.zh.srt"), host.loaded)
        assertEquals(SubtitleStatus.READY, vm.state.value.subtitleStatus)
    }

    @Test
    fun togglingOffKeepsTrackButStopsRendering() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()
        env.last!!.position = 1_500L
        tick(1)
        assertEquals("第一条", vm.subtitleCue.value?.text)

        vm.setSubtitleEnabled(false)
        settle()

        assertNull(vm.subtitleCue.value)
        assertEquals(3, vm.state.value.subtitleCues.size)
        assertEquals(SubtitleStatus.OFF, vm.state.value.subtitleStatus)
    }

    @Test
    fun clearSubtitleRemovesTrackButKeepsCandidates() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.clearSubtitle()
        settle()

        assertNull(vm.state.value.subtitlePath)
        assertTrue(vm.state.value.subtitleCues.isEmpty())
        assertEquals(1, vm.state.value.subtitleCandidates.size)
    }

    // ------------------------------------------------------------------ 失败提示（R14 不静默）

    @Test
    fun loadFailureRaisesNoticeAndLeavesNoTrack() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.loadFailure = StorageException.AccessDenied("无读权限")
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))

        val vm = player(env, "Movies/b.mp4")
        settle()

        val notice = vm.state.value.subtitleNotice
        assertEquals(SubtitleNoticeKind.LOAD_FAILED, notice?.kind)
        assertEquals("无读权限", notice?.detail)
        assertNull(vm.state.value.subtitlePath)
        assertFalse(vm.state.value.subtitleLoading)
        assertEquals(SubtitleStatus.EMPTY, vm.state.value.subtitleStatus)
    }

    @Test
    fun discoverFailureRaisesLoadFailedNotice() = playerTest {
        val host = FakeSubtitleHost()
        host.discoverFailure = StorageException.Network("网络不可用")
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(SubtitleNoticeKind.LOAD_FAILED, vm.state.value.subtitleNotice?.kind)
        assertEquals("网络不可用", vm.state.value.subtitleNotice?.detail)
    }

    @Test
    fun noCandidateRaisesNotice() = playerTest {
        val host = FakeSubtitleHost(emptyList())
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(SubtitleNoticeKind.NO_CANDIDATE, vm.state.value.subtitleNotice?.kind)
    }

    @Test
    fun emptySubtitleFileRaisesNotice() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", "")
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(SubtitleNoticeKind.LOAD_FAILED, vm.state.value.subtitleNotice?.kind)
        assertTrue(vm.state.value.subtitleNotice?.detail.orEmpty().contains("没有"))
    }

    @Test
    fun damagedSubtitleRaisesParseNotice() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put(
            "Movies/b.zh.srt",
            "坏块没有时间轴\n\n1\n00:00:05,000 --> 00:00:01,000\n倒挂\n\n2\n00:00:06,000 --> 00:00:07,000\n好的\n",
        )
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(SubtitleNoticeKind.PARSE_DAMAGED, vm.state.value.subtitleNotice?.kind)
        assertEquals("2", vm.state.value.subtitleNotice?.detail)
        assertEquals(1, vm.state.value.subtitleCues.size)
    }

    @Test
    fun clearNoticeRemovesIt() = playerTest {
        val host = FakeSubtitleHost(emptyList())
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()
        assertEquals(SubtitleNoticeKind.NO_CANDIDATE, vm.state.value.subtitleNotice?.kind)

        vm.clearSubtitleNotice()
        settle()

        assertNull(vm.state.value.subtitleNotice)
    }

    // ------------------------------------------------------------------ 时间轴微调（R14）

    @Test
    fun nudgeStepsHalfASecondAndPersists() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val prefs = FakeVideoPreferences(subtitleEnabled = true)
        val env = environment(host, prefs)
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.nudgeSubtitle(1)
        settle()
        assertEquals(500L, vm.state.value.subtitleOffsetMs)
        assertEquals("+0.5", vm.state.value.subtitleOffsetText)

        vm.nudgeSubtitle(-2)
        settle()
        assertEquals(-500L, vm.state.value.subtitleOffsetMs)
        assertEquals(listOf(500L, -500L), prefs.subtitleOffsetWrites)
    }

    @Test
    fun offsetIsClampedToBounds() = playerTest {
        val host = FakeSubtitleHost()
        val env = environment(host)
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.setSubtitleOffset(999_999L)
        settle()
        assertEquals(60_000L, vm.state.value.subtitleOffsetMs)

        vm.setSubtitleOffset(-999_999L)
        settle()
        assertEquals(-60_000L, vm.state.value.subtitleOffsetMs)
    }

    @Test
    fun storedOffsetAndStyleAreAppliedOnEntry() = playerTest {
        val host = FakeSubtitleHost()
        val prefs = FakeVideoPreferences(
            subtitleEnabled = false,
            subtitleStyle = SubtitleStyle(fontSizeSp = 26f, bold = true),
            subtitleOffsetMs = 1_500L,
        )
        val env = environment(host, prefs)

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(1_500L, vm.state.value.subtitleOffsetMs)
        assertEquals(26f, vm.state.value.subtitleStyle.fontSizeSp)
        assertTrue(vm.state.value.subtitleStyle.bold)
        assertFalse(vm.state.value.subtitleEnabled)
    }

    @Test
    fun subtitleCueFollowsPositionAndOffset() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!

        engine.position = 1_500L
        tick(1)
        assertEquals("第一条", vm.subtitleCue.value?.text)

        engine.position = 4_000L
        tick(1)
        assertNull(vm.subtitleCue.value)

        engine.position = 5_500L
        tick(1)
        assertEquals("第二条", vm.subtitleCue.value?.text)

        // +1 秒：第二条要等到 6.5 秒才出现
        vm.setSubtitleOffset(1_000L)
        tick(1)
        assertNull(vm.subtitleCue.value)

        engine.position = 6_500L
        tick(1)
        assertEquals("第二条", vm.subtitleCue.value?.text)
    }

    // ------------------------------------------------------------------ 样式（R14）

    @Test
    fun styleChangesAreClampedAndPersisted() = playerTest {
        val host = FakeSubtitleHost()
        val prefs = FakeVideoPreferences()
        val env = environment(host, prefs)
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.setSubtitleFontSize(100f)
        settle()
        assertEquals(SubtitleStyle.MAX_FONT_SIZE_SP, vm.state.value.subtitleStyle.fontSizeSp)

        vm.setSubtitleOutlineWidth(99f)
        vm.setSubtitleBottomMargin(-10f)
        vm.setSubtitleTextColor(0xFFFFEB3B.toInt())
        vm.toggleSubtitleBold()
        vm.toggleSubtitleItalic()
        settle()

        val style = vm.state.value.subtitleStyle
        assertEquals(SubtitleStyle.MAX_OUTLINE_WIDTH_DP, style.outlineWidthDp)
        assertEquals(SubtitleStyle.MIN_BOTTOM_MARGIN_DP, style.bottomMarginDp)
        assertEquals(0xFFFFEB3B.toInt(), style.textColorArgb)
        assertTrue(style.bold)
        assertTrue(style.italic)
        assertEquals(6, prefs.subtitleStyleWrites.size)
        assertEquals(style, prefs.subtitleStyleWrites.last())
    }

    // ------------------------------------------------------------------ 写回 / 另存（R14）

    @Test
    fun writeBackSendsShiftedCuesAndResetsOffset() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val prefs = FakeVideoPreferences(subtitleEnabled = true)
        val env = environment(host, prefs)
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.setSubtitleOffset(500L)
        settle()
        vm.writeBackSubtitle()
        settle()

        val (videoPath, format, cues) = host.writes.single()
        assertEquals("Movies/b.mp4", videoPath)
        assertEquals(SubtitleFormat.SRT, format)
        // 写回的是「微调之后」的时间轴
        assertEquals(listOf(1_500L, 5_500L, 9_500L), cues.map { it.startMs })
        // 偏移已经烙进文件，状态归零并持久化
        assertEquals(0L, vm.state.value.subtitleOffsetMs)
        assertEquals(0L, prefs.subtitleOffsetWrites.last())
        assertEquals(SubtitleNoticeKind.WRITE_OK, vm.state.value.subtitleNotice?.kind)
        assertEquals("Movies/b.srt", vm.state.value.subtitleNotice?.detail)
    }

    @Test
    fun writeBackCanSaveAsVtt() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.writeBackSubtitle(SubtitleFormat.VTT)
        settle()

        assertEquals(SubtitleFormat.VTT, host.writes.single().second)
    }

    @Test
    fun writeBackWithoutPermissionFallsBackToLocalNotice() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        host.writeResult = SubtitleWriteResult.LocalFallback("/data/user/0/app/files/subtitles/b.srt")
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()
        vm.setSubtitleOffset(500L)
        settle()

        vm.writeBackSubtitle()
        settle()

        val notice = vm.state.value.subtitleNotice
        assertEquals(SubtitleNoticeKind.WRITE_LOCAL, notice?.kind)
        assertEquals("/data/user/0/app/files/subtitles/b.srt", notice?.detail)
        // 落本地不算写回原目录：偏移保持不变，用户可以稍后重试
        assertEquals(500L, vm.state.value.subtitleOffsetMs)
    }

    @Test
    fun writeBackFailureRaisesNotice() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        host.writeResult = SubtitleWriteResult.Failed("网络中断")
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.writeBackSubtitle()
        settle()

        assertEquals(SubtitleNoticeKind.WRITE_FAILED, vm.state.value.subtitleNotice?.kind)
        assertEquals("网络中断", vm.state.value.subtitleNotice?.detail)
    }

    @Test
    fun writeBackIsIgnoredWithoutTrack() = playerTest {
        val host = FakeSubtitleHost()
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = false))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.writeBackSubtitle()
        settle()

        assertTrue(host.writes.isEmpty())
        assertFalse(vm.state.value.canWriteBackSubtitle)
    }

    // ------------------------------------------------------------------ 与播放流程的联动

    @Test
    fun switchingEngineKeepsSubtitleTrack() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.switchEngine()
        settle()

        assertEquals("Movies/b.zh.srt", vm.state.value.subtitlePath)
        assertEquals(3, vm.state.value.subtitleCues.size)
        assertEquals(SubtitleStatus.READY, vm.state.value.subtitleStatus)
    }

    @Test
    fun episodeChangeMatchesSubtitleOfTheNewEpisode() = playerTest {
        val host = FakeSubtitleHost()
        host.candidatesFor = { path -> listOf(autoCandidate(path.substringBeforeLast('.') + ".zh.srt")) }
        host.put("Movies/a.zh.srt", "1\n00:00:01,000 --> 00:00:02,000\nA 集字幕\n")
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()
        assertEquals("Movies/b.zh.srt", vm.state.value.subtitlePath)

        vm.previous()
        settle()

        assertEquals("Movies/a.mp4", vm.state.value.path)
        assertEquals("Movies/a.zh.srt", vm.state.value.subtitlePath)
        assertEquals("A 集字幕", vm.state.value.subtitleCues.single().text)
    }

    @Test
    fun switchingEngineDuringLoadingDoesNotLoseCandidates() = playerTest {
        val host = FakeSubtitleHost(listOf(autoCandidate("Movies/b.zh.srt"), manualCandidate("Movies/other.srt")))
        host.put("Movies/b.zh.srt", SAMPLE_SRT)
        val env = environment(host, FakeVideoPreferences(subtitleEnabled = true))
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.openSubtitlePanel()
        settle()

        assertEquals(2, vm.state.value.subtitleCandidates.size)
        assertEquals(1, vm.state.value.autoSubtitleCandidates.size)
        assertEquals(1, vm.state.value.manualSubtitleCandidates.size)
        assertEquals("中文（简体） · SRT", vm.state.value.autoSubtitleCandidates.single().displayName)
        assertEquals(SubtitleFormat.SRT, vm.state.value.subtitleWriteFormat)
        assertTrue(vm.state.value.canWriteBackSubtitle)
    }

    // ------------------------------------------------------------------ 测试脚手架

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

    private fun TestScope.player(env: FakeVideoPlayerEnvironment, path: String): VideoPlayerViewModel {
        val vm = VideoPlayerViewModel(
            environment = env,
            initialPath = path,
            io = dispatcher,
            exitScope = backgroundScope,
        )
        val store = ViewModelStore()
        store.put("player-video-subtitle", vm)
        stores += store
        return vm
    }

    private fun environment(
        subtitles: FakeSubtitleHost,
        preferences: FakeVideoPreferences = FakeVideoPreferences(),
    ): FakeVideoPlayerEnvironment = FakeVideoPlayerEnvironment(
        entries = episodeEntries(),
        preferences = preferences,
        subtitles = subtitles,
    )

    /** 两集：a.mp4 / b.mp4（换集测试要用）。 */
    private fun episodeEntries(): Map<String, List<RemoteEntry>> = mapOf(
        "Movies" to listOf(videoEntry("Movies/a.mp4"), videoEntry("Movies/b.mp4")),
    )
}
