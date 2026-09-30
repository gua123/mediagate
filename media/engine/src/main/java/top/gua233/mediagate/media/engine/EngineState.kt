package io.github.gua123.mediagate.media.engine

/**
 * 播放内核状态机（plan 4.6 要求 `StateFlow<EngineState>`，R9/R10 切换时以它为准）。
 *
 * 迁移：IDLE → PREPARING → READY →（ENDED | ERROR）；seek/换源会回到 PREPARING。
 * 引擎实现负责把各自的回调（ExoPlayer 的 Player.Listener、LibVLC 的 MediaPlayer.Event）
 * 归一到这里，上层 UI 不需要认识两个内核的私有事件。
 */
sealed interface EngineState {

    /** 没有装载媒体（刚构造或已释放）。 */
    data object Idle : EngineState

    /** 正在打开/缓冲（切换内核时也会短暂回到这里）。 */
    data object Preparing : EngineState

    /** 可播/在播。 */
    data object Ready : EngineState

    /** 播放到结尾。 */
    data object Ended : EngineState

    /**
     * 出错（R10 自动降级的触发点：Media3 报错 → 提示并一键切 VLC 同位置续播）。
     *
     * @property message 面向用户的中文说明。
     * @property cause 原始异常（诊断页用，可为 null）。
     */
    data class Error(val message: String, val cause: Throwable? = null) : EngineState
}
