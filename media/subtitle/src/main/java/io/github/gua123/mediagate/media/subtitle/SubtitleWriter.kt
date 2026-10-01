package io.github.gua123.mediagate.media.subtitle

import io.github.gua123.mediagate.data.storage.api.StorageBackend
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * 字幕写回 / 另存（R14：微调后的时间轴可以写回视频同目录；另存 VTT 可用）。
 *
 * 两个层次：
 * - **纯函数序列化** [serialize]（SRT / WebVTT），可做 parse → serialize → parse 的往返单测；
 * - **写回** [writeBack]：交给 [StorageBackend.write]，**异常原样抛出**——
 *   [io.github.gua123.mediagate.data.storage.api.StorageException.AccessDenied] /
 *   [io.github.gua123.mediagate.data.storage.api.StorageException.NotSupported] 由上层
 *   （:app）捕获后落到 App 私有目录并给中文提示（R14：绝不静默丢弃）；
 *   另有 [writeLocal] 供那次兜底写入使用。
 */
object SubtitleWriter {

    /** WebVTT 文件头。 */
    const val VTT_HEADER = "WEBVTT"

    /**
     * cue 列表 → 字幕文本。
     *
     * @throws IllegalArgumentException 传入 ASS / SSA（R14 只要求写回 SRT / VTT，
     *   调用方应先「另存为」受支持的格式）。
     */
    fun serialize(cues: List<SubtitleCue>, format: SubtitleFormat): String = when (format) {
        SubtitleFormat.SRT -> serializeSrt(cues)
        SubtitleFormat.VTT -> serializeVtt(cues)
        SubtitleFormat.TXT -> serializeTxt(cues)
        SubtitleFormat.ASS, SubtitleFormat.SSA ->
            throw IllegalArgumentException("暂不支持写回 " + format.label + "，请另存为 SRT / VTT / 纯文本")
    }

    /**
     * 纯文本序列化（**plan 4.7 B 的第三种产出**）：只留台词，一行一条，没有序号与时间轴。
     *
     * 坏数据（空文本）丢掉、按起始时间排序；去重相邻重复行（ASR 分段重叠时很常见），
     * 让导出的稿件可以直接拿去用。
     */
    fun serializeTxt(cues: List<SubtitleCue>): String {
        val lines = ArrayList<String>()
        for (cue in ordered(cues)) {
            val text = cue.text.trim()
            if (text.isEmpty()) continue
            if (lines.lastOrNull() == text) continue
            lines += text
        }
        return lines.joinToString("\n")
    }

    /** SRT 序列化：序号 + `HH:MM:SS,mmm --> HH:MM:SS,mmm` + 文本 + 空行。 */
    fun serializeSrt(cues: List<SubtitleCue>): String {
        val builder = StringBuilder()
        ordered(cues).forEachIndexed { index, cue ->
            builder.append(index + 1).append('\n')
            builder.append(formatTimecode(cue.startMs, SubtitleFormat.SRT))
                .append(" --> ")
                .append(formatTimecode(cue.endMs, SubtitleFormat.SRT))
                .append('\n')
            builder.append(cue.text).append('\n').append('\n')
        }
        return builder.toString()
    }

    /** WebVTT 序列化：WEBVTT 头 + `HH:MM:SS.mmm --> HH:MM:SS.mmm` + 文本（无序号）。 */
    fun serializeVtt(cues: List<SubtitleCue>): String {
        val builder = StringBuilder(VTT_HEADER).append('\n').append('\n')
        for (cue in ordered(cues)) {
            builder.append(formatTimecode(cue.startMs, SubtitleFormat.VTT))
                .append(" --> ")
                .append(formatTimecode(cue.endMs, SubtitleFormat.VTT))
                .append('\n')
            builder.append(cue.text).append('\n').append('\n')
        }
        return builder.toString()
    }

    /**
     * 毫秒 → 时间码（SRT 用逗号、VTT 用点；毫秒三位对齐）。
     *
     * 负数钳到 0（R14 边界：负值不早于 0）。
     */
    fun formatTimecode(milliseconds: Long, format: SubtitleFormat): String {
        val value = milliseconds.coerceAtLeast(0L)
        val hours = value / 3_600_000L
        val minutes = (value % 3_600_000L) / 60_000L
        val seconds = (value % 60_000L) / 1_000L
        val millis = value % 1_000L
        val separator = if (format == SubtitleFormat.VTT) '.' else ','
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, separator, millis)
    }

    /**
     * 写回 [path]（R14：与视频同名同目录的远端/本地字幕文件）。
     *
     * 语义与 [StorageBackend.write] 一致：父目录必须已存在、同名直接覆盖。
     * **不吞异常**：无写权限时抛 [io.github.gua123.mediagate.data.storage.api.StorageException.AccessDenied]，
     * 不支持写入时抛 NotSupported，上层据此落本地缓存并提示用户。
     *
     * @param path 目标路径（后端内路径口径，通常用 [siblingPathOf] 生成）。
     */
    suspend fun writeBack(
        backend: StorageBackend,
        path: String,
        format: SubtitleFormat,
        cues: List<SubtitleCue>,
    ) {
        val bytes = serialize(cues, format).toByteArray(Charsets.UTF_8)
        backend.write(path, ByteArrayInputStream(bytes))
    }

    /**
     * 写本地文件（R14 兜底：远端无写权限时落到 App 私有目录）。
     *
     * 自动创建父目录；覆盖同名文件；返回写入的文件，便于上层提示「已保存到 …」。
     */
    fun writeLocal(file: File, format: SubtitleFormat, cues: List<SubtitleCue>): File {
        file.parentFile?.mkdirs()
        file.writeText(serialize(cues, format), Charsets.UTF_8)
        return file
    }

    /** 与视频同名同目录的目标路径（`dir/Movie.mkv` + VTT → `dir/Movie.vtt`）。 */
    fun siblingPathOf(videoPath: String, format: SubtitleFormat): String {
        val directory = SubtitleLocator.directoryOf(videoPath)
        val base = SubtitleLocator.baseNameOf(SubtitleLocator.fileNameOf(videoPath))
        val name = base + "." + format.extension
        return if (directory.isEmpty()) name else directory + "/" + name
    }

    /** 可写出的条目：丢掉坏数据（时间倒挂 / 空文本），按起始时间排序。 */
    private fun ordered(cues: List<SubtitleCue>): List<SubtitleCue> =
        cues.filter { it.isValid }.sortedBy { it.startMs }
}
