package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核的可用清单与轮转（纯函数）。
 *
 * **2026-10-15 用户拍板之后**：只保留原有引擎（Media3）——自研引擎在真机上能切过去但**不出画面**，
 * LibVLC 在这台机器上会把 App 整个带走（历史面包屑为证）。所以这一组测试同时钉住两件事：
 * ① 契约层只报一个可用内核（界面据此隐藏「内核」按钮）；② 万一还有旧入口来问轮转，**原地不动**而不是给出别的内核。
 */
class EngineCycleTest {

    @Test
    fun 只有一个内核可用() {
        assertEquals(listOf(EngineKind.MEDIA3), EngineSelection.availableEngines())
        assertFalse("只有一个内核时不该显示切换入口", EngineSelection.canSwitchEngine())
    }

    @Test
    fun 轮转不再给出下线的内核() {
        // 就算有人拿着旧参数来问，也不许给出 VLC / 自研引擎
        assertEquals(EngineKind.MEDIA3, EngineSelection.cycleNext(EngineKind.MEDIA3, canUseVlc = true))
        assertEquals(EngineKind.MEDIA3, EngineSelection.cycleNext(EngineKind.MEDIA3, canUseVlc = false))
        assertEquals(EngineKind.MEDIA3, EngineSelection.cycleNext(EngineKind.VLC, canUseVlc = true))
        assertEquals(EngineKind.MEDIA3, EngineSelection.cycleNext(EngineKind.NATIVE, canUseVlc = true))
    }

    @Test
    fun 三种解码档位都落在_Media3() {
        // 强制软解不再靠 LibVLC：Media3 自己就能按 softwareOnly 筛解码器（ExoPlayerEngine.codecSelectorFor）
        DecoderMode.entries.forEach { mode ->
            assertEquals(mode.name, EngineKind.MEDIA3, EngineSelection.preferredFor(mode))
        }
    }

    @Test
    fun 没有别的内核可退时降级目标为空() {
        EngineKind.entries.forEach { kind ->
            assertEquals("从 " + kind.name + " 不该还能退到别的内核", null, EngineSelection.fallbackFrom(kind))
        }
        // 反向钉住：只要哪天又加了第二个可用内核，这条断言会提醒补降级链
        assertTrue("可用内核超过一个时本测试要跟着更新", EngineSelection.availableEngines().size <= 1)
    }
}
