package io.github.gua123.mediagate.media.subtitle

/**
 * 外挂字幕格式（R14：支持 srt / vtt / ass（基础样式）/ ssa）。
 *
 * 枚举顺序即「同名同目录存在多份字幕」时的默认偏好：SRT 兼容性最好，其次 WebVTT、ASS、SSA
 * （候选排序见 [SubtitleLocator] 的 [SubtitleMatchKind] 排名）。
 *
 * @property extension 小写扩展名（不含点）。
 * @property label 展示名（界面直接显示，与 :media:engine 的字幕 mime 口径一致）。
 * @property mimeType 交给播放内核解析时用的 MIME 类型。
 */
enum class SubtitleFormat(
    val extension: String,
    val label: String,
    val mimeType: String,
) {

    /** SubRip（.srt）。 */
    SRT("srt", "SRT", "application/x-subrip"),

    /** WebVTT（.vtt）。 */
    VTT("vtt", "WebVTT", "text/vtt"),

    /** Advanced SubStation Alpha（.ass，基础样式）。 */
    ASS("ass", "ASS", "text/x-ssa"),

    /** SubStation Alpha（.ssa，基础样式）。 */
    SSA("ssa", "SSA", "text/x-ssa"),
    ;

    /** 是否为可写回（另存）的文本格式（R14 只要求 SRT / VTT）。 */
    val writable: Boolean get() = this == SRT || this == VTT

    companion object {

        /** 全部受支持扩展名（小写）。 */
        val EXTENSIONS: List<String> = entries.map { it.extension }

        /** 按扩展名（大小写不敏感、允许带点）取格式；不认识返回 null。 */
        fun fromExtension(extension: String): SubtitleFormat? {
            val normalized = extension.removePrefix(".").trim().lowercase()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.extension == normalized }
        }

        /** 按文件名（或路径末段）取格式；不认识返回 null。 */
        fun fromFileName(fileName: String): SubtitleFormat? =
            fromExtension(fileName.substringAfterLast('.', ""))

        /** 文件名是否是受支持的字幕文件。 */
        fun isSubtitleFile(fileName: String): Boolean = fromFileName(fileName) != null
    }
}
