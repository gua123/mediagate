package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TsIndex] / [TsIndexCodec] 的 JVM 单测（R3/R4）：seekTarget 精度、边界、单调性校验与二进制往返。
 */
class TsIndexTest {

    private val points = listOf(
        KeyframePoint(0L, 0L),
        KeyframePoint(2_000L, 1_000L),
        KeyframePoint(4_000L, 2_500L),
        KeyframePoint(6_000L, 4_000L),
    )

    private val index = TsIndex(
        points = points,
        videoPid = TsFixtures.VIDEO_PID,
        videoCodec = TsVideoCodec.H264,
        durationMs = 6_000L,
        scannedBytes = 9_000L,
    )

    @Test
    fun `seekTarget 命中关键帧本身`() {
        assertEquals(0L, index.seekTarget(0L))
        assertEquals(1_000L, index.seekTarget(2_000L))
        assertEquals(2_500L, index.seekTarget(4_000L))
        assertEquals(4_000L, index.seekTarget(6_000L))
    }

    @Test
    fun `seekTarget 落在两帧之间取前一个关键帧`() {
        assertEquals(1_000L, index.seekTarget(2_001L))
        assertEquals(1_000L, index.seekTarget(3_999L))
        assertEquals(2_500L, index.seekTarget(4_001L))
        assertEquals(2_500L, index.seekTarget(5_999L))
    }

    @Test
    fun `seekTarget 早于第一帧退化到第一帧`() {
        assertEquals(0L, index.seekTarget(-1L))
        assertEquals(0L, index.seekTarget(0L))
    }

    @Test
    fun `seekTarget 晚于最后一帧取最后一帧`() {
        assertEquals(4_000L, index.seekTarget(Long.MAX_VALUE))
        assertEquals(4_000L, index.seekTarget(6_001L))
    }

    @Test
    fun `空索引查询全部返回 null 或负一`() {
        val empty = TsIndex.EMPTY
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.keyframeCount)
        assertEquals(-1L, empty.seekTarget(1_000L))
        assertNull(empty.seekPoint(1_000L))
        assertNull(empty.nearestKeyframeBefore(1_000L))
        assertNull(empty.nearestKeyframeAfter(1_000L))
        assertNull(empty.nearestKeyframe(1_000L))
        assertNull(empty.firstPoint)
        assertNull(empty.lastPoint)
        assertTrue(empty.slice(0L, 10_000L).isEmpty())
    }

    @Test
    fun `nearestKeyframeBefore 与 After 的边界`() {
        val before = TsIndex(listOf(KeyframePoint(1_000L, 10L), KeyframePoint(3_000L, 30L)))
        assertNull(before.nearestKeyframeBefore(999L))
        assertEquals(1_000L, before.nearestKeyframeBefore(1_000L)!!.timeMs)
        assertEquals(1_000L, before.nearestKeyframeBefore(2_999L)!!.timeMs)
        assertEquals(3_000L, before.nearestKeyframeBefore(9_999L)!!.timeMs)
        assertNull(before.nearestKeyframeAfter(3_001L))
        assertEquals(3_000L, before.nearestKeyframeAfter(3_000L)!!.timeMs)
        assertEquals(3_000L, before.nearestKeyframeAfter(2_999L)!!.timeMs)
        assertEquals(1_000L, before.nearestKeyframeAfter(-5L)!!.timeMs)
    }

    @Test
    fun `nearestKeyframe 取时间上更近的一个`() {
        // 关键帧时间：0 / 2000 / 4000 / 6000
        assertEquals(0L, index.nearestKeyframe(900L)!!.timeMs)
        assertEquals(2_000L, index.nearestKeyframe(1_600L)!!.timeMs)
        assertEquals(4_000L, index.nearestKeyframe(3_100L)!!.timeMs)
        // 等距时取前一个
        assertEquals(0L, index.nearestKeyframe(1_000L)!!.timeMs)
        assertEquals(2_000L, index.nearestKeyframe(3_000L)!!.timeMs)
    }

    @Test
    fun `单点索引任意时间都落到该点`() {
        val single = TsIndex(listOf(KeyframePoint(5_000L, 777L)))
        assertEquals(777L, single.seekTarget(0L))
        assertEquals(777L, single.seekTarget(5_000L))
        assertEquals(777L, single.seekTarget(99_999L))
    }

    @Test
    fun `时间倒退的索引构造失败`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TsIndex(listOf(KeyframePoint(2_000L, 0L), KeyframePoint(1_000L, 1_000L)))
        }
        assertTrue(error.message!!.contains("单调"))
    }

    @Test
    fun `偏移不递增的索引构造失败`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TsIndex(listOf(KeyframePoint(0L, 500L), KeyframePoint(1_000L, 500L)))
        }
        assertTrue(error.message!!.contains("递增"))
        assertThrows(IllegalArgumentException::class.java) {
            TsIndex(listOf(KeyframePoint(0L, -1L)))
        }
    }

    @Test
    fun `slice 取闭区间`() {
        assertEquals(listOf(points[1], points[2]), index.slice(2_000L, 4_000L))
        assertEquals(listOf(points[0]), index.slice(0L, 0L))
        assertTrue(index.slice(6_001L, 9_000L).isEmpty())
        assertTrue(index.slice(5_000L, 1_000L).isEmpty())
    }

    @Test
    fun `二进制往返保持一致`() {
        val restored = TsIndex.fromBytes(index.toBytes())!!
        assertEquals(index, restored)
        assertEquals(index.points, restored.points)
        assertEquals(index.videoPid, restored.videoPid)
        assertEquals(index.videoCodec, restored.videoCodec)
        assertEquals(index.durationMs, restored.durationMs)
        assertEquals(index.scannedBytes, restored.scannedBytes)
        assertTrue(restored.complete)
        assertEquals(TsIndexCodec.sizeOf(index.points.size), index.toBytes().size)
    }

    @Test
    fun `中间产物的 complete 标志被保留`() {
        val partial = TsIndex(points.take(2), videoPid = 0x0100, complete = false, scannedBytes = 1_234L)
        val restored = TsIndex.fromBytes(partial.toBytes())!!
        assertFalse(restored.complete)
        assertEquals(1_234L, restored.scannedBytes)
        assertEquals(2, restored.keyframeCount)
    }

    @Test
    fun `空索引与未知时长也能往返`() {
        val empty = TsIndex(emptyList(), videoPid = -1, videoCodec = TsVideoCodec.OTHER, durationMs = null)
        val restored = TsIndex.fromBytes(empty.toBytes())!!
        assertEquals(empty, restored)
        assertNull(restored.durationMs)
    }

    @Test
    fun `损坏的二进制返回 null 而不是崩溃`() {
        val bytes = index.toBytes()
        assertNull(TsIndex.fromBytes(ByteArray(0)))
        assertNull(TsIndex.fromBytes(bytes.copyOf(bytes.size - 2)))
        assertNull(TsIndex.fromBytes(ByteArray(bytes.size)))
        // 改 CRC
        val badCrc = bytes.copyOf()
        badCrc[badCrc.size - 1] = (badCrc[badCrc.size - 1].toInt() xor 0xFF).toByte()
        assertNull(TsIndex.fromBytes(badCrc))
        // 改点数（放大到离谱）
        val badCount = bytes.copyOf()
        badCount[TsIndexCodec.HEADER_BYTES - 1] = 0x7F
        assertNull(TsIndex.fromBytes(badCount))
        // 改内容但 CRC 同时被改对的情况不会出现；这里只保证不抛异常
        assertNull(TsIndex.fromBytes(bytes.copyOf().also { it[6] = 0x7F }))
    }

    @Test
    fun `magic 判定`() {
        assertTrue(TsIndexCodec.looksLikeIndex(index.toBytes()))
        assertFalse(TsIndexCodec.looksLikeIndex(ByteArray(64)))
        assertFalse(TsIndexCodec.looksLikeIndex(index.toBytes().copyOf(4)))
    }

    @Test
    fun `toString 不展开全部点`() {
        val text = index.toString()
        assertTrue(text.contains("points=4"))
        assertFalse(text.contains("KeyframePoint(timeMs=2000"))
    }
}
