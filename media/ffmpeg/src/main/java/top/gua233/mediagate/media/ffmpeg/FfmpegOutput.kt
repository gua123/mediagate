package io.github.gua123.mediagate.media.ffmpeg

import java.util.Locale

/**
 * FFmpeg 会话的结局（R11）。
 *
 * 取值来自 ffmpeg-kit 的 return code：0 = 成功，255 = 被取消（`FFmpegKit.cancel`），其余为失败。
 * 单测直接覆盖 [of]，因此调用方不需要再引用 native 类型。
 */
enum class FfmpegOutcome {

    /** return code = 0。 */
    SUCCESS,

    /** return code = 255：被 [FfmpegRunner.cancel] 取消（不是错误）。 */
    CANCELLED,

    /** 其余 return code：真正的失败。 */
    FAILED;

    val isSuccess: Boolean get() = this == SUCCESS

    val isCancelled: Boolean get() = this == CANCELLED

    companion object {

        /** ffmpeg-kit 的取消返回码（ReturnCode.CANCEL）。 */
        const val CANCEL_RETURN_CODE = 255

        /** 把 return code 映射成结局（**纯函数**）。 */
        fun of(returnCode: Int): FfmpegOutcome = when (returnCode) {
            0 -> SUCCESS
            CANCEL_RETURN_CODE -> CANCELLED
            else -> FAILED
        }
    }
}

/**
 * 一次进度回调的快照（R11，plan 4.10「长任务显示进度」）。
 *
 * @param positionMs 已处理到的媒体时间（毫秒）；-1 表示本次统计里没有时间信息。
 * @param speed 处理倍速（1.0 = 实时）；-1.0 未知。
 * @param fraction 进度 0..1；**总时长未知时为 null**（不做假进度）。
 * @param frameNumber 已处理帧数；-1 未知（`-c copy` 不产帧）。
 * @param bitrateKbps 瞬时码率（kbits/s）；-1 未知。
 * @param sizeBytes 已写出字节数；-1 未知。
 */
data class FfmpegProgress(
    val positionMs: Long,
    val speed: Double,
    val fraction: Double?,
    val frameNumber: Int = -1,
    val bitrateKbps: Double = -1.0,
    val sizeBytes: Long = -1L,
) {

    /** 0..100 的整数百分比，便于直接塞进通知栏；总时长未知时返回 -1。 */
    val percent: Int get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) } ?: -1
}

/**
 * 一次 FFmpeg 会话的完整结果（R11）。
 *
 * @param sessionId ffmpeg-kit 的会话 id（取消、诊断用）。
 * @param returnCode 原始 return code。
 * @param output 会话输出文本（`-loglevel error` 下通常只有错误）。
 * @param failStackTrace ffmpeg-kit 在 native 崩溃时给出的堆栈，可为 null。
 * @param durationMs 会话耗时（毫秒）。
 */
data class FfmpegResult(
    val sessionId: Long,
    val returnCode: Int,
    val outcome: FfmpegOutcome,
    val output: String,
    val failStackTrace: String?,
    val durationMs: Long,
    val command: FfmpegCommand,
) {
    val isSuccess: Boolean get() = outcome.isSuccess

    val isCancelled: Boolean get() = outcome.isCancelled
}

/**
 * ffmpeg 文本输出的**纯函数解析**（R11）：return code 归类、进度换算、统计行/时长行解析。
 *
 * 单独抽出来的原因：这些逻辑最容易出错（时间轴格式、`N/A`、缺少字段、Locale 小数点），
 * 而它们**完全不需要设备**——抽成纯函数就能在 JVM 单测里穷举，真机上只留「把返回值交出去」。
 */
object FfmpegOutput {

    /** `key=value` 对；ffmpeg 会在 `=` 后补空格（`bitrate= 838.9kbits/s`），所以 `=` 后允许空白。 */
    private val KEY_VALUE = Regex("([A-Za-z_]+)=\\s*([^\\s]+)")

    /** `Duration: 00:02:00.05, start: ...` 里的时长。 */
    private val DURATION = Regex("Duration:\\s*([0-9:.]+)")

    /**
     * 解析 ffmpeg 时间码 `HH:MM:SS.cc` / `MM:SS.cc`（**纯函数**）。
     *
     * @return 毫秒；`N/A`、空串、格式不符返回 null；负值（`-00:00:01.00`）原样返回负毫秒。
     */
    fun parseTimecode(text: String?): Long? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("N/A", ignoreCase = true)) return null
        val negative = raw.startsWith("-")
        val body = if (negative) raw.substring(1) else raw
        val parts = body.split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val ms = (hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000.0)
        return if (negative) -ms else ms
    }

    /**
     * 解析倍速 `1.5x`（**纯函数**）。
     *
     * @return 倍速；`N/A`、空串、非数字返回 null。
     */
    fun parseSpeed(text: String?): Double? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("N/A", ignoreCase = true)) return null
        val body = raw.removeSuffix("x").removeSuffix("X")
        val value = body.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    /** 解析 `Duration:` 行（**纯函数**）；没有该行返回 null。 */
    fun parseDurationLine(line: String): Long? =
        DURATION.find(line)?.groupValues?.get(1)?.let(::parseTimecode)

    /** 解析码率 `838.9kbits/s` → kbits/s（**纯函数**）；未知返回 -1.0。 */
    fun parseBitrateKbps(text: String?): Double {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("N/A", ignoreCase = true)) return -1.0
        val number = raw.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return -1.0
        val lower = raw.lowercase(Locale.US)
        return when {
            lower.contains("mbit") -> number * 1000.0
            lower.contains("kbit") -> number
            lower.contains("bit") -> number / 1000.0
            else -> number
        }
    }

    /** 解析 `size=1024kB` → 字节（**纯函数**）；未知返回 -1。 */
    fun parseSizeBytes(text: String?): Long {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("N/A", ignoreCase = true)) return -1L
        val number = raw.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return -1L
        val lower = raw.lowercase(Locale.US)
        val factor = when {
            lower.endsWith("gib") || lower.endsWith("gb") -> 1024.0 * 1024 * 1024
            lower.endsWith("mib") || lower.endsWith("mb") -> 1024.0 * 1024
            lower.endsWith("kib") || lower.endsWith("kb") -> 1024.0
            else -> 1.0
        }
        return (number * factor).toLong()
    }

    /**
     * 把「位置 / 总时长」换算成 0..1 的进度（**纯函数**）。
     *
     * @return null 表示总时长未知（不做假进度）；否则夹在 0..1。
     */
    fun fractionOf(positionMs: Long, totalDurationMs: Long?): Double? {
        if (totalDurationMs == null || totalDurationMs <= 0L || positionMs < 0L) return null
        return (positionMs.toDouble() / totalDurationMs.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 解析 ffmpeg 的统计行（**纯函数**）：
     * `frame=  250 fps= 25 q=28.0 size=    1024kB time=00:00:10.00 bitrate= 838.9kbits/s speed=1.01x`。
     *
     * `-c copy` 的命令没有 `frame=`；此时帧数为 -1，其余字段照常解析。
     *
     * @return 解析不出任何已知字段时返回 null。
     */
    fun parseStatisticsLine(line: String, totalDurationMs: Long? = null): FfmpegProgress? {
        val fields = HashMap<String, String>(8)
        for (match in KEY_VALUE.findAll(line)) {
            fields[match.groupValues[1].lowercase(Locale.US)] = match.groupValues[2]
        }
        if (!fields.containsKey("time") && !fields.containsKey("speed") && !fields.containsKey("frame")) return null
        val positionMs = parseTimecode(fields["time"]) ?: -1L
        val speed = parseSpeed(fields["speed"]) ?: -1.0
        val frame = fields["frame"]?.trim()?.toIntOrNull() ?: -1
        return FfmpegProgress(
            positionMs = positionMs,
            speed = speed,
            fraction = fractionOf(positionMs, totalDurationMs),
            frameNumber = frame,
            bitrateKbps = parseBitrateKbps(fields["bitrate"]),
            sizeBytes = parseSizeBytes(fields["size"]),
        )
    }

    /**
     * ffmpeg-kit 的 [com.arthenica.ffmpegkit.Statistics] → 统一进度对象（**纯函数**）。
     *
     * 之所以不直接吃 `Statistics`：那会让进度换算绑定 native 类型，JVM 单测就没法覆盖。
     *
     * @param timeSeconds Statistics.getTime()（秒）。
     * @param speed Statistics.getSpeed()（倍速）。
     */
    fun fromStatistics(
        timeSeconds: Double,
        speed: Double,
        totalDurationMs: Long? = null,
        frameNumber: Int = -1,
    ): FfmpegProgress {
        val positionMs = if (timeSeconds.isFinite() && timeSeconds >= 0) Math.round(timeSeconds * 1000.0) else -1L
        return FfmpegProgress(
            positionMs = positionMs,
            speed = if (speed.isFinite() && speed > 0) speed else -1.0,
            fraction = fractionOf(positionMs, totalDurationMs),
            frameNumber = frameNumber,
        )
    }

    /** return code 的中文说明（错误提示 / 日志用）。 */
    fun describeReturnCode(returnCode: Int): String = when (FfmpegOutcome.of(returnCode)) {
        FfmpegOutcome.SUCCESS -> "成功"
        FfmpegOutcome.CANCELLED -> "已取消"
        FfmpegOutcome.FAILED -> "FFmpeg 执行失败（return code=$returnCode）"
    }
}
