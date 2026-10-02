
package io.github.gua123.mediagate.feature.player.video

/**
 * 预览帧的"时间桶"（**2026-10-03 真机修复**：用户反馈「预览图总是显示生成中，显示不出来」）。
 *
 * 拖动调进度时横滑事件密集到"每个像素一次"，而抽一帧要几百毫秒 ⇒ 必须节流：
 * 同一个桶内只取一次，桶变了才重新取。桶大小与 :app 侧预览缓存的 key 保持一致（5 秒）。
 *
 * 纯函数，JVM 单测覆盖（负数一律归到第 0 桶，避免"拖到 0 附近"出现负桶）。
 */
object PreviewBucket {

    /** 一个桶多少毫秒。 */
    const val BUCKET_MS: Long = 5_000L

    /** [positionMs] 落在哪个桶。 */
    fun of(positionMs: Long): Long = if (positionMs <= 0L) 0L else positionMs / BUCKET_MS
}
