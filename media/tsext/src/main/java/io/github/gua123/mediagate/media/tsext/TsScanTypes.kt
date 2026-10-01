package io.github.gua123.mediagate.media.tsext

import io.github.gua123.mediagate.core.common.ErrorText

/**
 * 一次扫描的统计（R3/R4，诊断与单测断言用）。
 *
 * @param packetsScanned 处理过的 188 字节包数（含空包）。
 * @param syncErrors 同步字节不是 0x47、触发重同步的次数。
 * @param psiSections 拼完整的 PSI 段数（PAT+PMT）。
 * @param psiCrcErrors CRC 校验失败被丢弃的段数（R3 容错：坏 CRC 只丢段，不中断扫描）。
 * @param pcrSamples 解析到的 PCR 个数。
 * @param pesWithPts 带 PTS 的视频 PES 个数。
 * @param keyframes 产出的关键帧点数。
 * @param ptsWraps / ptsResets 时间轴回绕 / 拼接重置次数。
 * @param discontinuities 适配域里置了 discontinuity_indicator 的包数。
 * @param scrambledPackets 被加扰（跳过 NAL 扫描）的视频包数。
 * @param truncatedTailBytes 文件尾部不足一个包的字节数（截断包，容忍）。
 */
data class TsScanStats(
    val packetsScanned: Long = 0L,
    val syncErrors: Long = 0L,
    val psiSections: Long = 0L,
    val psiCrcErrors: Long = 0L,
    val pcrSamples: Long = 0L,
    val pesWithPts: Long = 0L,
    val keyframes: Int = 0,
    val ptsWraps: Int = 0,
    val ptsResets: Int = 0,
    val discontinuities: Long = 0L,
    val scrambledPackets: Long = 0L,
    val truncatedTailBytes: Int = 0,
) {
    companion object {
        val EMPTY = TsScanStats()
    }
}

/**
 * 扫描失败的原因（R3 容错口径：**不崩、给明确错误**）。
 *
 * 这些都是「文件本身不适合建索引」，不是程序缺陷；上层据此提示用户（例如「疑似不是 TS 文件，将直接播放」）。
 */
sealed class TsScanError(val message: String) {

    /** 空文件（0 字节）。 */
    class EmptyFile : TsScanError("文件为空，无法建立 TS 索引")

    /** 前若干字节里找不到可信的同步字节（非 TS / 头部有其它封装）。 */
    class NotTransportStream(
        val scannedBytes: Long,
        val prefixHex: String,
    ) : TsScanError("已扫描 $scannedBytes 字节仍未找到连续的 TS 同步字节（0x47），疑似不是 TS 文件（开头：$prefixHex）")

    /** PAT/PMT 解析不出「含视频的节目」。 */
    class NoVideoStream(val detail: String) : TsScanError("没有解析出含视频的节目：$detail")

    /** 读取数据源失败（远端断开等）。 */
    class ReadFailed(val cause: Throwable) :
        TsScanError("读取失败：" + ErrorText.of(cause, "详情见诊断日志"))
}

/**
 * 断点（plan 4.3 第 1 条「可取消、可断点续扫」）。
 *
 * 保存两样东西：**已经扫到哪个字节**（[resumeOffset]）与**已经产出的关键帧点**（[points]），
 * 外加解析出来的节目信息与时间轴状态——恢复时从 [resumeOffset] 接着扫，
 * 结果与一次扫完**逐点一致**（见 [TsIndexer] 的单测）。
 *
 * [resumeOffset] 是「最后一个关键帧所在 PES 包的起始偏移」：从那里续扫最多重复一个 PES，
 * 而重复的那一帧因为偏移不大于已有点而被去重。
 */
class TsScanCheckpoint internal constructor(
    /** 续扫的起始字节偏移（TS 包边界）。 */
    val resumeOffset: Long,
    /** 已产出的关键帧点（不可变链表，快照 O(1)）。 */
    internal val chain: KeyframeChain?,
    /** 已解析的 PAT；断点续扫时用来继续盯 PMT PID。 */
    val pat: PatInfo?,
    /** 已选定的目标节目（多节目取第一个含视频的）。 */
    val program: TsProgramInfo?,
    /** PTS 时间轴状态（回绕/重置补偿）。 */
    val timeline: PtsTimelineState,
    /** PCR 时间轴状态（没有 PTS 时的时间兜底）。 */
    val pcrTimeline: PtsTimelineState,
    /** 目前为止的统计。 */
    val stats: TsScanStats,
) {

    /** 已产出的关键帧点数。 */
    val keyframeCount: Int get() = chain?.size ?: 0

    /** 已产出的关键帧点（按时间/偏移升序）。 */
    val points: List<KeyframePoint> get() = chain?.toList() ?: emptyList()

    /** 最后一个关键帧点；还没有则为 null。 */
    val lastKeyframe: KeyframePoint? get() = chain?.point

    /** 目标视频 PID；还没解析出来为 -1。 */
    val videoPid: Int get() = program?.videoPid ?: -1

    /** 是否还没有任何进度（可直接从头扫）。 */
    val isInitial: Boolean get() = resumeOffset <= 0L && chain == null

    companion object {

        /** 初始断点（等价于「从头扫」）。 */
        val EMPTY: TsScanCheckpoint = TsScanCheckpoint(
            resumeOffset = 0L,
            chain = null,
            pat = null,
            program = null,
            timeline = PtsTimelineState.EMPTY,
            pcrTimeline = PtsTimelineState.EMPTY,
            stats = TsScanStats.EMPTY,
        )

        /**
         * 外部构造（跨进程恢复用）：把已保存的点列表与节目信息重新装成断点。
         *
         * @param resumeOffset 从哪个字节继续（通常 = 最后一个点的 byteOffset）。
         */
        fun of(
            resumeOffset: Long,
            points: List<KeyframePoint>,
            pat: PatInfo? = null,
            program: TsProgramInfo? = null,
            timeline: PtsTimelineState = PtsTimelineState.EMPTY,
            pcrTimeline: PtsTimelineState = PtsTimelineState.EMPTY,
            stats: TsScanStats = TsScanStats.EMPTY,
        ): TsScanCheckpoint {
            var chain: KeyframeChain? = null
            for (point in points) {
                chain = KeyframeChain.of(point, chain)
            }
            return TsScanCheckpoint(resumeOffset, chain, pat, program, timeline, pcrTimeline, stats)
        }
    }
}

/** 内部：不可变的关键帧点链表，让「每个关键帧都拍一次快照」是 O(1)。 */
internal class KeyframeChain(
    val point: KeyframePoint,
    val previous: KeyframeChain?,
    val size: Int,
) {

    fun toList(): List<KeyframePoint> {
        val out = ArrayList<KeyframePoint>(size)
        var node: KeyframeChain? = this
        while (node != null) {
            out.add(node.point)
            node = node.previous
        }
        out.reverse()
        return out
    }

    companion object {
        fun of(point: KeyframePoint, previous: KeyframeChain?): KeyframeChain =
            KeyframeChain(point, previous, (previous?.size ?: 0) + 1)
    }
}

/**
 * 扫描过程中的增量输出（plan 4.3 第 1 条「边扫边用」）。
 *
 * 典型用法：收集 [Keyframe] 即可立刻把新找到的关键帧点喂给进度条；
 * 中途取消后拿最后一个 [Keyframe.checkpoint] 续扫；以 [Completed] 或 [Failed] 结束。
 */
sealed interface TsScanUpdate {

    /** 进度：已扫描字节 / 总字节。 */
    data class Progress(val bytesScanned: Long, val totalBytes: Long) : TsScanUpdate {
        /** 0..1；总长未知时为 0.0（不做假进度）。 */
        val fraction: Double
            get() = if (totalBytes > 0L) (bytesScanned.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0
    }

    /** 又扫到一个关键帧（边扫边用）。 */
    data class Keyframe(val point: KeyframePoint, val checkpoint: TsScanCheckpoint) : TsScanUpdate

    /** 扫到文件尾：完整索引。 */
    data class Completed(
        val index: TsIndex,
        val checkpoint: TsScanCheckpoint,
        val stats: TsScanStats,
    ) : TsScanUpdate

    /** 扫描失败（明确原因，见 [TsScanError]）。 */
    data class Failed(val error: TsScanError, val stats: TsScanStats) : TsScanUpdate
}
