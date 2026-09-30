package io.github.gua123.mediagate.media.engine

/**
 * 解码器候选的纯逻辑挑选（R10 软解），把 Media3 的 `MediaCodecInfo` 抽成纯数据以便 JVM 单测。
 *
 * @property name 解码器名（`c2.android.avc.decoder` / `OMX.qcom.video.decoder.hevc` …）。
 * @property softwareOnly 是否纯软件解码器（Media3 的 `MediaCodecInfo.softwareOnly`）。
 */
data class CodecCandidate(val name: String, val softwareOnly: Boolean)

/** 挑选结果：按优先级排好的候选 + 是否只能退回硬解。 */
data class CodecSelection(
    val ordered: List<CodecCandidate>,
    /** true = 一个软件解码器都没有，只能拿硬解（此时按 R10「做不到就记录并回报」上报）。 */
    val onlyHardwareAvailable: Boolean,
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

    /** 依据模式与候选列表挑选；[DecoderMode.AUTO_HW] / [DecoderMode.FORCE_HW] 不改顺序。 */
    fun select(mode: DecoderMode, candidates: List<CodecCandidate>): CodecSelection = when {
        !mode.preferSoftwareDecoder -> CodecSelection(candidates, onlyHardwareAvailable = false)
        else -> {
            val software = candidates.filter { it.softwareOnly }
            if (software.isEmpty()) {
                CodecSelection(candidates, onlyHardwareAvailable = candidates.isNotEmpty())
            } else {
                CodecSelection(software, onlyHardwareAvailable = false)
            }
        }
    }

    /** 生成面向用户的说明（null = 无需提示）。 */
    fun note(mode: DecoderMode, selection: CodecSelection): String? = when {
        mode.preferSoftwareDecoder && selection.onlyHardwareAvailable ->
            "本机没有可用的软件解码器（未内置 FFmpeg 解码扩展），已退回硬解；如需真软解请切 LibVLC 内核"
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
