package io.github.gua123.mediagate.media.enginenative

/**
 * 容器里的一条内嵌字幕轨（**只描述，不解码**）。
 *
 * 为什么自研引擎能给出这个：文本类字幕（subrip / ass / mov_text / webvtt）在容器里
 * **本来就是纯文本 payload**，不需要 FFmpeg 的字幕解码器（本项目那份也没编），
 * 直接读 packet 再拼成 SRT 就能交给 App 已有的字幕解析器。
 */
data class EmbeddedSubtitleTrack(
    /** 流下标（抽取时用它）。 */
    val index: Int,
    /** FFmpeg 的编码名（subrip / ass / mov_text / hdmv_pgs_subtitle…）。 */
    val codec: String,
    /** 能不能走"读 payload 转文本"这条路（图像类字幕为 false ⇒ 界面上如实说不支持）。 */
    val textBased: Boolean,
    /** 容器里的语言标记（可能是空串）。 */
    val language: String,
    /** 容器里的轨名（可能是空串）。 */
    val title: String,
) {

    /**
     * 界面上显示的名字：优先轨名，其次语言，最后编码；
     * 图像类的轨**照样列出来**但标注清楚（不让用户以为"字幕丢了"）。
     */
    val label: String
        get() {
            val head = title.ifBlank { languageLabel() }.ifBlank { codec }
            val suffix = if (textBased) codec else codec + "（图像字幕，暂不支持）"
            return head + " · " + suffix
        }

    private fun languageLabel(): String = when (language.lowercase()) {
        "zh", "chi", "zho", "chs", "cht", "zh-cn", "zh-hans", "zh-hant" -> "中文"
        "en", "eng" -> "英语"
        "ja", "jpn" -> "日语"
        "ko", "kor" -> "韩语"
        else -> language
    }
}

/**
 * 解析 native 返回的字幕轨列表（**纯函数**，JVM 单测）。
 *
 * 协议：每行 `下标|编码|是否文本(1/0)|语言|轨名`；空串＝没有字幕轨；
 * 以"错误："开头＝失败原因（原样交给界面显示）。
 */
fun parseSubtitleTracks(raw: String): List<EmbeddedSubtitleTrack> {
    if (raw.isBlank() || raw.startsWith("错误：")) return emptyList()
    return raw.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 5) return@mapNotNull null
            val index = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            EmbeddedSubtitleTrack(
                index = index,
                codec = parts[1].trim(),
                textBased = parts[2].trim() == "1",
                language = parts[3].trim(),
                title = parts[4].trim(),
            )
        }
        .toList()
}
