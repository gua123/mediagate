package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内核按钮的轮转顺序（纯函数）：过渡期三个内核并存，但交互上只有一个按钮。
 *
 * 顺序：Media3 → 自研引擎 → LibVLC → Media3。
 */
class EngineCycleTest {

    @Test
    fun 从_Media3_出发先轮到自研引擎() {
        assertEquals(EngineKind.NATIVE, EngineSelection.cycleNext(EngineKind.MEDIA3, canUseVlc = true))
    }

    @Test
    fun 从自研引擎轮到_LibVLC_再回到_Media3() {
        assertEquals(EngineKind.VLC, EngineSelection.cycleNext(EngineKind.NATIVE, canUseVlc = true))
        assertEquals(EngineKind.MEDIA3, EngineSelection.cycleNext(EngineKind.VLC, canUseVlc = true))
    }

    @Test
    fun LibVLC_已验证不可用时跳过它() {
        // 从自研引擎出发：本该轮到 LibVLC，但它跑不了 ⇒ 直接回到 Media3
        assertEquals(EngineKind.MEDIA3, EngineSelection.cycleNext(EngineKind.NATIVE, canUseVlc = false))
        // 从 Media3 出发不受影响（下一档是自研引擎）
        assertEquals(EngineKind.NATIVE, EngineSelection.cycleNext(EngineKind.MEDIA3, canUseVlc = false))
    }

    @Test
    fun 轮转三下一定回到原点() {
        var kind = EngineKind.MEDIA3
        repeat(3) { kind = EngineSelection.cycleNext(kind, canUseVlc = true) }
        assertEquals(EngineKind.MEDIA3, kind)
    }
}
