package io.github.gua123.mediagate.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页信息架构（**R21**，2026-10-15 用户要求「优化一下设置页面的信息排列等」）的护栏。
 *
 * 为什么值得钉住：设置页是"一张一张卡片攒出来的"，最容易的退化就是**顺序又乱了**、
 * 或者**新加了一组却忘了接进页面**（用户看到的是"我加的功能不见了"）。
 *
 * 关于"忘了接"这条：**整个分支被删掉**是编译器挡的（`when` 对枚举要求穷尽，实测删掉一个分支直接编译失败），
 * 所以本测试管的是编译器管不了的那种——**分支还在、但里面是空的**（`-> Unit`），
 * 那种写法编译通过、页面少一块，只有源码级检查能发现（已实证能红）。
 */
class SettingsLayoutTest {

    @Test
    fun 分组顺序按使用频率排_排障垫底() {
        // 显式写死期望顺序（不是"等于 entries"）：顺序本身就是需求，改了必须有人过一遍
        assertEquals(
            listOf(
                SettingsGroup.CONNECTION,
                SettingsGroup.PLAYBACK,
                SettingsGroup.NETWORK,
                SettingsGroup.UPDATE,
                SettingsGroup.SECURITY,
                SettingsGroup.DIAGNOSTICS,
            ),
            settingsGroupOrder(),
        )
    }

    @Test
    fun 只有诊断组默认折叠() {
        val collapsed = settingsGroupOrder().filter { it.collapsedByDefault }
        assertEquals("排障用的那组才默认折叠", listOf(SettingsGroup.DIAGNOSTICS), collapsed)
    }

    @Test
    fun 每组都要有标题和一句说明_而且互不重复() {
        val titles = settingsGroupOrder().map { it.titleRes }
        val subtitles = settingsGroupOrder().map { it.subtitleRes }
        assertEquals("标题不许重复（否则两组看起来是同一组）", titles.size, titles.toSet().size)
        assertEquals("说明不许重复", subtitles.size, subtitles.toSet().size)
        assertTrue("资源 id 必须是真资源（0 是占位）", titles.none { it == 0 } && subtitles.none { it == 0 })
    }

    @Test
    fun 没给内容的分组不渲染空卡片() {
        val empty = SettingsSections.EMPTY.groupsWithContent()
        assertEquals("连接与播放是内置内容，恒在", setOf(SettingsGroup.CONNECTION, SettingsGroup.PLAYBACK), empty)

        val full = SettingsSections(
            playback = {},
            network = {},
            update = {},
            security = {},
            diagnostics = {},
        ).groupsWithContent()
        assertEquals("五个插槽都给齐时六组全在", settingsGroupOrder().toSet(), full)
    }

    @Test
    fun 说明要归到它解释的分组里() {
        val notes = listOf(
            SettingsNote("选路", "…", SettingsGroup.CONNECTION),
            SettingsNote("缓存", "…", SettingsGroup.NETWORK),
            SettingsNote("凭据", "…", SettingsGroup.SECURITY),
        )
        assertEquals(listOf("选路"), settingsNotesFor(notes, SettingsGroup.CONNECTION).map { it.title })
        assertEquals(listOf("缓存"), settingsNotesFor(notes, SettingsGroup.NETWORK).map { it.title })
        assertTrue("没有说明的组返回空（界面据此不渲染说明卡）", settingsNotesFor(notes, SettingsGroup.UPDATE).isEmpty())
    }

    // ------------------------------------------------------------------ 源码级护栏

    @Test
    fun 页面必须按分组渲染_而且不许回到整体追加的老写法() {
        val screen = settingsScreenSource()
        assertTrue("页面要按 settingsGroupOrder() 渲染", screen.contains("settingsGroupOrder()"))
        assertFalse(
            "R21 之前的 extraSections（整体追加在最后）不该回来——那样分组就是摆设",
            screen.contains("extraSections"),
        )
    }

    @Test
    fun 每个分组的分支里都要有真内容() {
        val block = settingsWhenBlock(settingsScreenSource())
        settingsGroupOrder().forEach { group ->
            val branch = branchFor(block, group)
            assertNotNull("分组 " + group.name + " 没接进页面", branch)
            val body = branch!!.substringAfter("->").replace("\\s+".toRegex(), "")
            assertFalse(
                "分组 " + group.name + " 的分支是空的（接了等于没接）",
                body.isEmpty() || body == "Unit",
            )
        }
    }

    private fun settingsScreenSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java/io/github/gua123/mediagate/feature/settings/SettingsScreen.kt")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 SettingsScreen.kt")
    }

    /** 抠出 `when (group) { … }` 这一段（按花括号配对，够用且不依赖正则嵌套）。 */
    private fun settingsWhenBlock(source: String): String {
        val start = source.indexOf("when (group) {")
        assertTrue("页面里找不到 when (group) 分支", start >= 0)
        val begin = source.indexOf('{', start)
        var depth = 0
        var i = begin
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(begin, i + 1)
                }
            }
            i++
        }
        error("when 块没有闭合")
    }

    /** 取某个分组的分支文本（到下一个分组分支或块尾为止）。 */
    private fun branchFor(block: String, group: SettingsGroup): String? {
        val key = "SettingsGroup." + group.name
        val at = block.indexOf(key)
        if (at < 0) return null
        val next = block.indexOf("SettingsGroup.", at + key.length)
        return block.substring(at, if (next >= 0) next else block.length)
    }
}
