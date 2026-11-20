package io.github.gua123.mediagate.media.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台保活窗口的判定（**2026-11-20 锁屏停止播放** 的核心口径）。
 *
 * 为什么这些常量不能拍脑袋：宽限期必须明显大于 SFTP 的单次读超时（15 s），
 * 否则「锁屏瞬间内核报 false → 放锁 → CPU 睡 → 读超时 → 再也醒不回来」这条自锁环还会在。
 */
class PlaybackKeepAwakeTest {

    // ------------------------------------------------------------ 该不该持有

    @Test
    fun 会话没了就一定要放() {
        assertFalse(
            "播放页退出/内核释放后还持有锁 = 白耗电",
            PlaybackKeepAwake.shouldHold(sessionAlive = false, playing = true, msSinceLastPlaying = 0L),
        )
    }

    @Test
    fun 在播就持有() {
        assertTrue(PlaybackKeepAwake.shouldHold(sessionAlive = true, playing = true, msSinceLastPlaying = 0L))
    }

    @Test
    fun 暂停后的宽限期内仍然持有_盖住锁屏瞬间的抖动() {
        assertTrue(
            "锁屏瞬间内核会短暂报 false，这里放锁就是那条自锁环",
            PlaybackKeepAwake.shouldHold(sessionAlive = true, playing = false, msSinceLastPlaying = 5_000L),
        )
        assertTrue(PlaybackKeepAwake.shouldHold(sessionAlive = true, playing = false, msSinceLastPlaying = PlaybackKeepAwake.PAUSE_GRACE_MS))
    }

    @Test
    fun 宽限期过后就释放() {
        assertFalse(
            "真暂停超过宽限期还占着锁，就是永不释放",
            PlaybackKeepAwake.shouldHold(sessionAlive = true, playing = false, msSinceLastPlaying = PlaybackKeepAwake.PAUSE_GRACE_MS + 1L),
        )
    }

    @Test
    fun 从没播过就不持有() {
        assertFalse(PlaybackKeepAwake.shouldHold(sessionAlive = true, playing = false, msSinceLastPlaying = Long.MAX_VALUE))
    }

    @Test
    fun 宽限期必须长于远端读超时() {
        // SFTP/FTP/WebDAV 的默认读超时都是 15 s（SftpConfig.DEFAULT_READ_TIMEOUT_MS 等）；
        // 宽限期若不超过它，"抖一下"就会正好踩进读超时。
        assertTrue(
            "宽限期 " + PlaybackKeepAwake.PAUSE_GRACE_MS + " ms 必须明显大于 15 s 读超时",
            PlaybackKeepAwake.PAUSE_GRACE_MS >= 20_000L,
        )
    }

    // ------------------------------------------------------------ 该不该续期

    @Test
    fun 没持有就不用续() {
        assertFalse(PlaybackKeepAwake.shouldRenew(heldMs = -1L, timeoutMs = 30L * 60L * 1_000L))
    }

    @Test
    fun 没有超时的锁不用续() {
        assertFalse(PlaybackKeepAwake.shouldRenew(heldMs = 10L * 60L * 1_000L, timeoutMs = 0L))
    }

    @Test
    fun 到期前才续_不要每拍都重取() {
        val timeout = 30L * 60L * 1_000L
        assertFalse(
            "还早得很：每拍都 release+acquire 只是白折腾",
            PlaybackKeepAwake.shouldRenew(heldMs = 10L * 60L * 1_000L, timeoutMs = timeout),
        )
        assertTrue(
            "快到超时了：不续就会出现一段没有唤醒锁的空窗",
            PlaybackKeepAwake.shouldRenew(heldMs = timeout - PlaybackKeepAwake.RENEW_LEAD_MS, timeoutMs = timeout),
        )
    }

    @Test
    fun 超时很短的锁过半就续() {
        assertFalse(PlaybackKeepAwake.shouldRenew(heldMs = 1_000L, timeoutMs = 5_000L))
        assertTrue(PlaybackKeepAwake.shouldRenew(heldMs = 2_500L, timeoutMs = 5_000L))
    }
}
