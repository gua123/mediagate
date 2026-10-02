package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「记住上次是横屏还是竖屏」的档位解析与切换。 */
class PlayerOrientationTest {

    @Test
    fun 点一下在横竖之间切换() {
        assertEquals(PlayerOrientation.LANDSCAPE, PlayerOrientation.PORTRAIT.toggled())
        assertEquals(PlayerOrientation.PORTRAIT, PlayerOrientation.LANDSCAPE.toggled())
    }

    @Test
    fun 由当前屏幕方向换算() {
        assertEquals(PlayerOrientation.LANDSCAPE, PlayerOrientation.ofLandscape(true))
        assertEquals(PlayerOrientation.PORTRAIT, PlayerOrientation.ofLandscape(false))
    }

    @Test
    fun 存下来的值认不出来就当没记录_跟随系统() {
        assertEquals(PlayerOrientation.LANDSCAPE, PlayerOrientation.parse("LANDSCAPE"))
        assertNull("旧版本残留", PlayerOrientation.parse("SOMETHING"))
        assertNull("没有记录", PlayerOrientation.parse(null))
    }
}
