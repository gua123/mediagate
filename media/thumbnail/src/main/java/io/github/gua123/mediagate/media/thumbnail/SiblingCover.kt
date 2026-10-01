package io.github.gua123.mediagate.media.thumbnail

import io.github.gua123.mediagate.core.model.RemoteEntry

/**
 * 同目录封面兜底（**R5**，plan 4.4 音频行的补充）。
 *
 * 为什么需要它：很多音乐文件（尤其是整轨 / 老资源）**没有内嵌封面**，但旁边放着
 * `cover.jpg` / `folder.jpg`——播放器圈子的通行做法。只做内嵌封面的话，这些专辑在列表里
 * 全是同一个灰块；有了兜底，用户什么都不用改就能看到封面。
 *
 * 口径：优先认通行文件名（[BASENAMES] 按优先级），扩展名只要是图片即可（大小写不敏感）。
 * 不做"目录里唯一图片就用它"的宽松兜底——那会把歌词本、海报、截图误当封面。
 */
object SiblingCover {

    /** 认得的封面基名（小写，顺序 = 优先级）。 */
    val BASENAMES: List<String> = listOf("cover", "folder", "album", "front", "albumart", "albumartsmall")

    /** 认得的图片扩展名（小写，不带点）。 */
    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "bmp")

    /**
     * 从同目录的条目里挑一张封面；没有就返回 null。
     *
     * @param entries 目录列表（含子目录，本函数会跳过目录）。
     */
    fun pick(entries: List<RemoteEntry>): RemoteEntry? {
        val images = entries.filter { !it.isDirectory && isImage(it.name) }
        if (images.isEmpty()) return null
        // 按基名优先级取：cover.* 优先于 folder.*，同基名再按扩展名稳定排序
        for (basename in BASENAMES) {
            images.firstOrNull { baseName(it.name) == basename }?.let { return it }
        }
        return null
    }

    /** 文件名是否图片（按扩展名）。 */
    fun isImage(fileName: String): Boolean = extensionOf(fileName) in IMAGE_EXTENSIONS

    /** 小写扩展名（不含点）。 */
    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** 小写基名（去掉扩展名）。 */
    private fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName).lowercase()
}
