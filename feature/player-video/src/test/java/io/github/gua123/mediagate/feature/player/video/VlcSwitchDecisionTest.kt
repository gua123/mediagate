package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Test

/** 点内核的判定（2026-10-03 用户：「我没找到测试内核的地方」→ 把测试放进这条必经路径）。 */
class VlcSwitchDecisionTest {

    @Test
    fun `切回去用 Media3 就直接切，不需要测试`() {
        assertEquals(VlcKernelTap.DIRECT, vlcKernelTap(targetIsVlc = false, vlcUsable = null))
        assertEquals(VlcKernelTap.DIRECT, vlcKernelTap(targetIsVlc = false, vlcUsable = false))
    }

    @Test
    fun `已验证可用就直接切`() {
        assertEquals(VlcKernelTap.DIRECT, vlcKernelTap(targetIsVlc = true, vlcUsable = true))
    }

    @Test
    fun `没测过就弹窗，主按钮是先测试再切换`() {
        assertEquals(VlcKernelTap.ASK_WITH_TEST, vlcKernelTap(targetIsVlc = true, vlcUsable = null))
    }

    @Test
    fun `测过且不可用就不给切`() {
        assertEquals(VlcKernelTap.BLOCKED, vlcKernelTap(targetIsVlc = true, vlcUsable = false))
    }
}
