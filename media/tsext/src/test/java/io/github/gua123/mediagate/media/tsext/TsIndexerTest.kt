package io.github.gua123.mediagate.media.tsext

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TsIndexer] 的 JVM 单测（R3/R4，plan 4.3 第 1 条）——本轮的核心用例集。
 *
 * 全部用 [TsMuxer] 现场合成 TS：PAT/PMT、周期性 IDR、可控 PTS 与 PCR、坏 CRC、截断尾巴、
 * 非 TS 数据、加扰包、回绕与拼接重置；再验证索引点、seekTarget 精度、取消后续扫一致性与容错。
 */
class TsIndexerTest {

    private fun indexer(bytes: ByteArray, chunkBytes: Int = TsIndexer.DEFAULT_CHUNK_BYTES): TsIndexer =
        TsIndexer(ByteSource(bytes), chunkBytes = chunkBytes)

    private fun completed(updates: List<TsScanUpdate>): TsScanUpdate.Completed =
        updates.filterIsInstance<TsScanUpdate.Completed>().single()

    private fun failed(updates: List<TsScanUpdate>): TsScanUpdate.Failed =
        updates.filterIsInstance<TsScanUpdate.Failed>().single()

    @Test
    fun `扫完单节目 TS 得到完整索引`() = runTest {
        val (bytes, muxer) = TsFixtures.singleProgram(keyframes = 5, framesPerGop = 2, ptsStepMs = 2_000L)
        val result = completed(indexer(bytes).scan().toList())
        assertEquals(5, result.index.keyframeCount)
        assertEquals(TsFixtures.VIDEO_PID, result.index.videoPid)
        assertEquals(TsVideoCodec.H264, result.index.videoCodec)
        assertTrue(result.index.complete)
        assertEquals(bytes.size.toLong(), result.index.scannedBytes)
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 7_000L, 9_000L), result.index.points.map { it.timeMs })
        // 偏移正好是每个 IDR PES 的起点（合成时打过 mark）
        for (index in 0 until 5) {
            assertEquals("kf$index", muxer.marks["kf$index"]!!, result.index.points[index].byteOffset)
        }
        assertEquals(5, result.stats.keyframes)
        assertEquals(0L, result.stats.psiCrcErrors)
        assertTrue("应解析到 PCR：${result.stats.pcrSamples}", result.stats.pcrSamples >= 5)
        assertEquals(9_080L, result.index.durationMs)
    }

    @Test
    fun `索引时间单调不减且偏移严格递增`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 6)
        val points = completed(indexer(bytes).scan().toList()).index.points
        assertTrue(points.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
        assertTrue(points.zipWithNext().all { (a, b) -> b.byteOffset > a.byteOffset })
    }

    @Test
    fun `seekTarget 落在最近的关键帧上`() = runTest {
        val (bytes, muxer) = TsFixtures.singleProgram(keyframes = 4, ptsStepMs = 2_000L)
        val index = completed(indexer(bytes).scan().toList()).index
        // 关键帧时间：1000 / 3000 / 5000 / 7000
        assertEquals(muxer.marks["kf0"]!!, index.seekTarget(1_000L))
        assertEquals(muxer.marks["kf0"]!!, index.seekTarget(2_999L))
        assertEquals(muxer.marks["kf1"]!!, index.seekTarget(3_000L))
        assertEquals(muxer.marks["kf2"]!!, index.seekTarget(6_500L))
        assertEquals(muxer.marks["kf3"]!!, index.seekTarget(99_999L))
        assertEquals(muxer.marks["kf0"]!!, index.seekTarget(0L))
    }

    @Test
    fun `边扫边产出关键帧且每个更新都带可用断点`() = runTest {
        val (bytes, muxer) = TsFixtures.singleProgram(keyframes = 4)
        val updates = indexer(bytes).scan().toList()
        val keyframes = updates.filterIsInstance<TsScanUpdate.Keyframe>()
        assertEquals(4, keyframes.size)
        for ((position, update) in keyframes.withIndex()) {
            assertEquals(muxer.marks["kf$position"]!!, update.point.byteOffset)
            assertEquals(update.point.byteOffset, update.checkpoint.resumeOffset)
            assertEquals(position + 1, update.checkpoint.keyframeCount)
        }
        assertTrue(updates.last() is TsScanUpdate.Completed)
        // 关键帧更新出现在 Completed 之前
        assertTrue(updates.indexOfLast { it is TsScanUpdate.Keyframe } < updates.indexOfLast { it is TsScanUpdate.Completed })
    }

    @Test
    fun `进度流单调不减且末尾为一`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 6)
        val fractions = indexer(bytes, chunkBytes = TS_PACKET_SIZE * 4).progress().toList()
        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1.0, fractions.last(), 1e-9)
    }

    @Test
    fun `读到一半取消后从断点续扫结果与一次扫完一致`() = runTest {
        val (bytes, muxer) = TsFixtures.singleProgram(keyframes = 8, ptsStepMs = 2_000L)
        val full = completed(indexer(bytes).scan().toList()).index
        assertEquals(8, full.keyframeCount)

        // 扫到第 3 个关键帧就停（模拟用户拖拽/离屏导致的中途取消）
        val checkpoints = mutableListOf<TsScanCheckpoint>()
        var keyframesSeen = 0
        indexer(bytes, chunkBytes = TS_PACKET_SIZE * 2)
            .scan()
            .onEach { update ->
                if (update is TsScanUpdate.Keyframe) {
                    keyframesSeen++
                    checkpoints += update.checkpoint
                }
            }
            .takeWhile { !(it is TsScanUpdate.Keyframe && keyframesSeen >= 3) }
            .collect { }
        assertEquals(3, checkpoints.size)
        val checkpoint = checkpoints.last()
        assertEquals(muxer.marks["kf2"]!!, checkpoint.resumeOffset)

        val resumed = completed(indexer(bytes, chunkBytes = TS_PACKET_SIZE * 2).scan(checkpoint).toList()).index
        assertEquals(full.points, resumed.points)
        assertEquals(full.keyframeCount, resumed.keyframeCount)
        assertEquals(full.videoPid, resumed.videoPid)
        assertEquals(full.videoCodec, resumed.videoCodec)
        assertEquals(full.durationMs, resumed.durationMs)
        assertEquals(full.scannedBytes, resumed.scannedBytes)
    }

    @Test
    fun `续扫不会重复断点处的关键帧`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 4)
        val updates = indexer(bytes).scan().toList()
        val checkpoint = updates.filterIsInstance<TsScanUpdate.Keyframe>()[1].checkpoint
        val resumed = completed(indexer(bytes).scan(checkpoint).toList()).index
        val offsets = resumed.points.map { it.byteOffset }
        assertEquals(offsets.distinct(), offsets)
        assertEquals(4, resumed.keyframeCount)
    }

    @Test
    fun `用公开工厂重建的断点也能续扫`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 6)
        val full = completed(indexer(bytes).scan().toList()).index
        // 模拟跨进程恢复：只有「点列表 + 续扫位置」，没有内存里的 PSI/时间轴状态
        val checkpoint = TsScanCheckpoint.of(
            resumeOffset = full.points[2].byteOffset,
            points = full.points.take(3),
        )
        val resumed = completed(indexer(bytes).scan(checkpoint).toList()).index
        assertEquals(full.points, resumed.points)
    }

    @Test
    fun `断点越界时退回从头扫`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 3)
        val full = completed(indexer(bytes).scan().toList()).index
        val checkpoint = TsScanCheckpoint.of(resumeOffset = bytes.size.toLong() + 10_000L, points = emptyList())
        val resumed = completed(indexer(bytes).scan(checkpoint).toList()).index
        assertEquals(full.points, resumed.points)
    }

    @Test
    fun `空文件给出明确错误`() = runTest {
        val result = failed(indexer(ByteArray(0)).scan().toList())
        assertTrue(result.error is TsScanError.EmptyFile)
        assertTrue(result.error.message.contains("空"))
    }

    @Test
    fun `非 TS 文件给出明确错误且不崩`() = runTest {
        val result = failed(indexer(TsFixtures.notTransportStream(20_000)).scan().toList())
        assertTrue("实际：${result.error}", result.error is TsScanError.NotTransportStream)
        assertTrue(result.error.message.contains("0x47"))
        assertEquals(0L, result.stats.packetsScanned)
    }

    @Test
    fun `大块非 TS 数据也会在探测窗口内停下`() = runTest {
        val result = failed(indexer(TsFixtures.notTransportStream(600_000)).scan().toList())
        assertTrue(result.error is TsScanError.NotTransportStream)
        assertTrue(
            "探测应在阈值附近结束：${result.stats.syncErrors}",
            result.stats.syncErrors > 0,
        )
    }

    @Test
    fun `截断的尾部包被忽略但索引仍完整`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 3)
        val truncated = bytes + ByteArray(100) { 0x55 }
        val result = completed(indexer(truncated).scan().toList())
        assertEquals(3, result.index.keyframeCount)
        assertEquals(100, result.stats.truncatedTailBytes)
    }

    @Test
    fun `坏 CRC 的 PAT 导致没有视频节目的明确错误`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 2, corruptPatCrc = true)
        val result = failed(indexer(bytes).scan().toList())
        assertTrue("实际：${result.error}", result.error is TsScanError.NoVideoStream)
        assertTrue(result.stats.psiCrcErrors >= 1)
        assertTrue(result.error.message.contains("CRC"))
    }

    @Test
    fun `坏 CRC 的 PMT 不会崩且给出明确错误`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 2, corruptPmtCrc = true)
        val result = failed(indexer(bytes).scan().toList())
        assertTrue(result.error is TsScanError.NoVideoStream)
        assertTrue(result.stats.psiCrcErrors >= 1)
        assertEquals(0, result.stats.keyframes)
    }

    @Test
    fun `纯音频节目给出没有视频的错误`() = runTest {
        val result = failed(indexer(TsFixtures.audioOnly()).scan().toList())
        assertTrue(result.error is TsScanError.NoVideoStream)
    }

    @Test
    fun `多节目时选中含视频的第二个节目`() = runTest {
        val result = completed(indexer(TsFixtures.audioFirstTwoPrograms()).scan().toList())
        assertEquals(2, result.checkpoint.program!!.programNumber)
        assertEquals(TsFixtures.VIDEO_PID, result.index.videoPid)
        assertEquals(2, result.index.keyframeCount)
        assertEquals(listOf(1_000L, 2_000L), result.index.points.map { it.timeMs })
    }

    @Test
    fun `HEVC 的 IRAP 被当作关键帧`() = runTest {
        val result = completed(indexer(TsFixtures.hevcSingleProgram(keyframes = 3, irapType = 20)).scan().toList())
        assertEquals(TsVideoCodec.H265, result.index.videoCodec)
        assertEquals(3, result.index.keyframeCount)
        assertEquals(listOf(1_000L, 3_000L, 5_000L), result.index.points.map { it.timeMs })
    }

    @Test
    fun `没有 PTS 时用 PCR 兜底记时间`() = runTest {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(TsFixtures.PROGRAM to TsFixtures.PMT_PID)))
        muxer.psi(
            TsFixtures.PMT_PID,
            TsSections.pmt(
                TsFixtures.PROGRAM,
                pcrPid = TsFixtures.VIDEO_PID,
                streams = listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)),
            ),
        )
        muxer.pcrOnly(TsFixtures.VIDEO_PID, baseTicks = 4 * 90_000L)
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, null, H26x.h264(idr = true, fillerBytes = 100))
        muxer.pcrOnly(TsFixtures.VIDEO_PID, baseTicks = 6 * 90_000L)
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, null, H26x.h264(idr = true, fillerBytes = 100))

        val result = completed(indexer(muxer.toByteArray()).scan().toList())
        assertEquals(listOf(4_000L, 6_000L), result.index.points.map { it.timeMs })
        assertEquals(0L, result.stats.pesWithPts)
        assertTrue(result.stats.pcrSamples >= 2)
    }

    @Test
    fun `加扰的视频包不参与关键帧识别`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 3)
        val scrambled = bytes.copyOf()
        var offset = 0
        while (offset + TS_PACKET_SIZE <= scrambled.size) {
            if (TsPackets.pid(scrambled, offset) == TsFixtures.VIDEO_PID) {
                scrambled[offset + 3] = (scrambled[offset + 3].toInt() or 0x80).toByte()
            }
            offset += TS_PACKET_SIZE
        }
        val result = completed(indexer(scrambled).scan().toList())
        assertEquals(0, result.index.keyframeCount)
        assertTrue("应记录加扰包：${result.stats.scrambledPackets}", result.stats.scrambledPackets > 0)
        assertEquals(TsFixtures.VIDEO_PID, result.index.videoPid)
    }

    @Test
    fun `PTS 回绕后索引时间继续递增`() = runTest {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(TsFixtures.PROGRAM to TsFixtures.PMT_PID)))
        muxer.psi(
            TsFixtures.PMT_PID,
            TsSections.pmt(
                TsFixtures.PROGRAM,
                pcrPid = TsFixtures.VIDEO_PID,
                streams = listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)),
            ),
        )
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, PtsTimeline.WRAP_TICKS - 90_000L, H26x.h264(idr = true, fillerBytes = 80))
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, 90_000L, H26x.h264(idr = true, fillerBytes = 80))

        val result = completed(indexer(muxer.toByteArray()).scan().toList())
        assertEquals(2, result.index.keyframeCount)
        assertEquals(95_442_717L, result.index.points[0].timeMs)
        assertEquals(95_444_717L, result.index.points[1].timeMs)
        assertTrue(result.index.points[1].timeMs > result.index.points[0].timeMs)
        assertEquals(1, result.stats.ptsWraps)
    }

    @Test
    fun `拼接流时间重置后索引仍然单调`() = runTest {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(TsFixtures.PROGRAM to TsFixtures.PMT_PID)))
        muxer.psi(
            TsFixtures.PMT_PID,
            TsSections.pmt(
                TsFixtures.PROGRAM,
                pcrPid = TsFixtures.VIDEO_PID,
                streams = listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)),
            ),
        )
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, 10_000L * 90, H26x.h264(idr = true, fillerBytes = 80))
        // 第二段录像的时间戳跳回 1 秒
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, 1_000L * 90, H26x.h264(idr = true, fillerBytes = 80))
        muxer.pes(TsFixtures.VIDEO_PID, 0xE0, 2_000L * 90, H26x.h264(idr = true, fillerBytes = 80))

        val result = completed(indexer(muxer.toByteArray()).scan().toList())
        assertEquals(3, result.index.keyframeCount)
        assertEquals(listOf(10_000L, 10_000L, 11_000L), result.index.points.map { it.timeMs })
        assertEquals(1, result.stats.ptsResets)
        assertTrue(result.index.points.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
    }

    @Test
    fun `读块大小不影响索引结果`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 4)
        val expected = completed(indexer(bytes).scan().toList()).index
        for (chunk in listOf(TS_PACKET_SIZE, TS_PACKET_SIZE * 3, TS_PACKET_SIZE * 7 + 5, 4096)) {
            val actual = completed(indexer(bytes, chunkBytes = chunk).scan().toList()).index
            assertEquals("chunk=$chunk", expected.points, actual.points)
        }
    }

    @Test
    fun `没有关键帧的流也能扫完`() = runTest {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(TsFixtures.PROGRAM to TsFixtures.PMT_PID)))
        muxer.psi(
            TsFixtures.PMT_PID,
            TsSections.pmt(
                TsFixtures.PROGRAM,
                pcrPid = TsFixtures.VIDEO_PID,
                streams = listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)),
            ),
        )
        repeat(4) { muxer.pes(TsFixtures.VIDEO_PID, 0xE0, (1_000L + it * 40) * 90, H26x.h264(idr = false, fillerBytes = 60)) }

        val result = completed(indexer(muxer.toByteArray()).scan().toList())
        assertEquals(0, result.index.keyframeCount)
        assertEquals(-1L, result.index.seekTarget(1_000L))
        assertEquals(0, result.stats.keyframes)
        assertEquals(TsFixtures.VIDEO_PID, result.index.videoPid)
    }

    @Test
    fun `读取失败给出读取错误`() = runTest {
        val result = failed(TsIndexer(FailingSource(size = 4096, failAfter = 0)).scan().toList())
        assertTrue(result.error is TsScanError.ReadFailed)
        assertTrue(result.error.message.contains("模拟远端断开"))
    }

    @Test
    fun `未知长度的源也能扫到末尾`() = runTest {
        val (bytes, _) = TsFixtures.singleProgram(keyframes = 3)
        val result = completed(TsIndexer(ByteSource(bytes, size = -1L)).scan().toList())
        assertEquals(3, result.index.keyframeCount)
    }
}
