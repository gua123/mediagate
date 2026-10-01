package io.github.gua123.mediagate.feature.player.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry

/**
 * [AudioPlayerMath] 的 JVM 单测（R1 音频）：进度格式化、seek 比例换算、队列下标轮转、倍速档位。
 *
 * 这些纯函数就是页面按钮背后的全部判断逻辑，覆盖它们等于覆盖了「点一下会发生什么」。
 */
class AudioPlayerMathTest {

    @Test
    fun `时长格式化 不足一小时用 分秒`() {
        assertEquals("0:00", AudioPlayerMath.formatDuration(0))
        assertEquals("0:05", AudioPlayerMath.formatDuration(5_400))
        assertEquals("1:05", AudioPlayerMath.formatDuration(65_000))
        assertEquals("59:59", AudioPlayerMath.formatDuration(3_599_000))
    }

    @Test
    fun `时长格式化 超过一小时用 时分秒`() {
        assertEquals("1:00:00", AudioPlayerMath.formatDuration(3_600_000))
        assertEquals("2:03:04", AudioPlayerMath.formatDuration(7_384_000))
    }

    @Test
    fun `时长未知给占位文案`() {
        assertEquals(AudioPlayerMath.UNKNOWN_TIME, AudioPlayerMath.formatDuration(-1))
    }

    @Test
    fun `seek 比例换算`() {
        assertEquals(0.5f, AudioPlayerMath.seekRatio(30_000, 60_000), 0.0001f)
        assertEquals(0f, AudioPlayerMath.seekRatio(0, 60_000), 0.0001f)
        assertEquals("超过时长要钳到 1", 1f, AudioPlayerMath.seekRatio(90_000, 60_000), 0.0001f)
        assertEquals("负位置钳到 0", 0f, AudioPlayerMath.seekRatio(-100, 60_000), 0.0001f)
        assertEquals("时长未知给 0", 0f, AudioPlayerMath.seekRatio(1_000, 0), 0.0001f)
    }

    @Test
    fun `比例换算回位置`() {
        assertEquals(30_000L, AudioPlayerMath.positionOfRatio(0.5f, 60_000))
        assertEquals(60_000L, AudioPlayerMath.positionOfRatio(2f, 60_000))
        assertEquals(0L, AudioPlayerMath.positionOfRatio(-1f, 60_000))
        assertEquals("时长未知给 0", 0L, AudioPlayerMath.positionOfRatio(0.5f, 0))
    }

    @Test
    fun `下一首 顺序播放停在最后一首 列表循环回到第一首`() {
        assertEquals(1, AudioPlayerMath.nextIndex(0, 3, AudioRepeatMode.OFF))
        assertEquals(2, AudioPlayerMath.nextIndex(1, 3, AudioRepeatMode.OFF))
        assertEquals("最后一首不再往后", 2, AudioPlayerMath.nextIndex(2, 3, AudioRepeatMode.OFF))
        assertEquals("列表循环绕回第一首", 0, AudioPlayerMath.nextIndex(2, 3, AudioRepeatMode.ALL))
        assertEquals("单曲循环不影响手动切歌", 2, AudioPlayerMath.nextIndex(2, 3, AudioRepeatMode.ONE))
        assertEquals("空队列给 0", 0, AudioPlayerMath.nextIndex(0, 0, AudioRepeatMode.ALL))
        assertEquals("越界下标先钳住", 2, AudioPlayerMath.nextIndex(9, 3, AudioRepeatMode.OFF))
    }

    @Test
    fun `上一首 顺序播放停在第一首 列表循环绕到最后一首`() {
        assertEquals(0, AudioPlayerMath.previousIndex(1, 3, AudioRepeatMode.OFF))
        assertEquals("第一首不再往前", 0, AudioPlayerMath.previousIndex(0, 3, AudioRepeatMode.OFF))
        assertEquals("列表循环绕到最后一首", 2, AudioPlayerMath.previousIndex(0, 3, AudioRepeatMode.ALL))
        assertEquals("单曲循环不影响手动切歌", 1, AudioPlayerMath.previousIndex(2, 3, AudioRepeatMode.ONE))
        assertEquals(0, AudioPlayerMath.previousIndex(0, 0, AudioRepeatMode.OFF))
        assertEquals("越界下标先钳住", 1, AudioPlayerMath.previousIndex(9, 3, AudioRepeatMode.ALL))
    }

    @Test
    fun `循环模式轮转一档一档走`() {
        assertEquals(AudioRepeatMode.ALL, AudioPlayerMath.nextRepeatMode(AudioRepeatMode.OFF))
        assertEquals(AudioRepeatMode.ONE, AudioPlayerMath.nextRepeatMode(AudioRepeatMode.ALL))
        assertEquals(AudioRepeatMode.OFF, AudioPlayerMath.nextRepeatMode(AudioRepeatMode.ONE))
    }

    @Test
    fun `倍速在四档之间轮转`() {
        assertEquals(listOf(0.5f, 1f, 1.5f, 2f), AudioPlayerMath.SPEEDS)
        assertEquals(1f, AudioPlayerMath.nextSpeed(0.5f), 0.0001f)
        assertEquals(1.5f, AudioPlayerMath.nextSpeed(1f), 0.0001f)
        assertEquals(2f, AudioPlayerMath.nextSpeed(1.5f), 0.0001f)
        assertEquals("2× 之后回到 0.5×", 0.5f, AudioPlayerMath.nextSpeed(2f), 0.0001f)
        assertEquals("不在档位里回到默认 1×", 1f, AudioPlayerMath.nextSpeed(3f), 0.0001f)
    }

    @Test
    fun `倍速文案整数不带小数点`() {
        assertEquals("1×", AudioPlayerMath.speedLabel(1f))
        assertEquals("2×", AudioPlayerMath.speedLabel(2f))
        assertEquals("0.5×", AudioPlayerMath.speedLabel(0.5f))
        assertEquals("1.5×", AudioPlayerMath.speedLabel(1.5f))
    }

    @Test
    fun `目录项只留音频并按名称排序`() {
        val entries = listOf(
            otherEntry("Music/cover.jpg"),
            audioEntry("Music/b.mp3"),
            audioEntry("Music/A.flac"),
            RemoteEntry(name = "sub", path = "Music/sub", isDirectory = true),
            otherEntry("Music/notes.txt"),
            audioEntry("Music/c.ogg"),
        )

        val audio = AudioPlayerMath.audioEntries(entries)

        assertEquals(listOf("Music/A.flac", "Music/b.mp3", "Music/c.ogg"), audio.map { it.path })
    }

    @Test
    fun `路径定位与文件名`() {
        val paths = listOf("a.mp3", "b.mp3")
        assertEquals(1, AudioPlayerMath.indexOfPath(paths, "b.mp3"))
        assertEquals(-1, AudioPlayerMath.indexOfPath(paths, "c.mp3"))
        assertEquals("song.mp3", AudioPlayerMath.fileNameOf("Music/2026/song.mp3"))
        assertEquals("song.mp3", AudioPlayerMath.fileNameOf("song.mp3"))
    }
}
