package io.github.gua123.mediagate.feature.player.video

/**
 * 播放列表面板的滚动定位（**2026-10-03 用户要求**：「播放列表打开时自动跳转到当前播放的视频，
 * 而不是在最上边」）。
 *
 * 只算"第一屏从哪一项开始"：把当前项放进去，同时**尽量往上留一项**——
 * 这样你能看到"上一个"，也一眼知道自己在队列里的位置；已经在第一项时就停在顶部。
 */
object PlaylistScroll {

    /**
     * @param currentIndex 当前正在播的下标。
     * @param count 队列长度。
     * @return 打开面板时应该滚到第几项（0 起）；队列为空返回 0。
     */
    fun firstVisibleIndex(currentIndex: Int, count: Int): Int {
        if (count <= 0) return 0
        val safeCurrent = currentIndex.coerceIn(0, count - 1)
        return (safeCurrent - 1).coerceAtLeast(0)
    }
}
