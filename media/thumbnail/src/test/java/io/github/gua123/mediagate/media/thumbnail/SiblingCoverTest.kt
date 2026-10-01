package io.github.gua123.mediagate.media.thumbnail

import io.github.gua123.mediagate.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同目录封面兜底的选择口径（**R5**）。
 *
 * 这一条直接影响"没有内嵌封面的专辑在列表里长什么样"，所以优先级与"不误认"都要钉住。
 */
class SiblingCoverTest {

    @Test
    fun `按通行文件名优先级挑封面`() {
        val picked = SiblingCover.pick(
            listOf(
                entry("Music/album/folder.png"),
                entry("Music/album/cover.jpg"),
                entry("Music/album/track01.flac"),
            ),
        )
        assertEquals("Music/album/cover.jpg", picked?.path)
    }

    @Test
    fun `没有 cover 时退到 folder，再退到 front`() {
        assertEquals(
            "Music/album/folder.jpg",
            SiblingCover.pick(listOf(entry("Music/album/folder.jpg"), entry("Music/album/front.jpg")))?.path,
        )
        assertEquals(
            "Music/album/front.jpg",
            SiblingCover.pick(listOf(entry("Music/album/front.jpg")))?.path,
        )
    }

    @Test
    fun `大小写与图片扩展名都认`() {
        assertEquals("Music/Album/COVER.JPEG", SiblingCover.pick(listOf(entry("Music/Album/COVER.JPEG")))?.path)
        assertTrue(SiblingCover.isImage("x.WEBP"))
        assertFalse(SiblingCover.isImage("x.txt"))
    }

    @Test
    fun `不把歌词本海报截图当封面`() {
        assertNull(
            "非通行基名的图片不该被当成封面",
            SiblingCover.pick(
                listOf(
                    entry("Music/album/booklet-01.jpg"),
                    entry("Music/album/screenshot.png"),
                    entry("Music/album/readme.txt"),
                ),
            ),
        )
        // 目录项本身不算
        assertNull(SiblingCover.pick(listOf(entry("Music/album/cover.jpg", directory = true))))
    }

    private fun entry(path: String, directory: Boolean = false): RemoteEntry = RemoteEntry(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = directory,
        size = 1024L,
        mtime = 1L,
    )
}
