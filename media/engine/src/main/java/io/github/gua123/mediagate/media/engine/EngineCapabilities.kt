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

/**
 * 内核能力表（**按种类就能查，不需要先造出一个内核实例**）。
 *
 * 为什么需要它：界面得在**切换之前**告诉用户"目标内核少什么"，
 * 而 [PlayerEngine.capabilities] 只有把实例造出来才读得到。所以这里是唯一的一份能力声明，
 * 各引擎实现也从这里取（不许自己再写一份 —— 有源码护栏钉着，见 NativeEngineContractTest）。
 *
 * 现状（2026-10-15）：
 * - Media3 / LibVLC ＝过渡期兜底内核，能力齐平；
 * - NATIVE（自研引擎）如实声明：**倍速 ✗、解码档位 ✗**（只有 FFmpeg 软解一条路），
 *   缩放四档 ✓、音量 >100% ✓（软件增益）、外挂字幕 ✓（由 App 的覆盖层渲染，与内核无关）。
 */
object EngineCatalog {

    fun capabilitiesOf(kind: EngineKind): EngineCapabilities = when (kind) {
        EngineKind.MEDIA3, EngineKind.VLC -> EngineCapabilities.ALL

        EngineKind.NATIVE -> EngineCapabilities(
            speed = false,
            resizeModes = ResizeMode.entries.toSet(),
            volumeBoost = true,
            decoderModes = false,
        )
    }
}
