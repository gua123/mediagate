package io.github.gua123.mediagate.media.tsext

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource

/**
 * TS 索引构建器（R3/R4，plan 4.3 第 1 条）：扫 PAT/PMT 定位视频 PID，
 * 再扫 PES 头的 PTS/DTS + IDR/IRAP + PCR，产出「时间 ↔ 字节偏移」表。
 *
 * 行为对齐 plan：
 * - **边扫边用**：每找到一个关键帧就发一个 [TsScanUpdate.Keyframe]（带断点），进度用 [TsScanUpdate.Progress]；
 * - **可取消**：流随时可取消（协程取消即停，不留后台线程）；
 * - **可断点续扫**：取消时保存最后一个 [TsScanUpdate.Keyframe.checkpoint]，把它喂回 [scan] 即可接着扫，
 *   结果与一次扫完**逐点一致**（偏移/时间/顺序都一样）；
 * - **容错**：非 TS 文件、截断包、坏 CRC、加扰包都不崩——坏段丢弃并计数，尾部不足一包忽略，
 *   彻底不是 TS 时给出 [TsScanError]。
 *
 * 读放大说明：本类只做**顺序读**（每次一块、块长是 188 的整数倍），随机读能力留给播放器；
 * 读块在 [io] 上执行（[flowOn]），不阻塞调用方线程。
 *
 * @param source 随机访问源（[io.github.gua123.mediagate.data.storage.api.StorageBackend.openRead] 的适配产物）。
 * @param chunkBytes 读块大小；内部会规整成 188 的整数倍（默认 192 KiB ≈ 1024 个包）。
 * @param notTransportStreamProbeBytes 连续多少字节找不到同步字节就判定「不是 TS」（默认 376 KiB）。
 */
class TsIndexer(
    private val source: RandomAccessSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val notTransportStreamProbeBytes: Long = DEFAULT_NOT_TS_PROBE_BYTES,
) {

    /** 规整后的读块（188 的整数倍，保证不会把包读成两半）。 */
    private val chunkSize: Int = (chunkBytes - chunkBytes % TS_PACKET_SIZE).coerceAtLeast(TS_PACKET_SIZE)

    /**
     * 扫描并产出增量更新。
     *
     * @param resumeFrom 断点；null 表示从头扫。传上一次取消时拿到的 checkpoint 即可续扫。
     */
    fun scan(resumeFrom: TsScanCheckpoint? = null): Flow<TsScanUpdate> = flow {
        val total = source.size
        if (total == 0L) {
            emit(TsScanUpdate.Failed(TsScanError.EmptyFile(), TsScanStats.EMPTY))
            return@flow
        }
        val requestedStart = (resumeFrom?.resumeOffset ?: 0L).coerceAtLeast(0L)
        if (total > 0L && total < TS_PACKET_SIZE) {
            val prefix = readPrefix(0L)
            emit(
                TsScanUpdate.Failed(
                    TsScanError.NotTransportStream(total, hexOf(prefix)),
                    TsScanStats(truncatedTailBytes = total.toInt()),
                ),
            )
            return@flow
        }
        val state = ScanState(resumeFrom)
        val stats = MutableStats()
        stats.truncatedTailBytes = if (total > 0L) (total % TS_PACKET_SIZE).toInt() else 0
        // 断点越界（文件变小/被替换）时退回从头扫，绝不读到文件外
        var readOffset = if (total > 0L && requestedStart >= total) 0L else requestedStart
        val buffer = ByteArray(chunkSize)
        var bytesWithoutSync = 0L
        var prefix: ByteArray? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val want = if (total > 0L) minOf(buffer.size.toLong(), total - readOffset).toInt() else buffer.size
            if (want < TS_PACKET_SIZE) break
            val data = try {
                source.readFully(readOffset, want)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "TS 索引扫描读取失败：offset=$readOffset want=$want", t)
                emit(TsScanUpdate.Failed(TsScanError.ReadFailed(t), stats.snapshot(state)))
                return@flow
            }
            if (data.isEmpty()) break
            if (prefix == null) prefix = data.copyOf(minOf(PREFIX_BYTES, data.size))

            var index = 0
            var packetsInChunk = 0
            while (index + TS_PACKET_SIZE <= data.size) {
                if (!TsPackets.isSyncByte(data[index])) {
                    stats.syncErrors++
                    val next = TsPackets.findNextSync(data, index + 1, data.size)
                    if (next < 0) {
                        index = data.size
                        break
                    }
                    index = next
                    continue
                }
                packetsInChunk++
                processPacket(state, stats, data, index, readOffset + index) { update -> emit(update) }
                index += TS_PACKET_SIZE
            }
            readOffset += index
            if (packetsInChunk == 0) {
                bytesWithoutSync += data.size
                if (bytesWithoutSync >= notTransportStreamProbeBytes) {
                    emit(
                        TsScanUpdate.Failed(
                            TsScanError.NotTransportStream(readOffset, hexOf(prefix.toByteArrayOrEmpty())),
                            stats.snapshot(state),
                        ),
                    )
                    return@flow
                }
            } else {
                bytesWithoutSync = 0L
            }
            emit(TsScanUpdate.Progress(readOffset, total))
            if (data.size < want) break
        }

        // 一个包都没认出来：不是 TS（短文件也会走到这里，不必等满探测窗口）
        if (stats.packetsScanned == 0L) {
            emit(
                TsScanUpdate.Failed(
                    TsScanError.NotTransportStream(readOffset, hexOf(prefix.toByteArrayOrEmpty())),
                    stats.snapshot(state),
                ),
            )
            return@flow
        }

        val program = state.program
        if (program == null) {
            // 一个完整的 PSI 段都没拼出来、又有大量同步错误 → 更像「根本不是 TS」而不是「TS 里没有视频」
            val error = if (stats.psiSections == 0L && stats.syncErrors > 0L) {
                TsScanError.NotTransportStream(readOffset, hexOf(prefix.toByteArrayOrEmpty()))
            } else {
                TsScanError.NoVideoStream(noVideoDetail(stats))
            }
            emit(TsScanUpdate.Failed(error, stats.snapshot(state)))
            return@flow
        }
        val points = state.chain?.toList() ?: emptyList()
        val lastPesTime = state.lastPesTimeMs ?: 0L
        val durationMs = maxOf(lastPesTime, points.lastOrNull()?.timeMs ?: 0L).takeIf { it > 0L }
        val index = TsIndex(
            points = points,
            videoPid = program.videoPid,
            videoCodec = program.videoCodec,
            durationMs = durationMs,
            scannedBytes = if (total > 0L) total else readOffset,
            complete = true,
        )
        val checkpoint = state.checkpoint(index.lastPoint?.byteOffset ?: 0L, stats.snapshot(state))
        emit(TsScanUpdate.Progress(index.scannedBytes, total))
        emit(TsScanUpdate.Completed(index, checkpoint, stats.snapshot(state)))
    }.flowOn(io)

    /**
     * 只要进度的便捷流（0..1，总长未知时恒为 0.0）。
     *
     * 需要关键帧点时用 [scan]（本方法只是它的投影）。
     */
    fun progress(resumeFrom: TsScanCheckpoint? = null): Flow<Double> =
        scan(resumeFrom).filterIsInstance<TsScanUpdate.Progress>().map { it.fraction }

    // ------------------------------------------------------------------ 包处理

    private suspend fun processPacket(
        state: ScanState,
        stats: MutableStats,
        data: ByteArray,
        offset: Int,
        absoluteOffset: Long,
        emit: suspend (TsScanUpdate) -> Unit,
    ) {
        stats.packetsScanned++
        if (TsPackets.transportErrorIndicator(data, offset)) return
        val pid = TsPackets.pid(data, offset)

        // PCR：节目解析出来之前任何 PID 都收，之后只认 PCR PID（plan 4.3 第 1 条）
        if (TsPackets.hasAdaptationField(data, offset)) {
            if (AdaptationField.discontinuityIndicator(data, offset)) stats.discontinuities++
            val pcrPid = state.pcrPid
            if (pcrPid < 0 || pid == pcrPid) {
                val pcr = AdaptationField.pcr(data, offset)
                if (pcr != null) {
                    stats.pcrSamples++
                    state.lastPcrMs = state.pcrTimeline.map(pcr.baseTicks)
                }
            }
        }
        if (pid == TsPackets.PID_NULL) return

        val payloadUnitStart = TsPackets.payloadUnitStartIndicator(data, offset)
        val payloadOffset = TsPackets.payloadOffset(data, offset)
        if (payloadOffset < 0) return
        val payloadLength = TsPackets.payloadLength(data, offset)
        if (payloadLength <= 0) return

        // PAT
        if (pid == TsPackets.PID_PAT) {
            for (section in state.patAssembler.feed(data, payloadOffset, payloadLength, payloadUnitStart)) {
                stats.psiSections++
                val pat = PatParser.parse(section)
                if (pat == null) {
                    stats.psiCrcErrors++
                    continue
                }
                state.onPat(pat)
            }
            return
        }

        // PMT（PAT 里列出的、还没解析出来的 PID）
        if (state.isPmtPid(pid)) {
            val assembler = state.pmtAssemblers.getOrPut(pid) { PsiSectionAssembler() }
            for (section in assembler.feed(data, payloadOffset, payloadLength, payloadUnitStart)) {
                stats.psiSections++
                val pmt = PmtParser.parse(section)
                if (pmt == null) {
                    stats.psiCrcErrors++
                    continue
                }
                state.pmtByPid[pid] = pmt
                state.trySelectProgram()
            }
            return
        }

        // 视频流
        val program = state.program ?: return
        if (pid != program.videoPid) return
        if (TsPackets.scramblingControl(data, offset) != 0) {
            stats.scrambledPackets++
            return
        }
        if (payloadUnitStart) {
            val pes = PesParser.parse(data, payloadOffset, minOf(payloadLength, data.size - payloadOffset))
            state.currentPesOffset = absoluteOffset
            val pts = pes?.ptsTicks
            state.currentPesTimeMs = if (pts != null) {
                stats.pesWithPts++
                state.timeline.map(pts).also { state.lastPesTimeMs = it }
            } else {
                state.lastPcrMs ?: state.currentPesTimeMs
            }
        }
        if (state.currentPesOffset < 0L) return
        val scannable = program.videoCodec.supportsKeyframeScan
        val keyframeHere = if (scannable) {
            KeyframeDetector.isKeyframe(program.videoCodec, data, payloadOffset, payloadLength)
        } else {
            // 非 H.264/H.265 没有 IDR/IRAP 可认：退化为「每个 PES 起点」，用 1 s 间距压住点数
            payloadUnitStart
        }
        if (!keyframeHere) return
        val timeMs = state.currentPesTimeMs ?: state.lastPcrMs ?: return
        addKeyframe(state, stats, timeMs, state.currentPesOffset, spaced = !scannable, emit = emit)
    }

    /** 记一个关键帧点：去重、保单调、发更新、拍 O(1) 断点。 */
    private suspend fun addKeyframe(
        state: ScanState,
        stats: MutableStats,
        timeMs: Long,
        byteOffset: Long,
        spaced: Boolean,
        emit: suspend (TsScanUpdate) -> Unit,
    ) {
        val last = state.chain?.point
        if (last != null && byteOffset <= last.byteOffset) return
        val monotonic = if (last != null && timeMs < last.timeMs) last.timeMs else timeMs
        if (spaced && last != null && monotonic - last.timeMs < FALLBACK_KEYFRAME_SPACING_MS) return
        val point = KeyframePoint(monotonic, byteOffset)
        state.chain = KeyframeChain.of(point, state.chain)
        stats.keyframes++
        emit(TsScanUpdate.Keyframe(point, state.checkpoint(byteOffset, stats.snapshot(state))))
    }

    private suspend fun readPrefix(offset: Long): ByteArray = try {
        source.readFully(offset, PREFIX_BYTES)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        ByteArray(0)
    }

    private fun noVideoDetail(stats: MutableStats): String =
        "已扫描 ${stats.packetsScanned} 个包，PAT/PMT 段 ${stats.psiSections} 个" +
            (if (stats.psiCrcErrors > 0) "（其中 CRC 校验失败 ${stats.psiCrcErrors} 个）" else "")

    /** null → 空数组（错误信息展示用）。 */
    private fun ByteArray?.toByteArrayOrEmpty(): ByteArray = this ?: ByteArray(0)

    private fun hexOf(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "（无）"
        val builder = StringBuilder(bytes.size * 3)
        for ((index, byte) in bytes.withIndex()) {
            if (index > 0) builder.append(' ')
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    companion object {

        private const val TAG = "tsext"

        /** 默认读块：192 KiB = 1024 个 TS 包。 */
        const val DEFAULT_CHUNK_BYTES: Int = TS_PACKET_SIZE * 1024

        /** 默认「不是 TS」判定阈值：376 KiB（≈ 2048 个包）。 */
        const val DEFAULT_NOT_TS_PROBE_BYTES: Long = TS_PACKET_SIZE * 2048L

        /** 非 H.264/H.265 流的兜底打点间距（1 秒）。 */
        const val FALLBACK_KEYFRAME_SPACING_MS: Long = 1_000L

        /** 错误信息里展示的前导字节数。 */
        const val PREFIX_BYTES: Int = 16

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}

/** 扫描过程中的可变态（外部看不到，只在一次 [TsIndexer.scan] 里用）。 */
internal class ScanState(checkpoint: TsScanCheckpoint?) {

    var pat: PatInfo? = checkpoint?.pat

    var program: TsProgramInfo? = checkpoint?.program

    val pmtByPid: HashMap<Int, PmtInfo> = HashMap(4)

    val pmtAssemblers: HashMap<Int, PsiSectionAssembler> = HashMap(4)

    val patAssembler: PsiSectionAssembler = PsiSectionAssembler()

    val timeline: PtsTimeline = checkpoint?.timeline?.let(PtsTimeline::restore) ?: PtsTimeline()

    val pcrTimeline: PtsTimeline = checkpoint?.pcrTimeline?.let(PtsTimeline::restore) ?: PtsTimeline()

    /** 已产出的关键帧点（不可变链表）。 */
    var chain: KeyframeChain? = checkpoint?.chain

    /** 最近一次 PCR 映射出的毫秒（PES 没有 PTS 时的兜底时间）。 */
    var lastPcrMs: Long? = null

    /** 当前视频 PES 的起始偏移；还没遇到 PUSI 时为 -1。 */
    var currentPesOffset: Long = -1L

    /** 当前视频 PES 的起始时间；未知为 null。 */
    var currentPesTimeMs: Long? = null

    /** 最近一次 PTS 映射出的毫秒（时长估算用）。 */
    var lastPesTimeMs: Long? = null

    /** 目标 PCR PID；还没解析出节目为 -1（任何 PID 的 PCR 都收）。 */
    val pcrPid: Int get() = program?.pcrPid ?: -1

    /** 记录新 PAT：清掉旧的 PMT 缓存，但保留已选定的节目（避免 PAT 版本更新时出现空窗）。 */
    fun onPat(pat: PatInfo) {
        this.pat = pat
        pmtByPid.clear()
        pmtAssemblers.clear()
    }

    /** [pid] 是否是我们还在等的 PMT PID。 */
    fun isPmtPid(pid: Int): Boolean =
        pat?.programs?.any { it.pid == pid } == true && !pmtByPid.containsKey(pid)

    /** 首次选出「含视频的节目」（多节目取第一个）；已选定则原样返回。 */
    fun trySelectProgram(): TsProgramInfo? {
        program?.let { return it }
        val currentPat = pat ?: return null
        val selected = TsProgramSelector.select(currentPat) { pmtByPid[it] }
        if (selected != null) program = selected
        return program
    }

    fun checkpoint(resumeOffset: Long, stats: TsScanStats): TsScanCheckpoint = TsScanCheckpoint(
        resumeOffset = resumeOffset,
        chain = chain,
        pat = pat,
        program = program,
        timeline = timeline.snapshot(),
        pcrTimeline = pcrTimeline.snapshot(),
        stats = stats,
    )
}

/** 扫描统计的可变累加器。 */
internal class MutableStats {

    var packetsScanned: Long = 0L
    var syncErrors: Long = 0L
    var psiSections: Long = 0L
    var psiCrcErrors: Long = 0L
    var pcrSamples: Long = 0L
    var pesWithPts: Long = 0L
    var keyframes: Int = 0
    var discontinuities: Long = 0L
    var scrambledPackets: Long = 0L
    var truncatedTailBytes: Int = 0

    fun snapshot(state: ScanState): TsScanStats = TsScanStats(
        packetsScanned = packetsScanned,
        syncErrors = syncErrors,
        psiSections = psiSections,
        psiCrcErrors = psiCrcErrors,
        pcrSamples = pcrSamples,
        pesWithPts = pesWithPts,
        keyframes = keyframes,
        ptsWraps = state.timeline.wraps,
        ptsResets = state.timeline.resets,
        discontinuities = discontinuities,
        scrambledPackets = scrambledPackets,
        truncatedTailBytes = truncatedTailBytes,
    )
}
