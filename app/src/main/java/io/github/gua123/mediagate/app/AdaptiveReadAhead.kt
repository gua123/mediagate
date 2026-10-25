package io.github.gua123.mediagate.app

/**
 * 自适应预读的纯函数（**2026-10-25 用户拍板：缓存按推荐方案 A 改造**）。
 *
 * 为什么要自适应：固定抓 4 段（16 MB）在高速链路上很舒服，但在弱网/频繁 seek 时会
 * 与播放抢带宽（用户明确要求"预取必须给播放让路"）。这里按最近吞吐与 seek 状态
 * 把一次滑窗抓取的段数往下收，**用户设置的值是上限**，自适应只能收缩、不能越权放大。
 *
 * 阈值取整数 MB，避免"用某个测速结果调参"；后续可用真实 SFTP 数据再调整。
 */
object AdaptiveReadAhead {

    /** 低于 512 KiB/s 视为弱网：一次只抓 1 段，够读就行。 */
    const val LOW_BYTES_PER_SEC: Long = 512L * 1024L

    /** 512 KiB/s – 2 MiB/s：抓 2 段。 */
    const val MID_BYTES_PER_SEC: Long = 2L * 1024L * 1024L

    /** 2 – 8 MiB/s：抓 3 段；再高才用满用户上限。 */
    const val HIGH_BYTES_PER_SEC: Long = 8L * 1024L * 1024L

    /**
     * 本次滑窗预读取几段。
     *
     * @param throughputBytesPerSec 最近一次成功下载的吞吐（0 = 还没有样本 ⇒ 按用户上限，保持旧行为）。
     * @param seekedRecently 刚刚发生过明显 seek（前进太多或回拖）⇒ 先少抓，避免在新落点上狂下。
     * @param capSegments 用户设置的预读段数上限（0 = 关闭预读）。
     */
    fun segments(
        throughputBytesPerSec: Long,
        seekedRecently: Boolean,
        capSegments: Int,
    ): Int {
        if (capSegments <= 0) return 0
        if (seekedRecently) return minOf(2, capSegments)
        if (throughputBytesPerSec <= 0L) return capSegments
        return when {
            throughputBytesPerSec < LOW_BYTES_PER_SEC -> 1
            throughputBytesPerSec < MID_BYTES_PER_SEC -> 2
            throughputBytesPerSec < HIGH_BYTES_PER_SEC -> 3
            else -> capSegments
        }.coerceAtMost(capSegments)
    }
}
