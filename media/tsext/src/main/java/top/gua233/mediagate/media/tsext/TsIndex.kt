package io.github.gua123.mediagate.media.tsext

/**
 * 一个关键帧点：**时间 ↔ 字节偏移**（R3/R4，plan 4.3 第 1 条）。
 *
 * @param timeMs 该关键帧的播放时间（毫秒）；由 PES 的 PTS 经 [PtsTimeline] 映射（单调不减）。
 * @param byteOffset 该帧所在 PES 包在文件里的**起始字节偏移**（TS 包边界，可直接 seek 过去）。
 */
data class KeyframePoint(val timeMs: Long, val byteOffset: Long) : Comparable<KeyframePoint> {

    override fun compareTo(other: KeyframePoint): Int {
        val byTime = timeMs.compareTo(other.timeMs)
        return if (byTime != 0) byTime else byteOffset.compareTo(other.byteOffset)
    }
}

/**
 * TS 索引表（R3/R4，plan 4.3 第 1 条）：拖拽时「毫秒级」找到最近关键帧的字节偏移。
 *
 * 构造即校验：时间**单调不减**、偏移**严格递增**且非负——索引一旦乱序，二分查找与拖拽都会错位，
 * 所以宁可在构造时抛 [IllegalArgumentException]，也不要带着坏数据上线。
 *
 * @param points 关键帧点（按时间升序）。
 * @param videoPid 目标视频 PID；未知为 -1。
 * @param videoCodec 视频编码；未知为 [TsVideoCodec.OTHER]。
 * @param durationMs 时长（最后一个关键帧/最后一个 PTS 的时间）；未知为 null。
 * @param scannedBytes 已扫描到的字节数（= 文件长度表示扫到文件尾）。
 * @param complete 是否扫到文件尾（false = 断点续扫的中间产物）。
 */
data class TsIndex(
    val points: List<KeyframePoint>,
    val videoPid: Int = -1,
    val videoCodec: TsVideoCodec = TsVideoCodec.OTHER,
    val durationMs: Long? = null,
    val scannedBytes: Long = 0L,
    val complete: Boolean = true,
) {

    init {
        var previousTime = Long.MIN_VALUE
        var previousOffset = -1L
        for (index in points.indices) {
            val point = points[index]
            require(point.timeMs >= previousTime) {
                "关键帧时间必须单调不减：第 $index 个 ${point.timeMs} < $previousTime"
            }
            require(point.byteOffset > previousOffset) {
                "关键帧偏移必须严格递增：第 $index 个 ${point.byteOffset} <= $previousOffset"
            }
            previousTime = point.timeMs
            previousOffset = point.byteOffset
        }
    }

    /** 关键帧数量。 */
    val keyframeCount: Int get() = points.size

    /** 是否空索引（没扫到任何关键帧）。 */
    val isEmpty: Boolean get() = points.isEmpty()

    /** 第一个关键帧；空索引为 null。 */
    val firstPoint: KeyframePoint? get() = points.firstOrNull()

    /** 最后一个关键帧；空索引为 null。 */
    val lastPoint: KeyframePoint? get() = points.lastOrNull()

    /**
     * 不晚于 [timeMs] 的最后一个关键帧（拖拽定位的默认目标：从这里往前解码即可）。
     *
     * @return 没有这样的点（早于第一帧）时返回 null。
     */
    fun nearestKeyframeBefore(timeMs: Long): KeyframePoint? {
        if (points.isEmpty()) return null
        var low = 0
        var high = points.size - 1
        var result = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (points[middle].timeMs <= timeMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return if (result < 0) null else points[result]
    }

    /**
     * 不早于 [timeMs] 的第一个关键帧。
     *
     * @return 没有这样的点（晚于最后一帧）时返回 null。
     */
    fun nearestKeyframeAfter(timeMs: Long): KeyframePoint? {
        if (points.isEmpty()) return null
        var low = 0
        var high = points.size - 1
        var result = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (points[middle].timeMs >= timeMs) {
                result = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return if (result < 0) null else points[result]
    }

    /** 时间上离 [timeMs] 最近的关键帧（前后等距时取前一个）；空索引为 null。 */
    fun nearestKeyframe(timeMs: Long): KeyframePoint? {
        val before = nearestKeyframeBefore(timeMs) ?: return nearestKeyframeAfter(timeMs)
        val after = nearestKeyframeAfter(timeMs) ?: return before
        return if (timeMs - before.timeMs <= after.timeMs - timeMs) before else after
    }

    /**
     * 拖拽落点（R4）：返回应当 seek 到的**字节偏移**。
     *
     * 语义 = [nearestKeyframeBefore]，早于第一帧时退化到第一帧（否则无点可跳）。
     *
     * @return 空索引返回 -1（调用方据此走「先扫索引」或全量播放）。
     */
    fun seekTarget(timeMs: Long): Long =
        (nearestKeyframeBefore(timeMs) ?: firstPoint)?.byteOffset ?: -1L

    /** 拖拽落点的完整信息（时间 + 偏移）；空索引为 null。 */
    fun seekPoint(timeMs: Long): KeyframePoint? = nearestKeyframeBefore(timeMs) ?: firstPoint

    /** 取 `[fromMs, toMs]` 区间内的关键帧（含边界），用于分段预取/进度条打点。 */
    fun slice(fromMs: Long, toMs: Long): List<KeyframePoint> {
        if (points.isEmpty() || toMs < fromMs) return emptyList()
        val first = nearestKeyframeAfter(fromMs) ?: return emptyList()
        val startIndex = points.indexOf(first)
        if (startIndex < 0) return emptyList()
        var endIndex = startIndex
        while (endIndex < points.size && points[endIndex].timeMs <= toMs) endIndex++
        return points.subList(startIndex, endIndex).toList()
    }

    /** 紧凑二进制（见 [TsIndexCodec]；索引缓存落盘用）。 */
    fun toBytes(): ByteArray = TsIndexCodec.encode(this)

    override fun toString(): String =
        "TsIndex(points=${points.size}, videoPid=$videoPid, codec=$videoCodec, " +
            "durationMs=$durationMs, scannedBytes=$scannedBytes, complete=$complete)"

    companion object {

        /** 空索引（未扫 / 无关键帧）。 */
        val EMPTY: TsIndex = TsIndex(points = emptyList())

        /** 从二进制还原；格式不符/校验失败返回 null（不抛异常）。 */
        fun fromBytes(bytes: ByteArray): TsIndex? = TsIndexCodec.decode(bytes)
    }
}
