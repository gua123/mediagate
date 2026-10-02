package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 循环档位与"播完去哪儿"的用例。 */
class VideoLoopModeTest {

    @Test
    fun 点一下按顺序换档并回到不循环() {
        assertEquals(VideoLoopMode.FOLDER, VideoLoopMode.OFF.next())
        assertEquals(VideoLoopMode.SINGLE, VideoLoopMode.FOLDER.next())
        assertEquals(VideoLoopMode.RANDOM, VideoLoopMode.SINGLE.next())
        assertEquals(VideoLoopMode.OFF, VideoLoopMode.RANDOM.next())
    }

    @Test
    fun 不循环时播完就停() {
        assertNull(VideoLoopDecision.onEnded(VideoLoopMode.OFF, currentIndex = 0, size = 5))
    }

    @Test
    fun 单曲循环时重播自己() {
        assertEquals(VideoLoopDecision.REPLAY, VideoLoopDecision.onEnded(VideoLoopMode.SINGLE, 3, 5))
    }

    @Test
    fun 文件夹循环时按顺序走且末尾回到第一个() {
        assertEquals(1, VideoLoopDecision.onEnded(VideoLoopMode.FOLDER, 0, 5))
        assertEquals(4, VideoLoopDecision.onEnded(VideoLoopMode.FOLDER, 3, 5))
        assertEquals(0, VideoLoopDecision.onEnded(VideoLoopMode.FOLDER, 4, 5))
    }

    @Test
    fun 随机播放不会挑到自己() {
        for (pick in -7..7) {
            val next = VideoLoopDecision.onEnded(VideoLoopMode.RANDOM, currentIndex = 2, size = 5, randomPick = pick)!!
            assertTrue("挑到了自己：pick=" + pick, next != 2)
            assertTrue("越界：pick=" + pick, next in 0..4)
        }
    }

    @Test
    fun 只有一个文件时随机等于重播() {
        assertEquals(VideoLoopDecision.REPLAY, VideoLoopDecision.onEnded(VideoLoopMode.RANDOM, 0, 1))
    }

    @Test
    fun 空队列时什么都不做() {
        assertNull(VideoLoopDecision.onEnded(VideoLoopMode.FOLDER, 0, 0))
        assertFalse(VideoLoopMode.OFF.zhText.isBlank())
    }
}
