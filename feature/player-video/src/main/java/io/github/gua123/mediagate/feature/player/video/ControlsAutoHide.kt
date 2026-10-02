package io.github.gua123.mediagate.feature.player.video

/**
 * 播放页控制层的自动隐藏规则（**2026-10-03 用户要求**：「播放视频时，弹出的窗口，无操作多少秒时
 * 才隐藏，有操作时不能隐藏」）。
 *
 * 两条语义分得很清楚：
 * - **有没有操作**决定"要不要开始计时"（手指按着、正在拖进度、面板还开着 ⇒ 计时根本不启动，更不会隐藏）；
 * - **多少秒**是一个可配置的值（3 / 5 / 10 秒，或 0 = 永不自动隐藏）。
 *
 * 纯函数：界面只负责把"当前是不是在操作"和"配置了几秒"喂进来。
 */
object ControlsAutoHide {

    /** 可选的隐藏延时（秒）；0 = 永不自动隐藏。 */
    val SECONDS_CHOICES: List<Int> = listOf(3, 5, 10, 0)

    /** 默认 3 秒（与旧版一致）。 */
    const val DEFAULT_SECONDS: Int = 3

    /** 把存下来的值夹到合法档位（陌生值回默认）。 */
    fun normalizeSeconds(seconds: Int): Int = if (seconds in SECONDS_CHOICES) seconds else DEFAULT_SECONDS

    /** 延时毫秒；0/负数 ⇒ null 表示**永不自动隐藏**。 */
    fun timeoutMs(seconds: Int): Long? {
        val s = normalizeSeconds(seconds)
        return if (s <= 0) null else s * 1_000L
    }

    /**
     * 现在能不能开始"无操作计时"。
     *
     * @param playing 正在播放（暂停/结束/出错时控制层留着——用户在看信息或准备操作）。
     * @param interacting 有任何操作在进行：手指按在屏幕上、正在拖进度、面板/弹窗开着。
     * @param inPip 处于画中画（画中画里没有我们的控制层）。
     * @param controlsVisible 控制层当前是显示的（没显示就没什么可隐藏的）。
     */
    fun shouldStartCountdown(
        playing: Boolean,
        interacting: Boolean,
        inPip: Boolean,
        controlsVisible: Boolean,
    ): Boolean = controlsVisible && playing && !interacting && !inPip

    /** 档位文案（设置页用）。 */
    fun label(seconds: Int): String {
        val s = normalizeSeconds(seconds)
        return if (s <= 0) "不自动隐藏" else s.toString() + " 秒"
    }
}
