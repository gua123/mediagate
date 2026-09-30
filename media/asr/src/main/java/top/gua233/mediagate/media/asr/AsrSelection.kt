package io.github.gua123.mediagate.media.asr

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.subtitle.SubtitleFormat
import io.github.gua123.mediagate.media.subtitle.SubtitleLocator
import io.github.gua123.mediagate.media.subtitle.SubtitleWriter

/**
 * 一个候选视频（**M7-B / R19** 批量选择的中间表示）。
 *
 * @property path 后端内路径。
 * @property name 文件名（展示）。
 * @property size 字节数；-1 = 未知。0 字节的会被过滤掉。
 * @property hasSubtitle 同目录是否已经有它的字幕（SRT/VTT/ASS/SSA，含语言后缀）。
 */
data class AsrCandidate(
    val path: String,
    val name: String,
    val size: Long = -1L,
    val hasSubtitle: Boolean = false,
)

/**
 * 批量选择的过滤结果（**R19**）。
 *
 * @property accepted 真正入队的候选。
 * @property skipped 被「跳过已有字幕」挡下的候选。
 * @property filteredOut 因为不是视频/是空文件被丢掉的条数。
 */
data class AsrSelectionResult(
    val accepted: List<AsrCandidate>,
    val skipped: List<AsrCandidate>,
    val filteredOut: Int,
) {
    /** 一共看了多少条。 */
    val examined: Int get() = accepted.size + skipped.size + filteredOut
}

/**
 * 批量字幕的「选谁、跳谁、叫什么名」纯逻辑（**M7-B / R19**）。
 *
 * 三件事全部不碰 IO：
 * 1. **自动过滤非视频**：先看扩展名（[MediaKindGuesser]），排掉目录、0 字节文件与
 *    m3u8 播放列表（它不是真文件，取不到音轨）；
 * 2. **跳过已有字幕**：同目录里只要存在「同名字幕」（含 .zh.srt / .chs.srt 这类语言后缀）就算有；
 * 3. **命名规则**：与视频同名同目录（复用 :media:subtitle 的 [SubtitleWriter.siblingPathOf]），
 *    保证「一次只产出一个字幕文件」且位置可预期。
 *
 * 目录递归、列目录这些真 IO 留给 :app（它是唯一知道当前后端的地方）。
 */
object AsrSelection {

    /** HLS 播放列表：虽然 [MediaKindGuesser] 归为视频，但它没有可解码的音轨，不能进 ASR 队列。 */
    private val PLAYLIST_EXTENSIONS = setOf("m3u8", "m3u")

    /** 语言后缀白名单（plan 4.7 A 的 .zh.srt / .chs.srt 口径）。 */
    private val LANGUAGE_SUFFIXES = setOf(
        "zh", "chs", "cht", "chi", "zho", "cn", "sc", "tc",
        "en", "eng", "ja", "jpn", "jp", "ko", "kor", "kr",
        "zh-cn", "zh-hans", "zh-hant", "zh-tw", "zh-hk", "pt-br", "es-419",
    )

    /** 扩展名（小写，不含点）。 */
    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * 这个目录项能不能进 ASR 队列（**纯函数**）。
     *
     * 目录不行、非视频不行、播放列表不行、明确是 0 字节的不行（列出后没读到的 size=-1 仍放行，
     * 由后续的解码步骤报「无音轨/解码失败」，不在这里假装知道）。
     */
    fun isVideoEntry(entry: RemoteEntry): Boolean = isVideoEntry(entry.name, entry.isDirectory, entry.size)

    /** [isVideoEntry] 的基本形态（单测直接打这三个参数）。 */
    fun isVideoEntry(name: String, isDirectory: Boolean = false, size: Long = -1L): Boolean {
        if (isDirectory) return false
        if (size == 0L) return false
        if (extensionOf(name) in PLAYLIST_EXTENSIONS) return false
        return MediaKindGuesser.guess(name) == MediaKind.VIDEO
    }

    /** 目录项列表 → 视频候选（保序）。 */
    fun videoEntries(entries: List<RemoteEntry>): List<RemoteEntry> = entries.filter { isVideoEntry(it) }

    /** 视频文件名去掉扩展名后的基名（用于找同名字幕）。 */
    fun baseNameOf(videoName: String): String = SubtitleLocator.baseNameOf(SubtitleLocator.fileNameOf(videoName))

    /**
     * 同目录里是否已经有这个视频的字幕（**纯函数**）。
     *
     * 命中规则：文件名等于「基名 + 可选的语言后缀 + 字幕扩展名」。
     * 例：视频 Movie.mkv → Movie.srt / Movie.zh.srt / Movie.chs.ass 都算有；
     * Movie2.srt、Movie.zh.txt 不算。
     *
     * @param siblingNames 同目录下的**文件名**集合（不是路径）。
     */
    fun hasExistingSubtitle(videoPath: String, siblingNames: Set<String>): Boolean =
        findExistingSubtitle(videoPath, siblingNames) != null

    /** 返回命中的那个字幕文件名；没有则 null。 */
    fun findExistingSubtitle(videoPath: String, siblingNames: Set<String>): String? {
        val base = baseNameOf(videoPath)
        if (base.isEmpty()) return null
        return siblingNames.firstOrNull { name ->
            val extension = SubtitleFormat.fromExtension(extensionOf(name)) ?: return@firstOrNull false
            val stem = name.dropLast(extension.extension.length + 1)
            if (stem == base) return@firstOrNull true
            if (!stem.startsWith(base + ".")) return@firstOrNull false
            val tag = stem.removePrefix(base + ".").lowercase()
            tag.isNotEmpty() && tag in LANGUAGE_SUFFIXES
        }
    }

    /**
     * 候选 → 入队/跳过（**纯函数**，R19 的「可勾选跳过已有字幕」）。
     *
     * @param skipExisting true = 已经有字幕的文件进 [AsrSelectionResult.skipped] 而不是入队。
     */
    fun plan(candidates: List<AsrCandidate>, skipExisting: Boolean): AsrSelectionResult {
        val accepted = ArrayList<AsrCandidate>()
        val skipped = ArrayList<AsrCandidate>()
        var filtered = 0
        for (candidate in candidates) {
            if (!isVideoEntry(candidate.name, isDirectory = false, size = candidate.size)) {
                filtered++
                continue
            }
            if (skipExisting && candidate.hasSubtitle) {
                skipped += candidate
            } else {
                accepted += candidate
            }
        }
        return AsrSelectionResult(accepted, skipped, filtered)
    }

    /** 一条候选的跳过原因（中文，界面展示）。 */
    fun skipReason(candidate: AsrCandidate): String = "已有字幕，按设置跳过"

    /** 输出的字幕文件名（与视频同名，换扩展名）。 */
    fun outputNameFor(videoPath: String, format: SubtitleFormat): String =
        baseNameOf(videoPath) + "." + format.extension

    /** 输出的字幕路径（与视频同目录同名）。 */
    fun outputPathFor(videoPath: String, format: SubtitleFormat): String =
        SubtitleWriter.siblingPathOf(videoPath, format)
}
