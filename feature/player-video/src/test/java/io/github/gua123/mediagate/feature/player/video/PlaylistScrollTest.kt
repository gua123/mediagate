package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放列表打开时的定位。 */
class PlaylistScrollTest {

    @Test
    fun 打开面板时滚到当前项的上一个_好让上下文可见() {
        assertEquals(4, PlaylistScroll.firstVisibleIndex(currentIndex = 5, count = 20))
    }

    @Test
    fun 当前项是第一个时停在顶部() {
        assertEquals(0, PlaylistScroll.firstVisibleIndex(currentIndex = 0, count = 20))
        assertEquals(0, PlaylistScroll.firstVisibleIndex(currentIndex = 1, count = 20))
    }

    @Test
    fun 越界与空队列不崩() {
        assertEquals(0, PlaylistScroll.firstVisibleIndex(currentIndex = -3, count = 5))
        assertEquals(3, PlaylistScroll.firstVisibleIndex(currentIndex = 99, count = 5))
        assertEquals(0, PlaylistScroll.firstVisibleIndex(currentIndex = 2, count = 0))
    }
}
