package io.github.gua123.mediagate.media.tsext

import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * JVM 单测用的 TS 合成器（R3/R4）。
 *
 * 目标：**不依赖任何真实样本**就能造出「PAT/PMT + 可控 PTS + 周期性 IDR」的 TS 流，
 * 于是解析、索引、断点续扫、容错都能在纯 JVM 上断言。
 *
 * 注意：PES 头与 33 位时间戳的编码是**独立实现**（不复用主源码里的解析器），
 * 避免「同一处写错，测试与实现一起错」。
 */
internal class TsMuxer {

    private val out = ByteArrayOutputStream()

    private val continuity = HashMap<Int, Int>()

    /** 有名字的字节位置（例如每个关键帧 PES 的起点），用于断言索引里的 byteOffset。 */
    val marks = LinkedHashMap<String, Long>()

    /** 当前已写入的字节数。 */
    val size: Long get() = out.size().toLong()

    fun mark(name: String): TsMuxer {
        marks[name] = size
        return this
    }

    /** 写一个 PSI 段（自动分包：首包带 pointer_field）。 */
    fun psi(pid: Int, section: ByteArray): TsMuxer {
        var offset = 0
        var first = true
        while (offset < section.size) {
            val capacity = if (first) TS_PACKET_SIZE - 5 else TS_PACKET_SIZE - 4
            val chunk = minOf(capacity, section.size - offset)
            val payload = ByteArray(if (first) chunk + 1 else chunk)
            if (first) payload[0] = 0
            System.arraycopy(section, offset, payload, if (first) 1 else 0, chunk)
            writePacket(pid, payloadUnitStart = first, payload = payload, adaptation = null)
            offset += chunk
            first = false
        }
        return this
    }

    /** 写一个视频/音频 PES（自动按 184 字节分包）。 */
    fun pes(
        pid: Int,
        streamId: Int,
        ptsTicks: Long?,
        payload: ByteArray,
        dtsTicks: Long? = null,
    ): TsMuxer {
        val bytes = pesHeader(streamId, ptsTicks, dtsTicks, payload.size) + payload
        var offset = 0
        var first = true
        while (offset < bytes.size) {
            val chunk = minOf(TS_PACKET_SIZE - 4, bytes.size - offset)
            writePacket(pid, payloadUnitStart = first, payload = bytes.copyOfRange(offset, offset + chunk), adaptation = null)
            offset += chunk
            first = false
        }
        return this
    }

    /** 写一个只带 PCR 的适配域包（afc=2，余下用 0xFF 填充）。 */
    fun pcrOnly(pid: Int, baseTicks: Long, extension: Int = 0, discontinuity: Boolean = false): TsMuxer {
        val adaptation = ByteArray(TS_PACKET_SIZE - 4)
        adaptation[0] = (TS_PACKET_SIZE - 5).toByte() // adaptation_field_length
        adaptation[1] = ((if (discontinuity) 0x80 else 0) or 0x10).toByte() // discontinuity + PCR_flag
        val ticks = baseTicks and ((1L shl 33) - 1)
        adaptation[2] = ((ticks ushr 25) and 0xFF).toByte()
        adaptation[3] = ((ticks ushr 17) and 0xFF).toByte()
        adaptation[4] = ((ticks ushr 9) and 0xFF).toByte()
        adaptation[5] = ((ticks ushr 1) and 0xFF).toByte()
        adaptation[6] = ((((ticks and 0x01) shl 7) or (((extension ushr 8) and 0x01).toLong()))).toByte()
        adaptation[7] = (extension and 0xFF).toByte()
        for (index in 8 until adaptation.size) adaptation[index] = 0xFF.toByte()
        writePacket(pid, payloadUnitStart = false, payload = ByteArray(0), adaptation = adaptation, adaptationOnly = true)
        return this
    }

    /** 写一个原始包（用于注入坏同步字节 / 畸形数据）。 */
    fun raw(bytes: ByteArray): TsMuxer {
        out.write(bytes)
        return this
    }

    /** 写任意 188 字节包（用于构造 afc=0 之类的畸形包）。 */
    fun malformedPacket(pid: Int, adaptationFieldControl: Int): TsMuxer {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = ((pid ushr 8) and 0x1F).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = ((adaptationFieldControl shl 4) or 0x05).toByte()
        out.write(packet)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writePacket(
        pid: Int,
        payloadUnitStart: Boolean,
        payload: ByteArray,
        adaptation: ByteArray?,
        adaptationOnly: Boolean = false,
    ) {
        val counter = continuity.getOrDefault(pid, 0)
        continuity[pid] = (counter + 1) and 0x0F
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = ((if (payloadUnitStart) 0x40 else 0x00) or ((pid ushr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        val control = when {
            adaptation == null -> 1
            adaptationOnly -> 2
            else -> 3
        }
        packet[3] = ((control shl 4) or counter).toByte()
        var cursor = 4
        if (adaptation != null) {
            System.arraycopy(adaptation, 0, packet, cursor, adaptation.size)
            cursor += adaptation.size
        }
        val space = TS_PACKET_SIZE - cursor
        val copied = minOf(space, payload.size)
        if (copied > 0) System.arraycopy(payload, 0, packet, cursor, copied)
        out.write(packet)
    }

    private fun pesHeader(streamId: Int, pts: Long?, dts: Long?, payloadLength: Int): ByteArray {
        val headerDataLength = when {
            pts != null && dts != null -> 10
            pts != null -> 5
            else -> 0
        }
        val total = 3 + headerDataLength + payloadLength
        val header = ByteArrayOutputStream(9 + headerDataLength)
        header.write(byteArrayOf(0x00, 0x00, 0x01, streamId.toByte()))
        header.write((total ushr 8) and 0xFF)
        header.write(total and 0xFF)
        header.write(0x80) // '10' + 其余标志位为 0
        header.write(
            when {
                pts != null && dts != null -> 0xC0
                pts != null -> 0x80
                else -> 0x00
            },
        )
        header.write(headerDataLength)
        if (pts != null) header.write(encodeTimestamp(if (dts != null) 0x3 else 0x2, pts))
        if (dts != null) header.write(encodeTimestamp(0x1, dts))
        return header.toByteArray()
    }

    /** 独立的 33 位时间戳编码（PTS/DTS）。 */
    fun encodeTimestamp(prefix: Int, ticks: Long): ByteArray {
        val value = ticks and ((1L shl 33) - 1)
        return byteArrayOf(
            ((prefix shl 4) or (((value ushr 30) and 0x07).toInt() shl 1) or 0x01).toByte(),
            ((value ushr 22) and 0xFF).toByte(),
            (((((value ushr 15) and 0x7F).toInt()) shl 1) or 0x01).toByte(),
            ((value ushr 7) and 0xFF).toByte(),
            ((((value and 0x7F).toInt()) shl 1) or 0x01).toByte(),
        )
    }
}

/** PMT 里的一条基本流描述（测试用）。 */
internal data class PmtStreamSpec(val streamType: Int, val pid: Int, val esInfoLength: Int = 0)

/** PAT/PMT 段合成（测试用；CRC 用主源码的 [MpegCrc32]，它另有标准向量单测钉住）。 */
internal object TsSections {

    fun pat(
        programs: List<Pair<Int, Int>>,
        transportStreamId: Int = 1,
        networkPid: Int? = null,
        version: Int = 0,
        corruptCrc: Boolean = false,
    ): ByteArray {
        val entries = (if (networkPid != null) listOf(0 to networkPid) else emptyList()) + programs
        val sectionLength = 5 + entries.size * 4 + 4
        val body = ByteArrayOutputStream()
        body.write(TsTablesIds.PAT)
        body.write(0xB0 or ((sectionLength ushr 8) and 0x0F))
        body.write(sectionLength and 0xFF)
        body.write((transportStreamId ushr 8) and 0xFF)
        body.write(transportStreamId and 0xFF)
        body.write(0xC0 or ((version and 0x1F) shl 1) or 0x01)
        body.write(0x00)
        body.write(0x00)
        for ((program, pid) in entries) {
            body.write((program ushr 8) and 0xFF)
            body.write(program and 0xFF)
            body.write(0xE0 or ((pid ushr 8) and 0x1F))
            body.write(pid and 0xFF)
        }
        return appendCrc(body.toByteArray(), corruptCrc)
    }

    fun pmt(
        programNumber: Int,
        pcrPid: Int,
        streams: List<PmtStreamSpec>,
        version: Int = 0,
        programInfoLength: Int = 0,
        corruptCrc: Boolean = false,
    ): ByteArray {
        val streamsBytes = streams.sumOf { 5 + it.esInfoLength }
        val sectionLength = 9 + programInfoLength + streamsBytes + 4
        val body = ByteArrayOutputStream()
        body.write(TsTablesIds.PMT)
        body.write(0xB0 or ((sectionLength ushr 8) and 0x0F))
        body.write(sectionLength and 0xFF)
        body.write((programNumber ushr 8) and 0xFF)
        body.write(programNumber and 0xFF)
        body.write(0xC0 or ((version and 0x1F) shl 1) or 0x01)
        body.write(0x00)
        body.write(0x00)
        body.write(0xE0 or ((pcrPid ushr 8) and 0x1F))
        body.write(pcrPid and 0xFF)
        body.write(0xF0 or ((programInfoLength ushr 8) and 0x0F))
        body.write(programInfoLength and 0xFF)
        repeat(programInfoLength) { body.write(0x00) }
        for (stream in streams) {
            body.write(stream.streamType and 0xFF)
            body.write(0xE0 or ((stream.pid ushr 8) and 0x1F))
            body.write(stream.pid and 0xFF)
            body.write(0xF0 or ((stream.esInfoLength ushr 8) and 0x0F))
            body.write(stream.esInfoLength and 0xFF)
            repeat(stream.esInfoLength) { body.write(0x00) }
        }
        return appendCrc(body.toByteArray(), corruptCrc)
    }

    private fun appendCrc(body: ByteArray, corrupt: Boolean): ByteArray {
        val crc = MpegCrc32.compute(body)
        var bytes = byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
        if (corrupt) {
            bytes = bytes.copyOf()
            bytes[3] = (bytes[3].toInt() xor 0xFF).toByte()
        }
        return body + bytes
    }

    private object TsTablesIds {
        const val PAT = 0x00
        const val PMT = 0x02
    }
}

/** H.264/H.265 访问单元合成（测试用）。 */
internal object H26x {

    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun h264(idr: Boolean, sps: Boolean = true, aud: Boolean = true, fillerBytes: Int = 32): ByteArray {
        val out = ByteArrayOutputStream()
        if (aud) out.write(nal(0x09, 2))
        if (sps) {
            out.write(nal(0x07, 4))
            out.write(nal(0x08, 4))
        }
        out.write(nal(if (idr) 0x65 else 0x41, fillerBytes))
        return out.toByteArray()
    }

    fun h265(irapType: Int?, vps: Boolean = true, fillerBytes: Int = 32): ByteArray {
        val out = ByteArrayOutputStream()
        if (vps) {
            out.write(nal265(32, 4))
            out.write(nal265(33, 4))
            out.write(nal265(34, 4))
        }
        out.write(nal265(irapType ?: 1, fillerBytes))
        return out.toByteArray()
    }

    /** H.264 NAL：起手码 + 头字节（低 5 位是类型）。 */
    private fun nal(type: Int, payloadBytes: Int): ByteArray =
        START_CODE + byteArrayOf(type.toByte()) + ByteArray(payloadBytes) { 0x11 }

    /** H.265 NAL：起手码 + 2 字节头（第一字节高 6 位是类型）。 */
    private fun nal265(type: Int, payloadBytes: Int): ByteArray =
        START_CODE + byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + ByteArray(payloadBytes) { 0x22 }
}

/** 内存 [RandomAccessSource]（纯 JVM）。 */
internal class ByteSource(
    private val data: ByteArray,
    override val size: Long = data.size.toLong(),
) : RandomAccessSource {

    var closed: Boolean = false
        private set

    override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset < 0 || offset >= data.size) return -1
        val count = minOf(len.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buf, off, count)
        return count
    }

    override suspend fun readFully(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= data.size) return ByteArray(0)
        val count = minOf(len.toLong(), data.size - offset).toInt()
        return data.copyOfRange(offset.toInt(), offset.toInt() + count)
    }

    override fun close() {
        closed = true
    }
}

/** 读一半就失败的源（用于 [TsScanError.ReadFailed]）。 */
internal class FailingSource(override val size: Long = 4096L, private val failAfter: Int = 1) : RandomAccessSource {

    private var reads = 0

    override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = throw IOException("模拟远端断开")

    override suspend fun readFully(offset: Long, len: Int): ByteArray {
        reads++
        if (reads > failAfter) throw IOException("模拟远端断开")
        return ByteArray(minOf(len.toLong(), size).toInt()) { 0x47 }
    }

    override fun close() = Unit
}

/** 索引/解析测试的常用 TS 样本。 */
internal object TsFixtures {

    const val PROGRAM = 1
    const val PMT_PID = 0x1000
    const val VIDEO_PID = 0x0100
    const val AUDIO_PID = 0x0101
    const val H264 = 0x1B
    const val AAC = 0x0F

    /**
     * 单节目 H.264 TS：每个 GOP 一个 IDR + 若干非 IDR 帧，PTS 按 [ptsStepMs] 递增。
     *
     * 关键帧 i 的时间 = `ptsStartMs + i * ptsStepMs`，字节偏移记在 `kf$i` mark 上。
     */
    fun singleProgram(
        keyframes: Int = 4,
        framesPerGop: Int = 2,
        ptsStartMs: Long = 1_000L,
        ptsStepMs: Long = 2_000L,
        payloadBytes: Int = 300,
        withPcr: Boolean = true,
        corruptPatCrc: Boolean = false,
        corruptPmtCrc: Boolean = false,
        withAudio: Boolean = true,
        psiEveryGop: Boolean = true,
    ): Pair<ByteArray, TsMuxer> {
        val muxer = TsMuxer()
        val streams = buildList {
            add(PmtStreamSpec(H264, VIDEO_PID))
            if (withAudio) add(PmtStreamSpec(AAC, AUDIO_PID))
        }
        fun writePsi() {
            muxer.psi(0, TsSections.pat(listOf(PROGRAM to PMT_PID), corruptCrc = corruptPatCrc))
            muxer.psi(
                PMT_PID,
                TsSections.pmt(PROGRAM, pcrPid = VIDEO_PID, streams = streams, corruptCrc = corruptPmtCrc),
            )
        }
        writePsi()
        var ptsMs = ptsStartMs
        for (keyframe in 0 until keyframes) {
            // 真实 TS 每个 GOP 都会重复 PAT/PMT，断点续扫要靠它重新发现节目
            if (psiEveryGop && keyframe > 0) writePsi()
            if (withPcr) muxer.pcrOnly(VIDEO_PID, baseTicks = ptsMs * 90)
            muxer.mark("kf$keyframe")
            muxer.pes(VIDEO_PID, 0xE0, ptsMs * 90, H26x.h264(idr = true, fillerBytes = payloadBytes))
            if (withAudio) muxer.pes(AUDIO_PID, 0xC0, ptsMs * 90, ByteArray(payloadBytes) { 0x33 })
            for (frame in 1..framesPerGop) {
                val frameMs = ptsMs + frame * 40L
                muxer.pes(VIDEO_PID, 0xE0, frameMs * 90, H26x.h264(idr = false, fillerBytes = payloadBytes / 2))
            }
            ptsMs += ptsStepMs
        }
        return muxer.toByteArray() to muxer
    }

    /** HEVC 单节目样本（IRAP 类型可调）。 */
    fun hevcSingleProgram(keyframes: Int = 3, irapType: Int = 19, ptsStepMs: Long = 2_000L): ByteArray {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(PROGRAM to PMT_PID)))
        muxer.psi(PMT_PID, TsSections.pmt(PROGRAM, pcrPid = VIDEO_PID, streams = listOf(PmtStreamSpec(0x24, VIDEO_PID))))
        var ptsMs = 1_000L
        for (keyframe in 0 until keyframes) {
            muxer.pcrOnly(VIDEO_PID, baseTicks = ptsMs * 90)
            muxer.pes(VIDEO_PID, 0xE0, ptsMs * 90, H26x.h265(irapType = irapType, fillerBytes = 200))
            muxer.pes(VIDEO_PID, 0xE0, (ptsMs + 40) * 90, H26x.h265(irapType = null, fillerBytes = 100))
            ptsMs += ptsStepMs
        }
        return muxer.toByteArray()
    }

    /** 多节目样本：节目 1 只有音频，节目 2 含视频（用于验证「取第一个含视频的节目」）。 */
    fun audioFirstTwoPrograms(): ByteArray {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(1 to 0x1000, 2 to 0x1001)))
        muxer.psi(0x1000, TsSections.pmt(1, pcrPid = 0x0201, streams = listOf(PmtStreamSpec(AAC, 0x0201))))
        muxer.psi(0x1001, TsSections.pmt(2, pcrPid = VIDEO_PID, streams = listOf(PmtStreamSpec(H264, VIDEO_PID))))
        muxer.pcrOnly(VIDEO_PID, baseTicks = 90_000L)
        muxer.pes(VIDEO_PID, 0xE0, 90_000L, H26x.h264(idr = true, fillerBytes = 200))
        muxer.pes(VIDEO_PID, 0xE0, 180_000L, H26x.h264(idr = true, fillerBytes = 200))
        return muxer.toByteArray()
    }

    /** 纯音频样本（没有含视频的节目）。 */
    fun audioOnly(): ByteArray {
        val muxer = TsMuxer()
        muxer.psi(0, TsSections.pat(listOf(PROGRAM to PMT_PID)))
        muxer.psi(PMT_PID, TsSections.pmt(PROGRAM, pcrPid = AUDIO_PID, streams = listOf(PmtStreamSpec(AAC, AUDIO_PID))))
        muxer.pes(AUDIO_PID, 0xC0, 90_000L, ByteArray(200) { 0x44 })
        return muxer.toByteArray()
    }

    /** 非 TS 数据（伪随机，避免恰好出现 0x47 连续）。 */
    fun notTransportStream(bytes: Int = 20_000): ByteArray = ByteArray(bytes) { index -> ((index * 7 + 13) % 251).toByte() }
}
