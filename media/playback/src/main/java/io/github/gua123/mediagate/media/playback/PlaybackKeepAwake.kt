package io.github.gua123.mediagate.media.playback

/**
 * 后台播放的**保活窗口**判定（唤醒锁 / Wi-Fi 锁该不该持有、该不该续期）。
 *
 * 为什么单独抽成纯逻辑：锁屏后 CPU 休眠、Wi-Fi 进省电都是**只有真机才看得见**的行为，
 * JVM 测不了；但「什么时候持有、什么时候放」是纯判定，必须能在 JVM 上钉死——
 * 这条判定的两端都会出事：
 * - **放得太早**（内核 `isPlaying` 一抖就 release）：锁屏瞬间 CPU 直接睡下去，
 *   SFTP/WebDAV 的读在 15 秒读超时后抛错，数据流断掉；而此时没有唤醒锁，
 *   进程再也醒不回来——这就是 2026-11-20 用户报的「锁屏之后连接断掉了」的**自锁环**；
 * - **放得太晚**（永不释放）：暂停、播完、退出播放页还亮着锁，白耗电。
 *
 * 因此口径是：**会话还在 + 正在播 → 持有；暂停后留一小段宽限期（覆盖缓冲抖动 / 换集空档）；
 * 会话收起（停止播放 / 退出播放页）→ 立刻释放**。
 */
object PlaybackKeepAwake {

    /**
     * 暂停后仍持有保活锁的宽限期（毫秒）。
     *
     * 30 秒的来源：内核在缓冲、换集、seek 之后都会短暂 `isPlaying == false`，
     * 而 SFTP 的单次读超时是 15 秒——宽限期必须明显大于它，否则「抖动一次就把锁放掉」
     * 正好会踩进读超时。真暂停/退出超过 30 秒就不该再占着锁。
     */
    const val PAUSE_GRACE_MS = 30_000L

    /** 唤醒锁到期前多久续期（毫秒）：避免「到期那一刻没锁」被系统冻住主线程。 */
    const val RENEW_LEAD_MS = 60_000L

    /**
     * 现在要不要持有保活锁（唤醒锁 + Wi-Fi 锁）。
     *
     * @param sessionAlive 会话是否还在（播放页没退出、内核没释放）
     * @param playing 内核自报是否在播
     * @param msSinceLastPlaying 距最后一次自报在播的毫秒数；从没播过传 [Long.MAX_VALUE]
     */
    fun shouldHold(sessionAlive: Boolean, playing: Boolean, msSinceLastPlaying: Long): Boolean {
        if (!sessionAlive) return false
        if (playing) return true
        return msSinceLastPlaying in 0..PAUSE_GRACE_MS
    }

    /**
     * 已持有的唤醒锁要不要续期。
     *
     * 带超时的 acquire 是「万一某条异常路径没释放」的兜底；但兜底不该变成「每 30 分钟断一次」，
     * 所以在到期前主动续一次（[heldMs] 是已经持有了多久）。
     *
     * @param heldMs 已持有毫秒数；负数表示当前没持有
     * @param timeoutMs 这把锁的 acquire 超时；`<= 0` 表示没有超时（不用续）
     */
    fun shouldRenew(heldMs: Long, timeoutMs: Long): Boolean {
        if (heldMs < 0L) return false
        if (timeoutMs <= 0L) return false
        // 超时本身比续期提前量还短（单测/特殊场景）：过半就续，别等到过期
        if (timeoutMs <= RENEW_LEAD_MS) return heldMs >= timeoutMs / 2
        return heldMs >= timeoutMs - RENEW_LEAD_MS
    }
}
