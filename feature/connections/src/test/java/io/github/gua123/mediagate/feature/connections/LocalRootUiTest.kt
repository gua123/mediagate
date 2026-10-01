package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接页「本地目录」卡片的按钮语义（**R12**）。
 *
 * 关键是全盘入口那一个按钮要按权限状态切换含义：没授权 = 去开启，已授权 = 直接切过去。
 * 混了的话用户会点了没反应（或反复跳设置页）。
 */
class LocalRootUiTest {

    @Test
    fun `未选择时不显示清除且全盘按钮是去开启`() {
        val ui = LocalRootUi()

        assertFalse(ui.configured)
        assertFalse(ui.canClear)
        assertEquals(AllFilesAction.REQUEST_PERMISSION, ui.allFilesAction)
    }

    @Test
    fun `已授权全盘时按钮直接切过去`() {
        val ui = LocalRootUi(mode = LocalRootMode.SAF, displayPath = "Movies", allFilesGranted = true)

        assertEquals(AllFilesAction.USE, ui.allFilesAction)
        assertTrue(ui.canClear)
    }

    @Test
    fun `选了目录但没授权时按钮仍是去开启`() {
        val ui = LocalRootUi(mode = LocalRootMode.SAF, displayPath = "Movies", allFilesGranted = false)

        assertTrue(ui.configured)
        assertEquals(AllFilesAction.REQUEST_PERMISSION, ui.allFilesAction)
    }
}
