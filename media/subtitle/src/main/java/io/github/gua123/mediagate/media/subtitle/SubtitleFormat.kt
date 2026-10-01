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

    /**
     * 纯文本稿（.txt）：**只导出台词文本**，没有时间轴，因此**不是可加载的字幕源**。
     *
     * 它只作为"另存为"的目标格式存在（plan 4.7 B 里 ASR 的第三种产出）；字幕定位与解析都
     * 不认它（见 [SOURCE_FORMATS]），否则视频目录里一个无关的 .txt 会被当成字幕候选。
     */
    TXT("txt", "纯文本", "text/plain"),
    ;

    /** 是否为可写回（另存）的格式（R14 要求 SRT / VTT，另加纯文本稿）。 */
    val writable: Boolean get() = this == SRT || this == VTT || this == TXT

    companion object {

        /**
         * 可当外挂字幕**加载**的格式（不含 [TXT]——它只是导出目标，不能反过来被当成字幕）。
         *
         * 顺序即"同名多份字幕"的偏好顺序（SRT 兼容性最好）。
         */
        val SOURCE_FORMATS: List<SubtitleFormat> = entries.filter { it != TXT }

        /** 全部受支持的字幕扩展名（小写；不含 txt）。 */
        val EXTENSIONS: List<String> = SOURCE_FORMATS.map { it.extension }

        /** 按扩展名（大小写不敏感、允许带点）取格式；不认识返回 null。 */
        fun fromExtension(extension: String): SubtitleFormat? {
            val normalized = extension.removePrefix(".").trim().lowercase()
            if (normalized.isEmpty()) return null
            // 只认"可加载的字幕源"：.txt 是导出格式，不参与定位
            return SOURCE_FORMATS.firstOrNull { it.extension == normalized }
        }

        /** 按文件名（或路径末段）取格式；不认识返回 null。 */
        fun fromFileName(fileName: String): SubtitleFormat? =
            fromExtension(fileName.substringAfterLast('.', ""))

        /** 文件名是否是受支持的字幕文件。 */
        fun isSubtitleFile(fileName: String): Boolean = fromFileName(fileName) != null
    }
}
