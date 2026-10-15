package io.github.gua123.mediagate.media.enginenative

/**
 * 自研引擎的状态串（[NativeEngine.playerStatus] 的返回值）解析结果。
 *
 * native 侧格式（**字段只增不改序**）：
 *
 *     状态|位置ms|时长ms|已显示帧数|丢帧数|宽x高|视频解码器|音频解码器|采样率|声道|音频欠载|音画偏差ms|音频输出说明
 *
 * 出问题时 native 回的是单段文本 "错误：…"，此时 [error] 非空、其余字段为默认值。
 * 解析只做"能读就读"：字段缺失或不是数字都不抛异常（引擎状态不该把界面搞崩）。
 */
data class NativePlayerStatus(
    val state: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val frames: Long = 0,
    val droppedFrames: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val audioUnderruns: Long = 0,
    val avOffsetMs: Long = 0,
    val audioNote: String = "",
    /** 起播耗时（毫秒）：开始打开 → 第一帧送屏；0 = 还没有（native 侧量好填进来）。 */
    val firstFrameMs: Long = 0,
    /** 最近一次 seek 的等待（毫秒）：请求 → 落点第一帧送屏；0 = 还没 seek 过。 */
    val lastSeekMs: Long = 0,
    val error: String? = null,
) {
    /** 有没有音频（native 报的采样率 > 0 才算）。 */
    val hasAudio: Boolean get() = sampleRate > 0

    /**
     * 一行中文**播放指标**（诊断卡用）：起播 / seek 等待 / 丢帧 / 音频欠载 / 音画偏差。
     *
     * 为什么要摆出来（网络加载方案 P0.4）：没有基线就证明不了"变快"，
     * 而用户能截图发过来的东西，才是真机上的第一手数据。
     */
    fun metricsLine(): String = buildString {
        append("起播 ").append(firstFrameMs).append(" ms")
        if (lastSeekMs > 0) append(" · seek 等待 ").append(lastSeekMs).append(" ms")
        append(" · 丢帧 ").append(droppedFrames)
        append(" · 音频欠载 ").append(audioUnderruns)
        append(" · 音画偏差 ").append(avOffsetMs).append(" ms")
    }

    /** 给诊断卡 / 日志用的一行中文自述。 */
    fun describe(): String {
        if (error != null) return "自研引擎播放出错：$error"
        // 备注（例如"打不开音频输出"）不管是哪条分支都要露出来，否则界面只看到"有音频"却听不到声
        val note = if (audioNote.isNotEmpty()) "（$audioNote）" else ""
        val audio = if (hasAudio) {
            "音频 $audioCodec ${sampleRate}Hz/${channels}声道" +
                (if (audioUnderruns > 0) "（欠载 $audioUnderruns 次）" else "") + note
        } else {
            "没有音频" + note
        }
        return "$state ${positionMs / 1000} 秒 / ${durationMs / 1000} 秒；画面 $videoCodec " +
            "${width}x${height}，已显示 $frames 帧、丢弃 $droppedFrames 帧；$audio；音画偏差 ${avOffsetMs} ms"
    }

    companion object {
        private const val ERROR_PREFIX = "错误："
        private const val MIN_FIELDS = 13

        /** 解析 native 的状态串；字段太少（不是我们认识的格式）时返回 null。 */
        fun parse(raw: String): NativePlayerStatus? {
            if (raw.startsWith(ERROR_PREFIX)) {
                return NativePlayerStatus(
                    state = "出错",
                    error = raw.removePrefix(ERROR_PREFIX).ifEmpty { "原因未知" },
                )
            }
            val parts = raw.split('|')
            if (parts.size < MIN_FIELDS) return null
            val size = parts[5].split('x')
            return NativePlayerStatus(
                state = parts[0],
                positionMs = parts[1].toLongOrNull() ?: 0L,
                durationMs = parts[2].toLongOrNull() ?: 0L,
                frames = parts[3].toLongOrNull() ?: 0L,
                droppedFrames = parts[4].toLongOrNull() ?: 0L,
                width = size.getOrNull(0)?.toIntOrNull() ?: 0,
                height = size.getOrNull(1)?.toIntOrNull() ?: 0,
                videoCodec = parts[6],
                audioCodec = parts[7],
                sampleRate = parts[8].toIntOrNull() ?: 0,
                channels = parts[9].toIntOrNull() ?: 0,
                audioUnderruns = parts[10].toLongOrNull() ?: 0L,
                avOffsetMs = parts[11].toLongOrNull() ?: 0L,
                audioNote = parts[12],
                // 起播 / seek 指标是 2026-10-15 追加的字段（老串没有就按 0 算，不许因为缺字段炸掉解析）
                firstFrameMs = parts.getOrNull(13)?.toLongOrNull() ?: 0L,
                lastSeekMs = parts.getOrNull(14)?.toLongOrNull() ?: 0L,
            )
        }
    }
}
