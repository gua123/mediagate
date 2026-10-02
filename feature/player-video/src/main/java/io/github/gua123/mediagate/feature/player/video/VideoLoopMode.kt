package io.github.gua123.mediagate.feature.player.video

/**
 * 视频播放页的循环方式（**2026-10-03 用户要求**：「没有此文件夹循环、单曲循环、随机播放」）。
 *
 * 四档，点一下按顺序换一档（界面上是一个循环按钮 + 当前档位的字）。
 */
enum class VideoLoopMode(val zhText: String) {

    /** 播完就停（默认，行为与旧版一致）。 */
    OFF("不循环"),

    /** 播完自动播**同文件夹的下一个**（到末尾回到第一个）。 */
    FOLDER("文件夹循环"),

    /** 播完从头再播这一个。 */
    SINGLE("单曲循环"),

    /** 播完随机跳同文件夹里的另一个（只有一个文件时＝重复自己）。 */
    RANDOM("随机播放");

    /** 点一下换到下一档。 */
    fun next(): VideoLoopMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 播完之后去哪儿（纯函数，好测）。
 *
 * 返回 null ＝**什么都不做**（停在结尾）；返回 [REPLAY] ＝把当前这个从头再来；
 * 返回其它值 ＝队列里那个下标。
 */
object VideoLoopDecision {

    /** 单曲循环的返回值（比"返回同一个 index"更明确，免得和"只剩一个文件"混淆）。 */
    const val REPLAY: Int = -1

    /**
     * @param mode 当前档位。
     * @param currentIndex 当前在队列里的位置。
     * @param size 队列长度。
     * @param randomPick 随机数取模前的原始值（调用方传 Random.nextInt()；测试传定值）。
     */
    fun onEnded(mode: VideoLoopMode, currentIndex: Int, size: Int, randomPick: Int = 0): Int? {
        if (size <= 0) return null
        return when (mode) {
            VideoLoopMode.OFF -> null
            VideoLoopMode.SINGLE -> REPLAY
            VideoLoopMode.FOLDER -> (currentIndex + 1) % size
            VideoLoopMode.RANDOM -> if (size == 1) {
                REPLAY
            } else {
                // 随机但**不重复自己**：在剩下的 size-1 个里挑
                val shifted = Math.floorMod(randomPick, size - 1)
                val candidate = if (shifted >= currentIndex) shifted + 1 else shifted
                candidate.coerceIn(0, size - 1)
            }
        }
    }
}
