package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放缓冲时间（2026-10-20，借鉴 nPlayer 的 buffer_time）：夹值与档位。 */
class PlaybackBufferPolicyTest {

    @Test
    fun 默认五秒_档位齐全() {
        assertEquals(5, PlaybackBufferPolicy.DEFAULT_SECONDS)
        assertTrue(PlaybackBufferPolicy.SECONDS_CHOICES.contains(5))
        assertTrue(PlaybackBufferPolicy.SECONDS_CHOICES.contains(30))
    }

    @Test
    fun 每个档位都能构造合法的LoadControl() {
        // 回归 0.1.82 真机「播放失败：minBufferMs cannot be less than bufferForPlaybackMs」：
        // 原来 minBuffer 取目标一半、bufferForPlayback 取目标本身，Media3 直接拒绝。
        PlaybackBufferPolicy.SECONDS_CHOICES.forEach { seconds ->
            PlaybackBufferPolicy.loadControl(seconds)
        }
        PlaybackBufferPolicy.loadControl(PlaybackBufferPolicy.DEFAULT_SECONDS)
    }

    @Test
    fun 越界与坏值退回默认() {
        assertEquals(5, PlaybackBufferPolicy.clampSeconds(0))
        assertEquals(5, PlaybackBufferPolicy.clampSeconds(-1))
        assertEquals(5, PlaybackBufferPolicy.clampSeconds(31))
        assertEquals(3, PlaybackBufferPolicy.clampSeconds(3))
        assertEquals(30, PlaybackBufferPolicy.clampSeconds(30))
        assertEquals(10, PlaybackBufferPolicy.clampSeconds(10))
    }
}
