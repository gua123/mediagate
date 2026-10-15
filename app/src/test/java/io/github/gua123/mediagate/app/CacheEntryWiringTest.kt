package io.github.gua123.mediagate.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **缓存相关入口的接线护栏**（2026-10-15 用户要求：「将添加文件夹进队列和清理缓存界面放到一块」）。
 *
 * 为什么值得一条护栏：这类"把入口挪个地方"的需求，最容易出的错不是按钮画不出来，而是
 * **参数加了、按钮画了，但导航没接上**（点了没反应），或者反过来——整理页面时把入口整条删掉。
 * 编译器管不了这两种（参数有默认值、去掉也没人报错），所以在这里钉住。
 */
class CacheEntryWiringTest {

    private fun ui(name: String): String =
        File("src/main/java/io/github/gua123/mediagate/ui/" + name).readText()

    @Test
    fun 缓存明细页要带着添加文件夹的入口并真接上导航() {
        val screen = ui("CacheDetailScreen.kt")
        assertTrue("缓存明细页要接受添加文件夹的回调", screen.contains("onOpenFolderPicker"))
        assertTrue("而且要有真按钮（不是只有参数）", screen.contains("cache_detail_add_folder"))

        val host = ui("MediaGateApp.kt")
        assertTrue(
            "缓存明细页里点它必须真的跳到挑选页",
            host.contains("onOpenFolderPicker = { navController.navigate(CacheFolderPickerRoutes.ROUTE) }"),
        )
        assertTrue("挑选页本身要注册在导航表里", host.contains("composable(route = CacheFolderPickerRoutes.ROUTE)"))
    }

    @Test
    fun 浏览页长按仍然能把文件或文件夹加进缓存队列() {
        val host = ui("MediaGateApp.kt")
        assertTrue("浏览页要接长按", host.contains("onLongPressEntry = {"))
        assertTrue("长按之后要能入队", host.contains("container.enqueueCache("))
        assertTrue("文件夹要能递归统计", host.contains("container.collectCacheTargets("))
    }
}
