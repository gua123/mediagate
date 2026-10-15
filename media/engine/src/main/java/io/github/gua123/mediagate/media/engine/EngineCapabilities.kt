package io.github.gua123.mediagate.media.engine

/**
 * 内核能力矩阵（**不做静默降级**：界面要么按能力调整档位，要么如实说明"这一档不支持"）。
 *
 * 为什么要显式声明：过渡期同时存在自研引擎与两个旧内核，能力并不齐平
 * （例：自研引擎还没有倍速与硬解档位）。把这些差异写在类型里，
 * 上层就能在**进入某个内核之前**决定要不要拦、要不要提示，而不是等用户点了没反应。
 *
 * 默认值取"全支持"：旧内核没声明的地方保持原行为，避免这个类型引入行为变化。
 */
data class EngineCapabilities(
    /** 支持倍速播放（[PlayerEngine.setSpeed] 真的起作用）。 */
    val speed: Boolean = true,

    /** 支持哪些画面缩放档位。 */
    val resizeModes: Set<ResizeMode> = ResizeMode.entries.toSet(),

    /** 音量能超过 100%（>1.0）。自研引擎用软件增益，会削波。 */
    val volumeBoost: Boolean = true,

    /** 支持解码档位切换（硬解/软解）。 */
    val decoderModes: Boolean = true,
) {
    /** 是否支持某一档缩放。 */
    fun supports(mode: ResizeMode): Boolean = mode in resizeModes

    companion object {
        /** 全支持（旧内核的默认口径）。 */
        val ALL: EngineCapabilities = EngineCapabilities()
    }
}
