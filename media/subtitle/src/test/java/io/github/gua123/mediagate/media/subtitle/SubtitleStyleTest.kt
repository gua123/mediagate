package io.github.gua123.mediagate.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字幕样式模型（R14：字号 / 颜色 / 描边 / 底部边距 / 加粗斜体，默认值与越界钳制）。 */
class SubtitleStyleTest {

    @Test
    fun defaultsAreUsable() {
        val style = SubtitleStyle()

        assertEquals(18f, style.fontSizeSp)
        assertEquals(0xFFFFFFFF.toInt(), style.textColorArgb)
        assertEquals(1.5f, style.outlineWidthDp)
        assertEquals(0xFF000000.toInt(), style.outlineColorArgb)
        assertEquals(24f, style.bottomMarginDp)
        assertFalse(style.bold)
        assertFalse(style.italic)
        assertEquals(SubtitleStyle.DEFAULT, style)
        assertTrue(style.isValid)
    }

    @Test
    fun clampsFontSizeToRange() {
        assertEquals(SubtitleStyle.MIN_FONT_SIZE_SP, SubtitleStyle(fontSizeSp = 4f).clamped().fontSizeSp)
        assertEquals(SubtitleStyle.MAX_FONT_SIZE_SP, SubtitleStyle(fontSizeSp = 100f).clamped().fontSizeSp)
        assertEquals(20f, SubtitleStyle(fontSizeSp = 20f).clamped().fontSizeSp)
    }

    @Test
    fun clampsOutlineWidthAndBottomMargin() {
        val style = SubtitleStyle(outlineWidthDp = -3f, bottomMarginDp = 999f).clamped()

        assertEquals(SubtitleStyle.MIN_OUTLINE_WIDTH_DP, style.outlineWidthDp)
        assertEquals(SubtitleStyle.MAX_BOTTOM_MARGIN_DP, style.bottomMarginDp)
        assertEquals(SubtitleStyle.MAX_OUTLINE_WIDTH_DP, SubtitleStyle(outlineWidthDp = 99f).clamped().outlineWidthDp)
        assertEquals(SubtitleStyle.MIN_BOTTOM_MARGIN_DP, SubtitleStyle(bottomMarginDp = -1f).clamped().bottomMarginDp)
    }

    @Test
    fun nanFallsBackToDefaults() {
        val style = SubtitleStyle(
            fontSizeSp = Float.NaN,
            outlineWidthDp = Float.NaN,
            bottomMarginDp = Float.NaN,
        ).clamped()

        assertEquals(SubtitleStyle.DEFAULT, style)
    }

    @Test
    fun isValidDetectsOutOfRangeValues() {
        assertFalse(SubtitleStyle(fontSizeSp = 5f).isValid)
        assertFalse(SubtitleStyle(outlineWidthDp = 50f).isValid)
        assertFalse(SubtitleStyle(bottomMarginDp = 500f).isValid)
        assertTrue(SubtitleStyle(fontSizeSp = 24f, bold = true, italic = true).isValid)
        assertTrue(SubtitleStyle(fontSizeSp = 5f).clamped().isValid)
    }

    @Test
    fun copyOnlyChangesTheGivenField() {
        val base = SubtitleStyle()

        val changed = base.copy(bold = true, textColorArgb = 0xFFFFEB3B.toInt())

        assertTrue(changed.bold)
        assertEquals(0xFFFFEB3B.toInt(), changed.textColorArgb)
        assertEquals(base.fontSizeSp, changed.fontSizeSp)
        assertEquals(base.bottomMarginDp, changed.bottomMarginDp)
    }

    @Test
    fun colorPalettesAreOpaqueAndNonEmpty() {
        assertTrue(SubtitleStyle.TEXT_COLORS.isNotEmpty())
        assertTrue(SubtitleStyle.OUTLINE_COLORS.isNotEmpty())
        assertTrue(SubtitleStyle.TEXT_COLORS.all { (it ushr 24) and 0xFF == 0xFF })
        assertTrue(SubtitleStyle.OUTLINE_COLORS.all { (it ushr 24) and 0xFF == 0xFF })
    }
}
