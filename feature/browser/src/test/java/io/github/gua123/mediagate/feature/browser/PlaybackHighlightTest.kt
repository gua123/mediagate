package io.github.gua123.mediagate.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「上次播放」高亮：文件本身 + 通往它的每一级目录。 */
class PlaybackHighlightTest {

    @Test
    fun 那个视频本身高亮() {
        assertEquals(
            HighlightKind.LAST_PLAYED,
            PlaybackHighlight.kindOf("/media/a.mp4", isDirectory = false, lastPlayedPath = "/media/a.mp4"),
        )
        assertEquals(
            HighlightKind.NONE,
            PlaybackHighlight.kindOf("/media/b.mp4", isDirectory = false, lastPlayedPath = "/media/a.mp4"),
        )
    }

    @Test
    fun 祖先链每一级都高亮_一直到根目录() {
        val last = "/tv/shows/s01/e01.mp4"
        assertEquals(HighlightKind.ON_PATH, PlaybackHighlight.kindOf("/tv", true, last))
        assertEquals(HighlightKind.ON_PATH, PlaybackHighlight.kindOf("/tv/shows", true, last))
        assertEquals(HighlightKind.ON_PATH, PlaybackHighlight.kindOf("/tv/shows/s01", true, last))
        // 根目录（空串）也是祖先
        assertEquals(HighlightKind.ON_PATH, PlaybackHighlight.kindOf("", true, last))
    }

    @Test
    fun 不在路径上的目录不变色() {
        val last = "/tv/shows/s01/e01.mp4"
        assertEquals(HighlightKind.NONE, PlaybackHighlight.kindOf("/music", true, last))
        assertEquals(HighlightKind.NONE, PlaybackHighlight.kindOf("/tv/movies", true, last))
    }

    @Test
    fun 前缀相同但边界不同的目录不算祖先() {
        assertFalse(PlaybackHighlight.isAncestor("/a/b", "/a/bc/d.mp4"))
        assertTrue(PlaybackHighlight.isAncestor("/a/b", "/a/b/d.mp4"))
    }

    @Test
    fun 没有记录时谁都不变色() {
        assertEquals(HighlightKind.NONE, PlaybackHighlight.kindOf("/media/a.mp4", false, null))
        assertEquals(HighlightKind.NONE, PlaybackHighlight.kindOf("/media", true, "  "))
    }
}
