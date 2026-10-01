package io.github.gua123.mediagate.media.ffmpeg

/**
 * 执行 FFmpeg 命令的抽象（R11）。
 *
 * 之所以要接口：真机上由 [FfmpegKitRunner] 交给 ffmpeg-kit 的 native 库，JVM 单测里换成假实现，
 * 于是「命令怎么组、进度怎么算、失败怎么归因」都能在没有设备的情况下测（真跑 FFmpeg 需要手机）。
 *
 * 线程约定：实现不得阻塞调用方线程；回调线程由实现决定，[run] 的 [onProgress] 可能在任意线程触发，
 * 使用方自行保证线程安全（典型做法：只往 `MutableStateFlow` / `AtomicXxx` 里写）。
 *
 * 取消约定：调用方取消协程 = 取消本次会话（[FfmpegKitRunner] 内部调 `FFmpegKit.cancel(sessionId)`），
 * 此时 [run] 以 [kotlinx.coroutines.CancellationException] 结束，而不是返回 CANCELLED 结果。
 */
interface FfmpegRunner {

    /**
     * 跑一条命令直到结束。
     *
     * @param onProgress 进度回调（可空）；可能被高频调用（约每 0.5 s 一次）。
     * @return 会话结果；**不抛异常**（除协程取消）。
     */
    suspend fun run(command: FfmpegCommand, onProgress: ((FfmpegProgress) -> Unit)? = null): FfmpegResult

    /** 取消指定会话（幂等；会话已结束则无副作用）。 */
    fun cancel(sessionId: Long)

    /** 取消所有在跑的会话。 */
    fun cancelAll()

    /** 最近一次会话 id；从未跑过为 null（诊断页展示用）。 */
    val lastSessionId: Long?
}
