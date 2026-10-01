package io.github.gua123.mediagate.feature.home

import io.github.gua123.mediagate.feature.browser.RootModeKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「当前根目录」卡片的展示口径（**R12 + R8 的汇合点**）。
 *
 * 钉住的是那个曾经说不清的状态：**远端连接生效时，本地根目录只是"备用"**，
 * 卡片必须先说"正在用远端"，否则用户会以为自己在看本地目录。
 */
class HomeRootUiTest {

    @Test
    fun `没有远端连接时就是普通本地根目录`() {
        val root = HomeRootUi(mode = RootModeKind.SAF, displayPath = "Movies")

        assertTrue(root.configured)
        assertFalse(root.remoteActive)
        assertFalse("没有远端就谈不上备用", root.localStandby)
    }

    @Test
    fun `远端生效且本地也配过——本地是备用`() {
        val root = HomeRootUi(
            mode = RootModeKind.ALL_FILES,
            displayPath = "/storage/emulated/0",
            allFilesGranted = true,
            remoteLabel = "homedev · 局域网",
        )

        assertTrue(root.remoteActive)
        assertTrue("本地保留着，只是不生效", root.localStandby)
        assertTrue(root.configured)
    }

    @Test
    fun `远端生效但本地没配过——不该说成备用`() {
        val root = HomeRootUi(mode = RootModeKind.NONE, remoteLabel = "homedev · 局域网")

        assertTrue(root.remoteActive)
        assertFalse(root.configured)
        assertFalse("没配过就没有「备用目录」一说", root.localStandby)
    }

    @Test
    fun `空字符串的远端名不算远端`() {
        assertFalse(HomeRootUi(remoteLabel = "").remoteActive)
        assertFalse(HomeRootUi(remoteLabel = "   ").remoteActive)
        assertFalse(HomeRootUi(remoteLabel = null).remoteActive)
    }
}
