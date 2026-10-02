package io.github.gua123.mediagate.feature.player.video

/**
 * 播放方向（**2026-10-03 用户要求**：「记住上一次播放时是横屏还是竖屏」）。
 *
 * 只记"用户主动选过的那一个"：没记录（null）＝跟随系统。
 */
enum class PlayerOrientation {

    /** 竖屏。 */
    PORTRAIT,

    /** 横屏。 */
    LANDSCAPE;

    /** 点一下旋转按钮：在横竖之间换。 */
    fun toggled(): PlayerOrientation = if (this == PORTRAIT) LANDSCAPE else PORTRAIT

    companion object {

        /** 当前屏幕实际方向（来自 Configuration）→ 枚举。 */
        fun ofLandscape(landscape: Boolean): PlayerOrientation =
            if (landscape) LANDSCAPE else PORTRAIT

        /** 存下来的字符串 → 枚举；认不出来回 null（＝跟随系统）。 */
        fun parse(raw: String?): PlayerOrientation? =
            entries.firstOrNull { it.name == raw }
    }
}
