package io.github.gua123.mediagate.app

/**
 * **预约缓存只温开头**（2026-10-20 用户要求：「下载缓存的功能，需要下载的是未观看过的视频文件，
 * 加载开头一段时间的缓存，而不是缓存该文件的全部，以提高初次打开时的观看体验」）。
 *
 * 为什么是"开头一小段"而不是整份：
 * - 用户要的是**初次打开的快**，不是离线收藏——整份下载既慢又占空间（一部电影几个 GB），
 *   而"点开就能播、拖一下也顺"只需要开头那一小段；
 * - Media3 起播读的正是文件头，缓存住开头就消掉了那一次最慢的往返；
 * - 后面的内容按需取（分段缓存 + 播放中滑窗预取本来就在做），不需要提前囤。
 *
 * 目标大小取 **12 MB**（约合十几秒到半分钟，看码率）：往下要小到别浪费空间与时间，
 * 往上要够覆盖起播与一小段缓冲；比文件还大时按整份算（小文件没有"只温开头"的意义）。
 */
object CacheHeadPlan {

    /** 温开头的目标字节数（12 MB）。 */
    const val HEAD_BYTES = 12L * 1024 * 1024

    /** 这条任务要缓存多少字节：小文件整份，大文件只开头 [HEAD_BYTES]。 */
    fun targetBytes(totalBytes: Long?): Long =
        if (totalBytes == null) HEAD_BYTES else minOf(totalBytes, HEAD_BYTES)

    /** 是否已经够用了（开头那段已经在缓存里）。 */
    fun satisfied(totalBytes: Long?, cachedBytes: Long): Boolean = cachedBytes >= targetBytes(totalBytes)

    /** 界面/说明用：这个文件是"整份"还是"开头 12 MB"。 */
    fun label(totalBytes: Long?): String =
        if (totalBytes != null && totalBytes <= HEAD_BYTES) {
            "整份（文件较小）"
        } else {
            "开头 " + (HEAD_BYTES / (1024 * 1024)) + " MB"
        }
}
