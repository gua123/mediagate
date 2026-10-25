package io.github.gua123.mediagate.app

import android.media.MediaCodecList

/**
 * **常见视频编码的硬解覆盖自述**（2026-10-25 用户要求「全部采用硬解，但要保证常见视频类型可解析」）。
 *
 * 只做"本机有什么"的事实陈述，不做开关：用户已明确不要按编码器切软/硬。
 * 目的：把「H.264/HEVC/VP9/AV1/MPEG-4/MPEG-2/H.263 各自有没有硬解」摆进诊断卡，
 * 真机截图就能看出是设备缺硬解，还是取数/播放链路的问题。
 *
 * 注意：MediaCodecList 只说明**解码器存在**，不代表每个文件都能解；实际是否硬解仍由
 * Media3 在播放时根据 MediaCodec 的选择决定（[io.github.gua123.mediagate.media.engine.DecoderReport] 会写实际结果）。
 */
object CodecCoverage {

    private val common: List<Pair<String, String>> = listOf(
        "H.264" to "video/avc",
        "HEVC" to "video/hevc",
        "VP9" to "video/x-vnd.on2.vp9",
        "AV1" to "video/av01",
        "MPEG-4" to "video/mp4v-es",
        "MPEG-2" to "video/mpeg2",
        "H.263" to "video/3gpp",
    )

    /** 一行中文自述；任何异常都退回"读取失败"，不影响播放。 */
    fun summary(): String {
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrElse { return "解码器覆盖：读取失败" }
        val parts = common.map { (label, mime) ->
            val decoders = codecs.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            val hardware = decoders.count { it.isHardwareAccelerated }
            val software = decoders.size - hardware
            if (decoders.isEmpty()) {
                label + " 无"
            } else if (hardware == 0) {
                label + " 硬解 0 / 软解 " + software + " ⚠"
            } else {
                label + " 硬解 " + hardware + " / 软解 " + software
            }
        }
        return "解码器覆盖：" + parts.joinToString(" · ")
    }
}
