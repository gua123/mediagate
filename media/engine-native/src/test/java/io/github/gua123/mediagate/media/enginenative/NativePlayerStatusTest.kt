package io.github.gua123.mediagate.media.enginenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态串解析的护栏：native 的格式一旦改了字段顺序/个数，这里就会失败。
 *
 * 为什么值得钉住：M4 把自研引擎接进 PlayerEngine 契约之后，进度、时长、
 * 音画偏差都要从这条串里读——解析错了界面就会显示错的位置。
 */
class NativePlayerStatusTest {

    @Test
    fun 完整状态串要能逐字段解析出来() {
        val raw = "播放中|12500|7200000|301|4|1920x1080|h264|aac|48000|2|0|-3|"
        val status = NativePlayerStatus.parse(raw)
        requireNotNull(status)
        assertEquals("播放中", status.state)
        assertEquals(12_500L, status.positionMs)
        assertEquals(7_200_000L, status.durationMs)
        assertEquals(301L, status.frames)
        assertEquals(4L, status.droppedFrames)
        assertEquals(1920, status.width)
        assertEquals(1080, status.height)
        assertEquals("h264", status.videoCodec)
        assertEquals("aac", status.audioCodec)
        assertEquals(48_000, status.sampleRate)
        assertEquals(2, status.channels)
        assertEquals(0L, status.audioUnderruns)
        assertEquals(-3L, status.avOffsetMs)
        assertTrue(status.hasAudio)
        assertNull(status.error)
    }

    @Test
    fun 没有音频的文件要把音频字段报成空() {
        val status = NativePlayerStatus.parse("播放中|1000|60000|25|0|1280x720|hevc||0|0|0|0|")
        requireNotNull(status)
        assertFalse(status.hasAudio)
        assertEquals("", status.audioCodec)
        assertTrue(status.describe().contains("没有音频"))
    }

    @Test
    fun 音频输出出问题时要能带出原因并依然显示位置() {
        val raw = "播放中|2000|60000|50|0|1280x720|hevc|aac|48000|2|3|12|打不开音频输出：系统错误码 -889"
        val status = NativePlayerStatus.parse(raw)
        requireNotNull(status)
        assertTrue(status.hasAudio)
        assertEquals(3L, status.audioUnderruns)
        assertEquals(12L, status.avOffsetMs)
        assertTrue(status.describe().contains("打不开音频输出"))
        assertTrue(status.describe().contains("欠载 3 次"))
    }

    @Test
    fun 错误串只带原因不带字段() {
        val status = NativePlayerStatus.parse("错误：这个文件里没有能解码的视频流")
        requireNotNull(status)
        assertEquals("出错", status.state)
        assertEquals("这个文件里没有能解码的视频流", status.error)
        assertTrue(status.describe().contains("没有能解码的视频流"))
    }

    @Test
    fun 起播与_seek_指标要能解析出来并排成一行() {
        val raw = "播放中|12500|7200000|301|4|1920x1080|h264|aac|48000|2|0|-3||380|120"
        val status = NativePlayerStatus.parse(raw)
        requireNotNull(status)
        assertEquals(380L, status.firstFrameMs)
        assertEquals(120L, status.lastSeekMs)
        val line = status.metricsLine()
        assertTrue(line.contains("起播 380 ms"))
        assertTrue(line.contains("seek 等待 120 ms"))
        assertTrue(line.contains("丢帧 4"))
        assertTrue(line.contains("音画偏差 -3 ms"))
    }

    @Test
    fun 老版本的状态串_没有指标字段也要能解析() {
        // 字段是 2026-10-15 追加的：少两个字段不能把整条状态串判成"不认识"
        val status = NativePlayerStatus.parse("播放中|1000|60000|25|0|1280x720|hevc|aac|48000|2|0|0|")
        requireNotNull(status)
        assertEquals(0L, status.firstFrameMs)
        assertEquals(0L, status.lastSeekMs)
        assertTrue("没 seek 过就不显示 seek 等待", !status.metricsLine().contains("seek 等待"))
    }

    @Test
    fun 不认识的串返回空而不是抛异常() {
        assertNull(NativePlayerStatus.parse(""))
        assertNull(NativePlayerStatus.parse("随便什么东西"))
        // 字段数不够（老版本 native 串）也不能崩：M4 会当成"状态不可用"
        assertNull(NativePlayerStatus.parse("播放中|1000|2000|3|0|640x480|h264"))
    }

    @Test
    fun 数字字段坏掉时退化成零而不是抛异常() {
        val status = NativePlayerStatus.parse("播放中|abc|def|3|0|?x?|h264|aac|x|2|0|0|")
        requireNotNull(status)
        assertEquals(0L, status.positionMs)
        assertEquals(0, status.width)
        assertFalse(status.hasAudio)
    }
}
