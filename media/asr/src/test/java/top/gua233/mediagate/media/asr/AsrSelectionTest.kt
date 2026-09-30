package io.github.gua123.mediagate.media.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat

/**
 * 批量选择与过滤的 JVM 单测（**M7-B / R19**）：自动过滤非视频、跳过已有字幕、命名规则。
 */
class AsrSelectionTest {

    @Test
    fun isVideoEntry_acceptsCommonVideoContainers() {
        assertTrue(AsrSelection.isVideoEntry("Movie.mkv"))
        assertTrue(AsrSelection.isVideoEntry("Show.S01E01.MP4"))
        assertTrue(AsrSelection.isVideoEntry("clip.ts", size = 10L))
    }

    @Test
    fun isVideoEntry_rejectsDirectoriesAudioImagesAndPlaylists() {
        assertFalse(AsrSelection.isVideoEntry("Movies", isDirectory = true))
        assertFalse(AsrSelection.isVideoEntry("song.mp3"))
        assertFalse(AsrSelection.isVideoEntry("photo.jpg"))
        assertFalse(AsrSelection.isVideoEntry("index.m3u8"))
        assertFalse(AsrSelection.isVideoEntry("empty.mkv", size = 0L))
        assertFalse(AsrSelection.isVideoEntry("noextension"))
    }

    @Test
    fun videoEntries_filtersRemoteEntries() {
        val entries = listOf(
            RemoteEntry("Movies", "/Movies", isDirectory = true),
            RemoteEntry("a.mkv", "/Movies/a.mkv", size = 100L),
            RemoteEntry("b.srt", "/Movies/b.srt", size = 10L),
            RemoteEntry("c.mp4", "/Movies/c.mp4", size = 0L),
            RemoteEntry("d.mp4", "/Movies/d.mp4", size = 100L),
        )
        assertEquals(listOf("a.mkv", "d.mp4"), AsrSelection.videoEntries(entries).map { it.name })
    }

    @Test
    fun hasExistingSubtitle_matchesSameNameAndLanguageSuffix() {
        val video = "/Movies/Movie.mkv"
        assertTrue(AsrSelection.hasExistingSubtitle(video, setOf("Movie.srt")))
        assertTrue(AsrSelection.hasExistingSubtitle(video, setOf("Movie.zh.srt")))
        assertTrue(AsrSelection.hasExistingSubtitle(video, setOf("Movie.chs.ass")))
        assertTrue(AsrSelection.hasExistingSubtitle(video, setOf("Movie.zh-Hans.vtt")))
        assertFalse(AsrSelection.hasExistingSubtitle(video, setOf("Movie2.srt", "Movie.zh.txt", "Other.srt")))
        assertFalse(AsrSelection.hasExistingSubtitle(video, emptySet()))
    }

    @Test
    fun findExistingSubtitle_returnsTheMatchedName() {
        assertEquals(
            "Movie.chs.ass",
            AsrSelection.findExistingSubtitle("/Movies/Movie.mkv", setOf("readme.txt", "Movie.chs.ass")),
        )
        assertNull(AsrSelection.findExistingSubtitle("/Movies/Movie.mkv", setOf("readme.txt")))
    }

    @Test
    fun plan_skipsExistingWhenRequested() {
        val candidates = listOf(
            AsrCandidate("/m/a.mkv", "a.mkv", 100L, hasSubtitle = true),
            AsrCandidate("/m/b.mkv", "b.mkv", 100L, hasSubtitle = false),
            AsrCandidate("/m/c.txt", "c.txt", 10L),
            AsrCandidate("/m/d.mkv", "d.mkv", 0L),
        )
        val planned = AsrSelection.plan(candidates, skipExisting = true)
        assertEquals(listOf("b.mkv"), planned.accepted.map { it.name })
        assertEquals(listOf("a.mkv"), planned.skipped.map { it.name })
        assertEquals(2, planned.filteredOut)
        assertEquals(4, planned.examined)
        assertTrue(AsrSelection.skipReason(candidates[0]).isNotEmpty())
    }

    @Test
    fun plan_keepsEverythingWhenSkipDisabled() {
        val candidates = listOf(
            AsrCandidate("/m/a.mkv", "a.mkv", 100L, hasSubtitle = true),
            AsrCandidate("/m/b.mkv", "b.mkv", 100L),
        )
        val planned = AsrSelection.plan(candidates, skipExisting = false)
        assertEquals(listOf("a.mkv", "b.mkv"), planned.accepted.map { it.name })
        assertTrue(planned.skipped.isEmpty())
    }

    @Test
    fun naming_rulesFollowTheVideoFile() {
        assertEquals("Movie.srt", AsrSelection.outputNameFor("/Movies/Movie.mkv", SubtitleFormat.SRT))
        assertEquals("Movie.vtt", AsrSelection.outputNameFor("/Movies/Movie.mkv", SubtitleFormat.VTT))
        assertEquals("/Movies/Movie.srt", AsrSelection.outputPathFor("/Movies/Movie.mkv", SubtitleFormat.SRT))
        assertEquals("Movie.srt", AsrSelection.outputPathFor("Movie.mkv", SubtitleFormat.SRT))
    }

    @Test
    fun baseNameOf_stripsOnlyTheLastExtension() {
        assertEquals("Movie.2024", AsrSelection.baseNameOf("Movie.2024.mkv"))
        assertEquals("Movie", AsrSelection.baseNameOf("Movie"))
    }
}
