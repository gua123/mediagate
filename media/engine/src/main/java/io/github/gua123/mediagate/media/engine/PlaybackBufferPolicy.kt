package io.github.gua123.mediagate.media.engine

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * **播放缓冲时间**（2026-10-20，借鉴 nPlayer 的 buffer_time 设置）。
 *
 * 用户能感觉到的是两件事：**起播前等多久**、**网速波动时卡不卡**。
 * Media3 默认只在起播前攒 2.5 秒、最多缓 50 秒；nPlayer 默认 5 秒、允许 1–30 秒可调。
 * 这里把「缓冲几秒」翻成 Media3 的 LoadControl 三个参数：
 * - bufferForPlaybackMs：起播前至少攒够多少；
 * - bufferForPlaybackAfterRebufferMs：卡顿后恢复前至少攒够多少（给一样大，避免刚恢复又卡）；
 * - maxBufferMs：最多提前缓冲多少（给 8 倍，兼顾流量与抗抖动）。
 *
 * 纯函数、可单测；真正构造播放器在 [ExoPlayerEngine] 里。
 */
@UnstableApi
object PlaybackBufferPolicy {

    /** 默认 5 秒（与 nPlayer 默认一致，用户最不容易觉得「等太久」）。 */
    const val DEFAULT_SECONDS: Int = 5

    /** 可选档位（设置页给这几个 chip）。 */
    val SECONDS_CHOICES: List<Int> = listOf(3, 5, 10, 20, 30)

    /** 下限：再小起播就会频繁卡（3 秒已经比较激进）。 */
    const val MIN_SECONDS: Int = 3

    /** 上限：再大起播等待明显、流量也浪费。 */
    const val MAX_SECONDS: Int = 30

    /** 夹到合法区间；坏值退回默认（旧版本残留/手改文件都不该让播放页崩）。 */
    fun clampSeconds(raw: Int): Int = if (raw in MIN_SECONDS..MAX_SECONDS) raw else DEFAULT_SECONDS

    /**
     * 按秒数造 Media3 的 LoadControl。
     *
     * Media3 1.11 的参数顺序是（最小缓冲, 最大缓冲, 起播前缓冲, 卡顿恢复前缓冲），都用毫秒；
     * 这里让「最小缓冲」等于目标（Media3 要求它不小于起播/恢复缓冲），
     * 最大取目标的 8 倍（60–300 秒之间）：目标是「起播等几秒」，上限负责抗抖动。
     */
    fun loadControl(seconds: Int): DefaultLoadControl {
        val ms = clampSeconds(seconds) * 1000L
        // minBuffer 必须 >= bufferForPlaybackMs（Media3 的硬校验）；这里直接用目标值
        val minBuffer = ms
        val maxBuffer = (ms * 8).coerceIn(60_000L, 300_000L)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer.toInt(), maxBuffer.toInt(), ms.toInt(), ms.toInt())
            .build()
    }
}
