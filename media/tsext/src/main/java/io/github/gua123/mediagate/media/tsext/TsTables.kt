package io.github.gua123.mediagate.media.tsext

/**
 * 视频编码类型（R3/R4）：由 PMT 的 stream_type 推出，决定关键帧怎么扫。
 */
enum class TsVideoCodec(val displayName: String) {

    /** H.264/AVC（stream_type 0x1B），关键帧 = NAL type 5（IDR）。 */
    H264("H.264/AVC"),

    /** H.265/HEVC（stream_type 0x24），关键帧 = NAL type 16..23（IRAP，含 19/20/21）。 */
    H265("H.265/HEVC"),

    /** MPEG-2 Video（stream_type 0x02）：本轮不做帧内图扫描，只按 PES 头建点。 */
    MPEG2("MPEG-2 Video"),

    /** MPEG-4 Part 2（stream_type 0x10）：同上。 */
    MPEG4("MPEG-4 Video"),

    /** 其它视频编码（AVS/VC-1/…）：能定位 PID，但不做关键帧识别。 */
    OTHER("其它视频");

    /** 是否能做 IDR/IRAP 关键帧识别（plan 4.3 第 1 条）。 */
    val supportsKeyframeScan: Boolean get() = this == H264 || this == H265

    companion object {

        /** stream_type → 编码类型。 */
        fun ofStreamType(streamType: Int): TsVideoCodec = when (streamType) {
            TsStreamTypes.AVC_VIDEO -> H264
            TsStreamTypes.HEVC_VIDEO,
            TsStreamTypes.HEVC_TEMPORAL_VIDEO,
            TsStreamTypes.HEVC_TEMPORAL_SUBSET,
            -> H265
            TsStreamTypes.MPEG2_VIDEO, TsStreamTypes.MPEG1_VIDEO -> MPEG2
            TsStreamTypes.MPEG4_VIDEO -> MPEG4
            else -> OTHER
        }
    }
}

/**
 * PMT 的 stream_type 取值（ISO/IEC 13818-1 Table 2-34 常用子集，R3）。
 */
object TsStreamTypes {

    const val MPEG1_VIDEO = 0x01
    const val MPEG2_VIDEO = 0x02
    const val MPEG1_AUDIO = 0x03
    const val MPEG2_AUDIO = 0x04
    const val PRIVATE_DATA = 0x06
    const val AAC_AUDIO = 0x0F
    const val MPEG4_VIDEO = 0x10
    const val AAC_LATM_AUDIO = 0x11
    const val AVC_VIDEO = 0x1B
    const val HEVC_VIDEO = 0x24
    const val HEVC_TEMPORAL_VIDEO = 0x25
    const val HEVC_TEMPORAL_SUBSET = 0x26
    const val AVS_VIDEO = 0x42
    const val AC3_AUDIO = 0x81
    const val DTS_AUDIO = 0x8A
    const val EAC3_AUDIO = 0x87
    const val VC1_VIDEO = 0xEA

    /** 视频 stream_type 集合。 */
    val VIDEO_TYPES: Set<Int> = setOf(
        MPEG1_VIDEO, MPEG2_VIDEO, MPEG4_VIDEO, AVC_VIDEO, HEVC_VIDEO,
        HEVC_TEMPORAL_VIDEO, HEVC_TEMPORAL_SUBSET, AVS_VIDEO, VC1_VIDEO,
    )

    /**
     * 音频 stream_type 集合。
     *
     * 0x06（private data）在 DVB 里常用于 AC-3/DTS，故按音频计入；真正判定音轨仍看 PID 与播放器。
     */
    val AUDIO_TYPES: Set<Int> = setOf(
        MPEG1_AUDIO, MPEG2_AUDIO, AAC_AUDIO, AAC_LATM_AUDIO,
        AC3_AUDIO, DTS_AUDIO, EAC3_AUDIO, PRIVATE_DATA,
    )

    fun isVideo(streamType: Int): Boolean = streamType in VIDEO_TYPES

    fun isAudio(streamType: Int): Boolean = streamType in AUDIO_TYPES

    fun codecOf(streamType: Int): TsVideoCodec = TsVideoCodec.ofStreamType(streamType)
}

/** PAT 里的一条节目：节目号 + PMT PID。 */
data class PatProgram(val programNumber: Int, val pid: Int)

/**
 * PAT（table_id 0x00）解析结果（R3，plan 4.3 第 1 条）。
 *
 * @param networkPid program_number = 0 的 NIT PID；没有则为 null。
 */
data class PatInfo(
    val transportStreamId: Int,
    val versionNumber: Int,
    val networkPid: Int?,
    val programs: List<PatProgram>,
) {
    val isEmpty: Boolean get() = programs.isEmpty()
}

/** PMT 里的一条基本流。 */
data class PmtStream(val pid: Int, val streamType: Int) {

    val isVideo: Boolean get() = TsStreamTypes.isVideo(streamType)

    val isAudio: Boolean get() = TsStreamTypes.isAudio(streamType)

    val videoCodec: TsVideoCodec get() = TsStreamTypes.codecOf(streamType)
}

/** PMT（table_id 0x02）解析结果（R3）。 */
data class PmtInfo(
    val programNumber: Int,
    val versionNumber: Int,
    val pcrPid: Int,
    val streams: List<PmtStream>,
) {

    /** 按 PMT 声明顺序的视频流。 */
    val videoStreams: List<PmtStream> get() = streams.filter { it.isVideo }

    /** 第一条视频流；纯音频节目返回 null。 */
    fun firstVideoStream(): PmtStream? = streams.firstOrNull { it.isVideo }

    /** 音频 PID 列表（ASR 取音/字幕会用）。 */
    val audioPids: List<Int> get() = streams.filter { it.isAudio }.map { it.pid }
}

/**
 * 「这个 TS 里的目标视频节目」的全部信息（R3/R4）：索引器与上层都用它。
 *
 * 多节目时按 plan 4.3 的口径取**第一个含视频的节目**（见 [TsProgramSelector.select]）。
 */
data class TsProgramInfo(
    val programNumber: Int,
    val pmtPid: Int,
    val pcrPid: Int,
    val videoPid: Int,
    val videoCodec: TsVideoCodec,
    val audioPids: List<Int>,
    val streams: List<PmtStream>,
)

/** PAT 段解析（table_id 0x00）。纯函数，JVM 可测。 */
object PatParser {

    const val TABLE_ID = 0x00

    /**
     * 解析一个完整的 PAT 段。
     *
     * @param validateCrc 是否校验末 4 字节 CRC；**默认校验**——坏 CRC 的段必须丢弃（R3 容错），
     *   只有测试构造畸形样本时才关掉。
     * @return null 表示不是 PAT / 长度不合法 / CRC 不符。
     */
    fun parse(section: ByteArray, validateCrc: Boolean = true): PatInfo? {
        if (section.size < MIN_PAT_BYTES) return null
        if ((section[0].toInt() and 0xFF) != TABLE_ID) return null
        val total = sectionTotalLength(section)
        if (total < MIN_PAT_BYTES || total > section.size) return null
        if (validateCrc && !MpegCrc32.validateSection(section, 0, total)) return null
        val transportStreamId = readUInt16(section, 3)
        val version = (section[5].toInt() ushr 1) and 0x1F
        val programs = ArrayList<PatProgram>(4)
        var networkPid: Int? = null
        var index = 8
        while (index + 4 <= total - 4) {
            val programNumber = readUInt16(section, index)
            val pid = ((section[index + 2].toInt() and 0x1F) shl 8) or (section[index + 3].toInt() and 0xFF)
            if (programNumber == 0) {
                networkPid = pid
            } else {
                programs += PatProgram(programNumber, pid)
            }
            index += 4
        }
        return PatInfo(transportStreamId, version, networkPid, programs)
    }

    /** PAT 段最少字节数：3 段头 + 2 tsid + 1 version + 1 section_number + 1 last + 4 CRC。 */
    const val MIN_PAT_BYTES: Int = 12
}

/** PMT 段解析（table_id 0x02）。纯函数，JVM 可测。 */
object PmtParser {

    const val TABLE_ID = 0x02

    /**
     * 解析一个完整的 PMT 段（含 PCR_PID 与全部基本流）。
     *
     * @return null 表示不是 PMT / 长度不合法 / CRC 不符。
     */
    fun parse(section: ByteArray, validateCrc: Boolean = true): PmtInfo? {
        if (section.size < MIN_PMT_BYTES) return null
        if ((section[0].toInt() and 0xFF) != TABLE_ID) return null
        val total = sectionTotalLength(section)
        if (total < MIN_PMT_BYTES || total > section.size) return null
        if (validateCrc && !MpegCrc32.validateSection(section, 0, total)) return null
        val programNumber = readUInt16(section, 3)
        val version = (section[5].toInt() ushr 1) and 0x1F
        val pcrPid = ((section[8].toInt() and 0x1F) shl 8) or (section[9].toInt() and 0xFF)
        val programInfoLength = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        val streams = ArrayList<PmtStream>(4)
        var index = 12 + programInfoLength
        while (index + 5 <= total - 4) {
            val streamType = section[index].toInt() and 0xFF
            val pid = ((section[index + 1].toInt() and 0x1F) shl 8) or (section[index + 2].toInt() and 0xFF)
            val esInfoLength = ((section[index + 3].toInt() and 0x0F) shl 8) or (section[index + 4].toInt() and 0xFF)
            streams += PmtStream(pid, streamType)
            index += 5 + esInfoLength
        }
        return PmtInfo(programNumber, version, pcrPid, streams)
    }

    /** PMT 段最少字节数：3 段头 + 2 节目号 + 1 version + 1 section_number + 1 last + 2 PCR_PID + 2 program_info_length + 4 CRC。 */
    const val MIN_PMT_BYTES: Int = 16
}

/**
 * 从 PAT + 已解析的 PMT 里挑出目标节目（R3，plan 4.3 第 1 条「多节目：取第一个含视频的节目」）。
 */
object TsProgramSelector {

    /**
     * 按 PAT 里节目的**声明顺序**依次查看 PMT，返回第一个含视频流的节目；都不含视频返回 null。
     *
     * @param pmtOf 按 PMT PID 取已解析的 PMT；还没扫到该 PMT 时返回 null（跳过，不报错）。
     */
    fun select(pat: PatInfo, pmtOf: (Int) -> PmtInfo?): TsProgramInfo? {
        for (program in pat.programs) {
            val pmt = pmtOf(program.pid) ?: continue
            val video = pmt.firstVideoStream() ?: continue
            return TsProgramInfo(
                programNumber = program.programNumber,
                pmtPid = program.pid,
                pcrPid = pmt.pcrPid,
                videoPid = video.pid,
                videoCodec = video.videoCodec,
                audioPids = pmt.audioPids,
                streams = pmt.streams,
            )
        }
        return null
    }
}

/** 段声明的总长度（含 3 字节段头）；长度字段非法时返回 -1。 */
internal fun sectionTotalLength(section: ByteArray): Int {
    if (section.size < PsiSectionAssembler.SECTION_HEADER_BYTES) return -1
    val declared = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
    if (declared == 0) return -1
    return PsiSectionAssembler.SECTION_HEADER_BYTES + declared
}

internal fun readUInt16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
