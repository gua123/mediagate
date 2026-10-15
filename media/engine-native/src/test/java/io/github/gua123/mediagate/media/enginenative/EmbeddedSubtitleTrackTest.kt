package io.github.gua123.mediagate.media.enginenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌字幕轨的解析与显示名（**纯函数**）。
 *
 * 为什么值得钉：native 返回的是一行一轨的竖线串（跨 JNI 边界只传字符串，边界要粗）；
 * 解析错了用户看到的是"字幕列表里少了一轨"或"点进去没反应"，而这两件事在真机上很难分辨。
 */
class EmbeddedSubtitleTrackTest {

    @Test
    fun 竖线串要能解成轨道列表() {
        val raw = "2|subrip|1|zh|简体中文\n3|hdmv_pgs_subtitle|0|eng|\n"
        val tracks = parseSubtitleTracks(raw)
        assertEquals(2, tracks.size)
        assertEquals(2, tracks[0].index)
        assertEquals("subrip", tracks[0].codec)
        assertTrue(tracks[0].textBased)
        assertEquals("zh", tracks[0].language)
        assertEquals("简体中文", tracks[0].title)

        // 图像类字幕照样列出来，但 textBased=false（界面据此说"暂不支持"）
        assertFalse(tracks[1].textBased)
        assertEquals("hdmv_pgs_subtitle", tracks[1].codec)
    }

    @Test
    fun 空串与错误串都返回空表() {
        assertTrue(parseSubtitleTracks("").isEmpty())
        assertTrue(parseSubtitleTracks("   ").isEmpty())
        assertTrue("错误串不能当成轨道（否则界面会显示一轨乱码）", parseSubtitleTracks("错误：打不开这个文件").isEmpty())
    }

    @Test
    fun 坏行要跳过而不是整体失败() {
        val raw = "2|subrip|1|zh|中文\n乱七八糟的一行\nnotanumber|ass|1||\n5|ass|1|ja|日本語"
        val tracks = parseSubtitleTracks(raw)
        assertEquals("只保留能解析的两行", 2, tracks.size)
        assertEquals(listOf(2, 5), tracks.map { it.index })
    }

    @Test
    fun 失败原因要能带出来给界面用() {
        // 与 listSubtitleTracksWithReason 相同的判据
        val raw = "错误：内嵌字幕是图像格式"
        val reason = raw.trim().takeIf { it.startsWith("错误：") }?.removePrefix("错误：")
        assertEquals("内嵌字幕是图像格式", reason)
        assertNull("正常返回时不该有原因", "2|subrip|1|zh|x".takeIf { it.startsWith("错误：") })
    }

    @Test
    fun 显示名要有层次且图像字幕要标注不支持() {
        val named = EmbeddedSubtitleTrack(1, "subrip", true, "zh", "简体中文")
        assertEquals("简体中文 · subrip", named.label)

        // 没有轨名就用语言的中文名
        val byLanguage = EmbeddedSubtitleTrack(2, "ass", true, "en", "")
        assertEquals("英语 · ass", byLanguage.label)

        // 都没有就用编码
        val bare = EmbeddedSubtitleTrack(3, "mov_text", true, "", "")
        assertEquals("mov_text · mov_text", bare.label)

        val image = EmbeddedSubtitleTrack(4, "hdmv_pgs_subtitle", false, "zh", "中文")
        assertTrue("图像字幕必须写明不支持", image.label.contains("图像字幕") && image.label.contains("暂不支持"))
    }
}
