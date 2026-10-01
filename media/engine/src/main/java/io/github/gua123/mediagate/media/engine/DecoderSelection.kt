package io.github.gua123.mediagate.media.engine

/**
 * 解码器候选的纯逻辑挑选（R10 软解），把 Media3 的 `MediaCodecInfo` 抽成纯数据以便 JVM 单测。
 *
 * @property name 解码器名（`c2.android.avc.decoder` / `OMX.qcom.video.decoder.hevc` …）。
 * @property softwareOnly 是否纯软件解码器（Media3 的 `MediaCodecInfo.softwareOnly`）。
 */
data class CodecCandidate(val name: String, val softwareOnly: Boolean)

/**
 * 挑选结果：按优先级排好的候选 + 两条"退回了"的上报位。
 *
 * @property onlyHardwareAvailable true = 请求软解但本机一个软件解码器都没有，只能拿硬解。
 * @property onlySoftwareAvailable true = 请求硬解但本机一个硬解都没有（全是软解），只能拿软解。
 */
data class CodecSelection(
    val ordered: List<CodecCandidate>,
    val onlyHardwareAvailable: Boolean,
    val onlySoftwareAvailable: Boolean = false,
)

/**
 * 软解优先的候选挑选（ExoPlayerEngine 的 FORCE_SW 路径用它）。
 *
 * 规则：
 * - 有软件解码器 → **只留软件解码器**（真正的「强制软解」，不是 Media3 的 PREFER_SOFTWARE 排序）；
 * - 一个都没有（设备只提供硬解，且本项目未内置 media3 FFmpeg 解码扩展）→ 原样退回默认顺序，
 *   并置 [CodecSelection.onlyHardwareAvailable]，由引擎写进 [DecoderReport]。
 */
object DecoderSelection {

    /**
     * 依据模式与候选列表挑选。
     *
     * - [DecoderMode.AUTO_HW]：不动顺序（Media3 自己就是硬解优先，且允许回退）；
     * - [DecoderMode.FORCE_SW]：只留软件解码器；
     * - [DecoderMode.FORCE_HW]：**只留硬解**——这是「强制硬解」在 Media3 上唯一说得通的落地口径
     *   （Android 没有官方的"只能硬解"开关，能做的是把软件解码器从候选里去掉）。
     */
    fun select(mode: DecoderMode, candidates: List<CodecCandidate>): CodecSelection = when (mode) {
        DecoderMode.FORCE_SW -> {
            val software = candidates.filter { it.softwareOnly }
            if (software.isEmpty()) {
                CodecSelection(candidates, onlyHardwareAvailable = candidates.isNotEmpty())
            } else {
                CodecSelection(software, onlyHardwareAvailable = false)
            }
        }

        DecoderMode.FORCE_HW -> {
            val hardware = candidates.filter { !it.softwareOnly }
            if (hardware.isEmpty()) {
                CodecSelection(candidates, onlyHardwareAvailable = false, onlySoftwareAvailable = candidates.isNotEmpty())
            } else {
                CodecSelection(hardware, onlyHardwareAvailable = false, onlySoftwareAvailable = false)
            }
        }

        DecoderMode.AUTO_HW -> CodecSelection(candidates, onlyHardwareAvailable = false)
    }

    /** 生成面向用户的说明（null = 无需提示）。 */
    fun note(mode: DecoderMode, selection: CodecSelection): String? = when {
        mode.preferSoftwareDecoder && selection.onlyHardwareAvailable ->
            "本机没有可用的软件解码器（未内置 FFmpeg 解码扩展），已退回硬解；如需真软解请切 LibVLC 内核"

        mode == DecoderMode.FORCE_HW && selection.onlySoftwareAvailable ->
            "本机没有可用的硬解解码器，已退回软解；如果画面卡顿，可切到 LibVLC 内核"

        else -> null
    }
}

/**
 * 解码落地报告（R10「做不到就记录并回报」）。
 *
 * @property requested 用户选择的档位。
 * @property applied 实际生效的说明（例如「软件解码器 c2.android.avc.decoder」/「仅硬解可用」）。
 * @property softwareApplied 是否确实走了软件解码路径。
 * @property note 需要展示给用户的提示；null = 与请求一致。
 */
data class DecoderReport(
    val requested: DecoderMode,
    val applied: String,
    val softwareApplied: Boolean,
    val note: String? = null,
)
