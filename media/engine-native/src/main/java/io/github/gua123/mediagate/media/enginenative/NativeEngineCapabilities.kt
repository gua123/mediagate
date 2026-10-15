package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.media.engine.EngineCapabilities
import io.github.gua123.mediagate.media.engine.ResizeMode

/**
 * 自研引擎**如实声明**的能力（M4）。
 *
 * 放在独立的顶层对象里（而不是挂在 PlayerEngine 实现上）有两个好处：
 * 一是不用造一个 Android 环境就能在 JVM 单测里断言"我们承诺了什么"；
 * 二是**过渡期界面可以直接读它**，在切换之前就告诉用户哪一档不支持，而不是切过去发现点了没反应。
 *
 * 现状（2026-10-15）：
 * - 倍速 ✗：native 还没有时间伸缩（改倍速必须同时改音频与时钟，M5）；
 * - 缩放四档 ✓：FIT/CROP/STRETCH/ORIGINAL 都在 native 的送屏那一步算（ResizeMode 的 ordinal 直接传进去）；
 * - 音量 >100% ✓：走软件增益（会削波；Media3 那边是借系统 LoudnessEnhancer 补动态）；
 * - 解码档位 ✗：目前只有 FFmpeg 软解一条路，MediaCodec 硬解在 M5。
 *
 * 注意"外挂字幕"不在这个矩阵里 —— 字幕由 **App 的 Compose 覆盖层**渲染（读 positionMs），
 * 与内核无关，所以换内核不影响字幕。
 */
object NativeEngineCapabilities {

    val VALUE: EngineCapabilities = EngineCapabilities(
        speed = false,
        resizeModes = ResizeMode.entries.toSet(),
        volumeBoost = true,
        decoderModes = false,
    )
}
