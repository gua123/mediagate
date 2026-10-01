package io.github.gua123.mediagate.media.subtitle

import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend

/** 候选来源（R14）：自动匹配出来的，还是用户手动指定的。 */
enum class SubtitleSource {

    /** 按「同目录同名 → 语言后缀 → 修饰后缀」自动匹配出来。 */
    AUTO,

    /** 用户手动选择（不在自动匹配结果里，或用户刻意选了别的）。 */
    MANUAL,
}

/**
 * 自动匹配的优先级档位（R14 的三级优先级）。
 *
 * **枚举顺序即优先级**，排序时直接用 ordinal：同名同扩展 → 语言后缀 → default/forced 之类修饰。
 */
enum class SubtitleMatchKind {

    /** ① 同名同目录：`v.mkv` ↔ `v.srt`。 */
    SAME_NAME,

    /** ② 同名 + 语言后缀：`v.zh.srt` / `v.chs.srt` / `v.zh-CN.srt` / `v.zh_Hans.srt`。 */
    LANGUAGE_SUFFIX,

    /** ③ 同名 + 修饰后缀：`v.default.srt` / `v.forced.srt`。 */
    MODIFIER,
}

/**
 * 一个字幕候选（R14：**支持多候选**，UI 让用户选，播放时只加载一条轨道）。
 *
 * @property path 后端内路径（与视频同一目录、同一 [StorageBackend] 口径）。
 * @property name 文件名（展示）。
 * @property format 字幕格式（由扩展名判定）。
 * @property source 自动匹配 / 手动选择。
 * @property matchKind 命中的优先级档位（手动候选固定 [SubtitleMatchKind.MODIFIER]）。
 * @property language 规范化语言码（小写、下划线转连字符），如 `zh-hans`、`chs`；无则 null。
 * @property languageLabel 语言展示名（中文，R16）；无则 null。
 * @property modifiers 命中的修饰标签（`default` / `forced` …）。
 */
data class SubtitleCandidate(
    val path: String,
    val name: String,
    val format: SubtitleFormat,
    val source: SubtitleSource,
    val matchKind: SubtitleMatchKind,
    val language: String? = null,
    val languageLabel: String? = null,
    val modifiers: List<String> = emptyList(),
) {

    /** 界面展示名：有语言标签时给「语言 · 格式」，否则给文件名。 */
    val displayName: String
        get() = if (languageLabel != null) languageLabel + " · " + format.label else name

    /** 是否是「强制字幕」（forced）。 */
    val isForced: Boolean get() = modifiers.contains("forced")

    companion object {

        /**
         * 把任意一个字幕文件当成手动候选（R14「手动选择文件」的入口）。
         *
         * 语言码仍会从文件名里识别（能识别就显示语言标签，方便用户分辨），但来源是 [SubtitleSource.MANUAL]。
         *
         * @return 扩展名不是受支持字幕格式时返回 null。
         */
        fun manual(
            path: String,
            languageCodes: Map<String, String> = SubtitleLocator.DEFAULT_LANGUAGE_CODES,
        ): SubtitleCandidate? {
            val format = SubtitleFormat.fromFileName(path) ?: return null
            val name = SubtitleLocator.fileNameOf(path)
            val tags = SubtitleLocator.tagsOf(SubtitleLocator.baseNameOf(name))
            val found = SubtitleLocator.firstLanguage(tags, languageCodes)
            return SubtitleCandidate(
                path = path,
                name = name,
                format = format,
                source = SubtitleSource.MANUAL,
                matchKind = SubtitleMatchKind.MODIFIER,
                language = found?.first,
                languageLabel = found?.second,
                modifiers = tags.filter { it.lowercase() in SubtitleLocator.DEFAULT_MODIFIERS },
            )
        }
    }
}

/**
 * 外挂字幕定位器（R14：来源 = 与视频同名同目录的本地或远端字幕，走同一 StorageBackend）。
 *
 * 三个入口，都是**纯逻辑**（输入视频路径 + 同目录条目列表，输出有序候选）：
 * - [locate]：只要自动匹配结果，按 ① 同名 → ② 语言后缀 → ③ 修饰后缀 排序；
 * - [manualCandidates]：同目录里没被自动匹配上的其余字幕文件（手动可选项）；
 * - [pickList]：两者拼接，供界面做完整候选列表。
 *
 * IO 不在这里：目录列举由调用方以函数参数注入（[discover]），因此这一层可以纯 JVM 单测。
 *
 * 语言码表可配：默认 [DEFAULT_LANGUAGE_CODES]（键为语言码、值为中文展示名，**顺序即偏好**）。
 */
object SubtitleLocator {

    /**
     * 默认语言码表（顺序即偏好：中文各写法在前，然后英语、日语、韩语、其它）。
     *
     * 覆盖 R14 点名的写法：`.zh.srt` / `.chs.srt` / `.chi.srt` / `.zh-CN.srt` / `.zh_Hans.srt` / `.eng.srt`。
     */
    val DEFAULT_LANGUAGE_CODES: Map<String, String> = linkedMapOf(
        "zh" to "中文",
        "zh-cn" to "中文（简体）",
        "zh-hans" to "中文（简体）",
        "chs" to "中文（简体）",
        "chi" to "中文",
        "zh-sg" to "中文（新加坡）",
        "zh-tw" to "中文（繁体）",
        "zh-hk" to "中文（香港）",
        "zh-hant" to "中文（繁体）",
        "cht" to "中文（繁体）",
        "eng" to "英语",
        "en" to "英语",
        "jpn" to "日语",
        "ja" to "日语",
        "kor" to "韩语",
        "ko" to "韩语",
        "fra" to "法语",
        "fr" to "法语",
        "deu" to "德语",
        "de" to "德语",
        "spa" to "西班牙语",
        "es" to "西班牙语",
        "rus" to "俄语",
        "ru" to "俄语",
        "por" to "葡萄牙语",
        "pt" to "葡萄牙语",
        "ita" to "意大利语",
        "it" to "意大利语",
        "tha" to "泰语",
        "th" to "泰语",
        "vie" to "越南语",
        "vi" to "越南语",
    )

    /** 默认修饰标签（R14 的 `.default` / `.forced` 之类）。 */
    val DEFAULT_MODIFIERS: List<String> = listOf("default", "forced", "sdh", "cc", "hi")

    /**
     * 自动匹配（R14 优先级 ① → ② → ③）。
     *
     * 只返回**同一目录**里、基名能与视频对上、且后缀标签全是「已知语言码或已知修饰」的字幕。
     * 后缀里出现未知标签（例如 `Movie.2024.srt` 之于 `Movie.mkv`）一律不算候选——
     * 那多半是另一部片子的字幕，宁可让用户手动选。
     */
    fun locate(
        videoPath: String,
        entries: List<RemoteEntry>,
        languageCodes: Map<String, String> = DEFAULT_LANGUAGE_CODES,
        modifiers: List<String> = DEFAULT_MODIFIERS,
    ): List<SubtitleCandidate> {
        val directory = directoryOf(videoPath)
        val videoBase = baseNameOf(fileNameOf(videoPath))
        if (videoBase.isEmpty()) return emptyList()
        val known = modifiers.map { it.lowercase() }.toSet()
        val found = mutableListOf<SubtitleCandidate>()
        for (entry in entries) {
            if (entry.isDirectory) continue
            if (directoryOf(entry.path) != directory) continue
            val format = SubtitleFormat.fromFileName(entry.name) ?: continue
            val name = baseNameOf(entry.name)
            if (name.equals(videoBase, ignoreCase = true)) {
                found += SubtitleCandidate(
                    path = entry.path,
                    name = entry.name,
                    format = format,
                    source = SubtitleSource.AUTO,
                    matchKind = SubtitleMatchKind.SAME_NAME,
                )
                continue
            }
            val tags = suffixTags(name, videoBase) ?: continue
            var language: String? = null
            var languageLabel: String? = null
            val modifierHits = mutableListOf<String>()
            var unknownTag = false
            for (tag in tags) {
                val normalized = tag.lowercase()
                val matched = lookupLanguage(tag, languageCodes)
                when {
                    // 双语字幕（zh.eng）只取第一个语言码，第二个当附加信息忽略
                    matched != null -> if (language == null) {
                        language = matched.first
                        languageLabel = matched.second
                    }

                    normalized in known -> modifierHits += normalized

                    else -> unknownTag = true
                }
            }
            if (unknownTag) continue
            found += SubtitleCandidate(
                path = entry.path,
                name = entry.name,
                format = format,
                source = SubtitleSource.AUTO,
                matchKind = if (language != null) SubtitleMatchKind.LANGUAGE_SUFFIX else SubtitleMatchKind.MODIFIER,
                language = language,
                languageLabel = languageLabel,
                modifiers = modifierHits,
            )
        }
        return found.sortedWith(comparator(languageCodes))
    }

    /** 同目录里**没有**被自动匹配上的其余字幕文件（手动选项），按文件名排序。 */
    fun manualCandidates(
        videoPath: String,
        entries: List<RemoteEntry>,
        languageCodes: Map<String, String> = DEFAULT_LANGUAGE_CODES,
        modifiers: List<String> = DEFAULT_MODIFIERS,
    ): List<SubtitleCandidate> {
        val matched = locate(videoPath, entries, languageCodes, modifiers).mapTo(mutableSetOf()) { it.path }
        val directory = directoryOf(videoPath)
        val result = mutableListOf<SubtitleCandidate>()
        for (entry in entries) {
            if (entry.isDirectory) continue
            if (directoryOf(entry.path) != directory) continue
            if (entry.path in matched) continue
            val candidate = SubtitleCandidate.manual(entry.path, languageCodes) ?: continue
            result += candidate
        }
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** 自动匹配结果 + 手动候选（界面候选列表的完整口径）。 */
    fun pickList(
        videoPath: String,
        entries: List<RemoteEntry>,
        languageCodes: Map<String, String> = DEFAULT_LANGUAGE_CODES,
        modifiers: List<String> = DEFAULT_MODIFIERS,
    ): List<SubtitleCandidate> =
        locate(videoPath, entries, languageCodes, modifiers) +
            manualCandidates(videoPath, entries, languageCodes, modifiers)

    /**
     * 列同目录 → 出候选（R14 远端字幕：与视频同一 backend，能列目录就能匹配到）。
     *
     * 目录列举由调用方注入（[listDirectory]，**放在最后一个参数**，便于用尾随 lambda 调用），
     * 所以本函数在 JVM 单测里用假函数即可覆盖；
     * 列目录失败会把 [io.github.gua123.mediagate.data.storage.api.StorageException] 抛给上层
     * （播放页据此给中文提示，而不是静默没有字幕）。
     */
    suspend fun discover(
        videoPath: String,
        languageCodes: Map<String, String> = DEFAULT_LANGUAGE_CODES,
        modifiers: List<String> = DEFAULT_MODIFIERS,
        listDirectory: suspend (String) -> List<RemoteEntry>,
    ): List<SubtitleCandidate> =
        pickList(videoPath, listDirectory(directoryOf(videoPath)), languageCodes, modifiers)

    /** [discover] 的便捷重载：直接用存储后端列目录（本地/远端同一套代码）。 */
    suspend fun discover(
        backend: StorageBackend,
        videoPath: String,
        languageCodes: Map<String, String> = DEFAULT_LANGUAGE_CODES,
        modifiers: List<String> = DEFAULT_MODIFIERS,
    ): List<SubtitleCandidate> =
        discover(videoPath, languageCodes, modifiers) { directory -> backend.list(directory, null) }

    /** 路径所在目录（最后一个 `/` 之前；根目录为空串，与各后端口径一致）。 */
    fun directoryOf(path: String): String = path.substringBeforeLast('/', "")

    /** 路径末段（文件名）。 */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /** 文件名去掉扩展名（`Movie.2024.mkv` → `Movie.2024`）。 */
    fun baseNameOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName else fileName.substring(0, dot)
    }

    /** 把文件名按点拆成标签（`Movie.zh-Hans.forced.srt` → [Movie, zh-Hans, forced]）。 */
    fun tagsOf(baseName: String): List<String> =
        baseName.split('.').map { it.trim() }.filter { it.isNotEmpty() }

    /** 语言码归一化：小写 + 下划线转连字符。 */
    fun normalizeLanguageCode(code: String): String = code.trim().lowercase().replace('_', '-')

    /** 在标签列表里找第一个已知语言码（返回（规范化语言码, 中文展示名））。 */
    fun firstLanguage(
        tags: List<String>,
        languageCodes: Map<String, String> = DEFAULT_LANGUAGE_CODES,
    ): Pair<String, String>? {
        for (tag in tags) {
            val matched = lookupLanguage(tag, languageCodes) ?: continue
            return matched
        }
        return null
    }

    /**
     * 视频基名之后的标签（`Movie.zh.srt` 对 `Movie.mkv` 给 [zh]）；对不上返回 null。
     *
     * 只看「前缀完全一致 + 后面紧跟一个点」，所以 `Movie.zh.srt` 不会被误判成
     * `Movie.2024.mkv` 的字幕。
     */
    fun suffixTags(name: String, videoBase: String): List<String>? {
        if (name.length <= videoBase.length) return null
        if (!name.regionMatches(0, videoBase, 0, videoBase.length, ignoreCase = true)) return null
        if (name[videoBase.length] != '.') return null
        val tags = tagsOf(name.substring(videoBase.length + 1))
        return tags.ifEmpty { null }
    }

    /** 语言码查表：先精确匹配，再逐段去掉尾部（`zh-hans-cn` → `zh-hans` → `zh`）。 */
    private fun lookupLanguage(tag: String, languageCodes: Map<String, String>): Pair<String, String>? {
        var key = normalizeLanguageCode(tag)
        while (key.isNotEmpty()) {
            val hit = languageCodes.entries.firstOrNull { normalizeLanguageCode(it.key) == key }
            if (hit != null) return normalizeLanguageCode(hit.key) to hit.value
            val cut = key.lastIndexOf('-')
            if (cut <= 0) break
            key = key.substring(0, cut)
        }
        return null
    }

    /** 语言偏好排名（表里越靠前越小）；没有语言码的候选排在有语言码的后面。 */
    private fun languageRank(language: String?, languageCodes: Map<String, String>): Int {
        if (language == null) return Int.MAX_VALUE
        val index = languageCodes.keys.indexOfFirst { normalizeLanguageCode(it) == language }
        return if (index < 0) Int.MAX_VALUE - 1 else index
    }

    /** 排序：优先级档位 → 语言偏好 → 格式偏好（枚举声明顺序）→ 文件名。 */
    private fun comparator(languageCodes: Map<String, String>): Comparator<SubtitleCandidate> =
        Comparator { a, b ->
            var result = a.matchKind.ordinal - b.matchKind.ordinal
            if (result == 0) result = languageRank(a.language, languageCodes) - languageRank(b.language, languageCodes)
            if (result == 0) result = a.format.ordinal - b.format.ordinal
            if (result == 0) {
                result = a.name.compareTo(b.name, ignoreCase = true)
                if (result == 0) result = a.name.compareTo(b.name)
            }
            result
        }
}
