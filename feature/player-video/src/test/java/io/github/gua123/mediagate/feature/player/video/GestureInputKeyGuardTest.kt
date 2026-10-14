package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级护栏（**2026-10-03 真机反馈**：「调整亮度和音量时，屏幕中间的提示会一直存在」）。
 *
 * 根因：播放页手势的 `pointerInput` 把 `state.volume / state.brightness` 当成了 key——而拖动过程中
 * 这两个值一直在变 ⇒ 手势协程被反复取消重建 ⇒ 收尾的 `commitVerticalGesture()` 永不执行 ⇒ HUD 挂住。
 * 这类问题 JVM 单测抓不到（要真机手势才复现），所以用"扫源码"的方式把约定钉住。
 *
 * 注意：检查函数**自己也要被测试**（第二例拿真实的历史坏代码片段验证它会报），否则就是个装饰品。
 */
class GestureInputKeyGuardTest {

    /** 找出所有"把 state.xxx 当 key"的 pointerInput 调用（返回它们的括号内文本）。 */
    private fun badPointerInputKeys(src: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = src.indexOf("pointerInput(", from)
            if (start < 0) break
            var i = start + "pointerInput(".length
            var depth = 1
            val sb = StringBuilder()
            while (i < src.length && depth > 0) {
                val c = src[i]
                if (c == '(') depth++
                if (c == ')') depth--
                if (depth > 0) sb.append(c)
                i++
            }
            val args = sb.toString()
            if (args.contains("state.")) out += args.trim()
            from = i
        }
        return out
    }

    private fun screenSource(): String {
        val candidates = listOf(
            File("src/main/java/io/github/gua123/mediagate/feature/player/video/VideoPlayerScreen.kt"),
            File("feature/player-video/src/main/java/io/github/gua123/mediagate/feature/player/video/VideoPlayerScreen.kt"),
            File("../feature/player-video/src/main/java/io/github/gua123/mediagate/feature/player/video/VideoPlayerScreen.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue("找不到播放页源码，护栏失效（路径变了要同步改）", file != null)
        return file!!.readText()
    }

    @Test
    fun 检查函数本身要认得出历史坏代码() {
        // 这就是修之前那一行（会导致 HUD 挂住）
        val old = ".pointerInput(state.durationMs, state.volume, state.brightness) { awaitEachGesture { } }"
        assertEquals(1, badPointerInputKeys(old).size)
        // 修好之后是空的
        assertEquals(0, badPointerInputKeys(".pointerInput(Unit) { }").size)
    }

    @Test
    fun 手势的_pointerInput_不许以_state_值为_key() {
        val bad = badPointerInputKeys(screenSource())
        assertTrue(
            "手势 pointerInput 不能把 state.xxx 当 key（拖动中值会变 ⇒ 手势被取消重建 ⇒ HUD 挂住）：$bad",
            bad.isEmpty(),
        )
    }
}
