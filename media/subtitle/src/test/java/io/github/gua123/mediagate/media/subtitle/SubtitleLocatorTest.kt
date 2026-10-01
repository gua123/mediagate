package io.github.gua123.mediagate.media.subtitle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException

/** 外挂字幕定位与匹配（R14：同目录同名 → 语言后缀 → 修饰后缀，支持多候选，语言码表可配）。 */
class SubtitleLocatorTest {

    private fun locate(video: String, vararg subtitlePaths: String): List<SubtitleCandidate> =
        SubtitleLocator.locate(video, subtitlePaths.map { subtitleEntry(it) })

    @Test
    fun sameNameSubtitleIsTheTopCandidate() {
        val result = locate("Movies/a.mkv", "Movies/a.zh.srt", "Movies/a.srt")

        assertEquals(2, result.size)
        assertEquals("Movies/a.srt", result[0].path)
        assertEquals(SubtitleMatchKind.SAME_NAME, result[0].matchKind)
        assertEquals(SubtitleSource.AUTO, result[0].source)
        assertNull(result[0].language)
        assertEquals("Movies/a.zh.srt", result[1].path)
        assertEquals(SubtitleMatchKind.LANGUAGE_SUFFIX, result[1].matchKind)
        assertEquals("zh", result[1].language)
    }

    @Test
    fun sameNamePrefersSrtThenVttThenAssThenSsa() {
        val result = locate("a.mkv", "a.ssa", "a.ass", "a.vtt", "a.srt")

        assertEquals(listOf("a.srt", "a.vtt", "a.ass", "a.ssa"), result.map { it.name })
        assertTrue(result.all { it.matchKind == SubtitleMatchKind.SAME_NAME })
    }

    @Test
    fun chineseLanguageSuffixVariantsAllMatch() {
        val paths = listOf("v.zh.srt", "v.chs.srt", "v.chi.srt", "v.zh-CN.srt", "v.zh_Hans.srt")
        val result = SubtitleLocator.locate("v.mkv", paths.map { subtitleEntry(it) })
        val byName = result.associateBy { it.name }

        assertEquals(5, result.size)
        assertTrue(result.all { it.matchKind == SubtitleMatchKind.LANGUAGE_SUFFIX })
        assertEquals("zh", byName.getValue("v.zh.srt").language)
        assertEquals("zh-cn", byName.getValue("v.zh-CN.srt").language)
        assertEquals("zh-hans", byName.getValue("v.zh_Hans.srt").language)
        assertEquals("chs", byName.getValue("v.chs.srt").language)
        assertEquals("中文（简体）", byName.getValue("v.chs.srt").languageLabel)
        assertEquals("中文", byName.getValue("v.chi.srt").languageLabel)
    }

    @Test
    fun languageCandidatesFollowTableOrder() {
        val result = locate("v.mkv", "v.chi.srt", "v.chs.srt", "v.zh_Hans.srt", "v.zh-CN.srt", "v.zh.srt")

        assertEquals(
            listOf("v.zh.srt", "v.zh-CN.srt", "v.zh_Hans.srt", "v.chs.srt", "v.chi.srt"),
            result.map { it.name },
        )
    }

    @Test
    fun chineseLanguageComesBeforeEnglish() {
        val result = locate("v.mkv", "v.eng.srt", "v.zh.srt")

        assertEquals(listOf("v.zh.srt", "v.eng.srt"), result.map { it.name })
        assertEquals("英语", result[1].languageLabel)
    }

    @Test
    fun modifierSuffixIsTheLowestPriority() {
        val result = locate("v.mkv", "v.forced.srt", "v.default.srt", "v.zh.srt", "v.srt")

        assertEquals(listOf("v.srt", "v.zh.srt", "v.default.srt", "v.forced.srt"), result.map { it.name })
        assertEquals(SubtitleMatchKind.MODIFIER, result[2].matchKind)
        assertEquals(listOf("default"), result[2].modifiers)
        assertTrue(result[3].isForced)
        assertFalse(result[3].modifiers.contains("default"))
    }

    @Test
    fun languagePlusModifierKeepsLanguagePriority() {
        val result = locate("v.mkv", "v.zh.forced.srt")

        assertEquals(1, result.size)
        assertEquals(SubtitleMatchKind.LANGUAGE_SUFFIX, result[0].matchKind)
        assertEquals("zh", result[0].language)
        assertTrue(result[0].isForced)
    }

    @Test
    fun unknownTagIsNotACandidate() {
        assertTrue(locate("Movie.mkv", "Movie.2024.srt").isEmpty())
        assertEquals(1, locate("Movie.2024.mkv", "Movie.2024.srt").size)
    }

    @Test
    fun multiDotVideoBaseMatchesItsOwnLanguageSuffix() {
        val result = locate("Movies/Movie.2024.1080p.mkv", "Movies/Movie.2024.1080p.zh.srt")

        assertEquals(1, result.size)
        assertEquals("zh", result[0].language)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val result = locate("Movies/MOVIE.MKV", "Movies/movie.SRT")

        assertEquals(1, result.size)
        assertEquals(SubtitleMatchKind.SAME_NAME, result[0].matchKind)
        assertEquals(SubtitleFormat.SRT, result[0].format)
    }

    @Test
    fun entriesFromOtherDirectoriesAreIgnored() {
        assertTrue(locate("Movies/a.mkv", "Other/a.srt", "Other/a.zh.srt").isEmpty())
    }

    @Test
    fun directoriesAndOtherFilesAreIgnored() {
        val entries = listOf(
            subtitleEntry("a.srt", isDirectory = true),
            subtitleEntry("a.txt"),
            subtitleEntry("a.mkv"),
            subtitleEntry("a"),
        )

        assertTrue(SubtitleLocator.locate("a.mkv", entries).isEmpty())
    }

    @Test
    fun emptyEntryListGivesNoCandidate() {
        assertTrue(SubtitleLocator.locate("a.mkv", emptyList()).isEmpty())
    }

    @Test
    fun candidatesAreOrderedDeterministicallyRegardlessOfListingOrder() {
        val paths = listOf("v.srt", "v.zh.srt", "v.forced.srt", "v.eng.srt")
        val forward = SubtitleLocator.locate("v.mkv", paths.map { subtitleEntry(it) })
        val backward = SubtitleLocator.locate("v.mkv", paths.reversed().map { subtitleEntry(it) })

        assertEquals(forward.map { it.path }, backward.map { it.path })
        assertEquals(listOf("v.srt", "v.zh.srt", "v.eng.srt", "v.forced.srt"), forward.map { it.name })
    }

    @Test
    fun languageTableIsConfigurable() {
        val entries = listOf(subtitleEntry("v.xyz.srt"))

        assertTrue(SubtitleLocator.locate("v.mkv", entries).isEmpty())

        val custom = linkedMapOf("xyz" to "自定义语言")
        val result = SubtitleLocator.locate("v.mkv", entries, languageCodes = custom)
        assertEquals(1, result.size)
        assertEquals("xyz", result[0].language)
        assertEquals("自定义语言", result[0].languageLabel)
    }

    @Test
    fun modifiersAreConfigurable() {
        val entries = listOf(subtitleEntry("v.sign.srt"))

        assertTrue(SubtitleLocator.locate("v.mkv", entries).isEmpty())
        assertEquals(
            1,
            SubtitleLocator.locate("v.mkv", entries, modifiers = listOf("sign")).size,
        )
    }

    @Test
    fun manualCandidatesExcludeAutoMatchedFiles() {
        val entries = listOf(
            subtitleEntry("a.srt"),
            subtitleEntry("a.zh.srt"),
            subtitleEntry("other.srt"),
            subtitleEntry("a.txt"),
        )

        val manual = SubtitleLocator.manualCandidates("a.mkv", entries)

        assertEquals(listOf("other.srt"), manual.map { it.name })
        assertEquals(SubtitleSource.MANUAL, manual[0].source)
    }

    @Test
    fun manualCandidateStillShowsLanguageLabel() {
        val manual = SubtitleLocator.pickList("a.mkv", listOf(subtitleEntry("other.zh-Hans.srt")))
            .single { it.source == SubtitleSource.MANUAL }

        assertEquals("zh-hans", manual.language)
        assertEquals("中文（简体）", manual.languageLabel)
        assertEquals("中文（简体） · SRT", manual.displayName)
    }

    @Test
    fun pickListPutsAutoMatchesBeforeManualOnes() {
        val entries = listOf(subtitleEntry("a.zh.srt"), subtitleEntry("b.srt"))

        val list = SubtitleLocator.pickList("a.mkv", entries)

        assertEquals(listOf("a.zh.srt", "b.srt"), list.map { it.name })
        assertEquals(SubtitleSource.AUTO, list[0].source)
        assertEquals(SubtitleSource.MANUAL, list[1].source)
    }

    @Test
    fun manualCandidateReturnsNullForNonSubtitlePath() {
        assertNull(SubtitleCandidate.manual("a.txt"))
        assertNull(SubtitleCandidate.manual("a.mkv"))
    }

    @Test
    fun displayNameFallsBackToFileName() {
        val candidate = SubtitleLocator.locate("a.mkv", listOf(subtitleEntry("a.srt"))).single()

        assertEquals("a.srt", candidate.displayName)
    }

    @Test
    fun discoverAsksForTheVideoDirectory() = runTest {
        var asked: String? = null
        val candidates = SubtitleLocator.discover("Movies/Sub/a.mkv") { dir ->
            asked = dir
            listOf(subtitleEntry("Movies/Sub/a.zh.srt"))
        }

        assertEquals("Movies/Sub", asked)
        assertEquals("zh", candidates.single().language)
    }

    @Test
    fun discoverPropagatesStorageFailure() = runTest {
        try {
            SubtitleLocator.discover("a.mkv") { throw StorageException.Network("网络不可用") }
            fail("列目录失败应当抛出，交给播放页给中文提示")
        } catch (expected: StorageException.Network) {
            assertEquals("网络不可用", expected.message)
        }
    }

    @Test
    fun discoverWithBackendUsesTheSameListing() = runTest {
        val backend = FakeSubtitleBackend()
        backend.listing("Movies", listOf(subtitleEntry("Movies/a.zh.srt"), subtitleEntry("Movies/a.txt")))

        val candidates = SubtitleLocator.discover(backend, "Movies/a.mkv")

        assertEquals(listOf("Movies/a.zh.srt"), candidates.map { it.path })
    }

    @Test
    fun suffixTagsBoundaries() {
        assertNull(SubtitleLocator.suffixTags("a", "a"))
        assertNull(SubtitleLocator.suffixTags("ab", "a"))
        assertNull(SubtitleLocator.suffixTags("b.zh", "a"))
        assertNull(SubtitleLocator.suffixTags("a.", "a"))
        assertEquals(listOf("zh", "forced"), SubtitleLocator.suffixTags("a.zh.forced", "a"))
        // 标签保持原样返回（大小写归一由匹配逻辑负责），便于展示文件名里的原始写法
        assertEquals(listOf("ZH"), SubtitleLocator.suffixTags("A.ZH", "a"))
    }

    @Test
    fun pathHelpers() {
        assertEquals("Movies/Sub", SubtitleLocator.directoryOf("Movies/Sub/a.mkv"))
        assertEquals("", SubtitleLocator.directoryOf("a.mkv"))
        assertEquals("a.mkv", SubtitleLocator.fileNameOf("Movies/a.mkv"))
        assertEquals("Movie.2024", SubtitleLocator.baseNameOf("Movie.2024.mkv"))
        assertEquals("noext", SubtitleLocator.baseNameOf("noext"))
        assertEquals(".hidden", SubtitleLocator.baseNameOf(".hidden"))
    }

    @Test
    fun languageCodeNormalizationAndFirstLanguage() {
        assertEquals("zh-hans", SubtitleLocator.normalizeLanguageCode("ZH_Hans"))
        assertEquals(
            "zh-hans" to "中文（简体）",
            SubtitleLocator.firstLanguage(listOf("1080p", "zh_Hans")),
        )
        assertNull(SubtitleLocator.firstLanguage(listOf("1080p", "x265")))
    }

    @Test
    fun dualLanguageSuffixKeepsTheFirstLanguage() {
        val result = locate("v.mkv", "v.zh.eng.srt")

        assertEquals(1, result.size)
        assertEquals("zh", result[0].language)
    }
}
