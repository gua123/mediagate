package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核的可用清单与轮转（纯函数）。
 *
 * **2026-11-12 用户要求**：「将视频播放引擎更改为 libvlc，并设置为硬解模式，然后根据连接的局域网
 * 或公网改变缓存的配置」⇒ 契约层只报 LibVLC 一个可用内核（界面不再出现「内核」切换入口）；
 * Media3 只作为内部兜底（探针失败/构造异常），不进入可选清单。
 *
 * 这一组测试钉住两件事：① 可用清单里只有 VLC；② 旧入口来问轮转时**原地返回 VLC**，
 * 不会把用户带到一个已下线的内核（自研引擎）或偷偷切回 Media3。
 */
class EngineCycleTest {

    @Test
    fun 只有一个内核可用() {
        assertEquals(listOf(EngineKind.VLC), EngineSelection.availableEngines())
        assertFalse("只有一个内核时不该显示切换入口", EngineSelection.canSwitchEngine())
    }

    @Test
    fun 轮转始终回到唯一的_LibVLC() {
        // 就算有人拿着旧参数来问，也只返回当前唯一可用的内核
        assertEquals(EngineKind.VLC, EngineSelection.cycleNext(EngineKind.MEDIA3, canUseVlc = true))
        assertEquals(EngineKind.VLC, EngineSelection.cycleNext(EngineKind.MEDIA3, canUseVlc = false))
        assertEquals(EngineKind.VLC, EngineSelection.cycleNext(EngineKind.VLC, canUseVlc = true))
        assertEquals(EngineKind.VLC, EngineSelection.cycleNext(EngineKind.NATIVE, canUseVlc = true))
    }

    @Test
    fun 三种解码档位都落在_LibVLC() {
        // 硬/软解都由 VlcEngine 落地：自动/强制硬解走 mediacodec，强制软解走 :codec=all
        DecoderMode.entries.forEach { mode ->
            assertEquals(mode.name, EngineKind.VLC, EngineSelection.preferredFor(mode))
        }
    }

    @Test
    fun 偏好里的旧内核都解析成_LibVLC() {
        assertEquals(EngineKind.VLC, EngineSelection.resolvePreference(EngineKind.MEDIA3))
        assertEquals(EngineKind.VLC, EngineSelection.resolvePreference(EngineKind.NATIVE))
        assertEquals(EngineKind.VLC, EngineSelection.resolvePreference(EngineKind.VLC))
    }

    @Test
    fun LibVLC_出错时退回_Media3_其它内核不降级() {
        assertEquals("视频默认内核出错要能退回内部兜底", EngineKind.MEDIA3, EngineSelection.fallbackFrom(EngineKind.VLC))
        assertEquals("Media3 已是兜底", null, EngineSelection.fallbackFrom(EngineKind.MEDIA3))
        assertEquals("自研引擎已下线", null, EngineSelection.fallbackFrom(EngineKind.NATIVE))
        // 反向钉住：只要哪天又加了第二个可用内核，这条断言会提醒补降级链
        assertTrue("可用内核超过一个时本测试要跟着更新", EngineSelection.availableEngines().size <= 1)
    }
}
