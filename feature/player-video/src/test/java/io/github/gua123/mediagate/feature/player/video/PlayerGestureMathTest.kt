package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** 竖直手势换算（亮度/音量）的边界用例。 */
class PlayerGestureMathTest {

    @Test
    fun 左三分之一是亮度_右三分之一是音量_中间是进度() {
        // 边界按像素定：900 宽 ⇒ 亮 [0,300)、进度 [300,600)、音量 [600,900]
        assertEquals(PlayerGestureZone.BRIGHTNESS, PlayerGestureMath.zoneOf(0f, 900))
        assertEquals(PlayerGestureZone.BRIGHTNESS, PlayerGestureMath.zoneOf(299f, 900))
        assertEquals(PlayerGestureZone.SEEK, PlayerGestureMath.zoneOf(300f, 900))
        assertEquals(PlayerGestureZone.SEEK, PlayerGestureMath.zoneOf(599f, 900))
        assertEquals(PlayerGestureZone.VOLUME, PlayerGestureMath.zoneOf(600f, 900))
        assertEquals(PlayerGestureZone.VOLUME, PlayerGestureMath.zoneOf(899f, 900))
    }

    @Test
    fun 宽度为零时不会崩_退化成进度区() {
        assertEquals(PlayerGestureZone.SEEK, PlayerGestureMath.zoneOf(10f, 0))
    }

    @Test
    fun 向上拖变大_向下拖变小() {
        // 整屏高 1000px、量程 0..2：往上拖 250px ⇒ 增加 0.5
        assertEquals(0.5f, PlayerGestureMath.applyVerticalDrag(0f, -250f, 1000, 0f, 2f), 0.001f)
        assertEquals(1.5f, PlayerGestureMath.applyVerticalDrag(2f, 250f, 1000, 0f, 2f), 0.001f)
    }

    @Test
    fun 越界会被夹住() {
        assertEquals(2f, PlayerGestureMath.applyVerticalDrag(1.9f, -900f, 1000, 0f, 2f), 0.001f)
        assertEquals(0f, PlayerGestureMath.applyVerticalDrag(0.1f, 900f, 1000, 0f, 2f), 0.001f)
    }

    @Test
    fun 高度为零或位移是NaN时原样返回() {
        assertEquals(0.7f, PlayerGestureMath.applyVerticalDrag(0.7f, -100f, 0, 0f, 2f), 0.001f)
        assertEquals(0.7f, PlayerGestureMath.applyVerticalDrag(0.7f, Float.NaN, 1000, 0f, 2f), 0.001f)
    }

    @Test
    fun 文案带百分号_音量可以超过一百() {
        assertEquals("亮度 40%", PlayerGestureMath.label(PlayerGestureZone.BRIGHTNESS, 0.4f))
        assertEquals("音量 180%", PlayerGestureMath.label(PlayerGestureZone.VOLUME, 1.8f))
    }
}
