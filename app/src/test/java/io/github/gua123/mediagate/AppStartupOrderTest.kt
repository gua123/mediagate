package io.github.gua123.mediagate

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动顺序护栏（**2026-10-03 P0 之后加的**）。
 *
 * 事故：0.1.22 起把 `container.vlcProbeNow()` 写在了 `container = AppContainer(this)` **之前**，
 * `container` 是 lateinit ⇒ `UninitializedPropertyAccessException` ⇒ **每次启动即崩**，
 * 用户看到的就是"更新后直接打不开了"（0.1.22–0.1.25 四个版本都中招）。
 *
 * 这种错误编译器不会拦（lateinit 就是让你自己保证顺序），真机才会炸，所以在这里用源码检查兜住：
 * `onCreate` 里任何 `container.` 的访问都必须出现在赋值语句之后。
 */
class AppStartupOrderTest {

    @Test
    fun `onCreate 不得在 container 赋值前访问它`() {
        val file = File("src/main/java/io/github/gua123/mediagate/MediaGateApplication.kt")
        assertTrue("找不到源文件（测试工作目录应为 :app 模块）：" + file.absolutePath, file.exists())
        val source = file.readText()
        val body = source.substringAfter("override fun onCreate()", "")
        assertTrue("解析不到 onCreate 方法体", body.isNotEmpty())
        val assign = body.indexOf("container = AppContainer(")
        assertTrue("onCreate 里应当给 container 赋值", assign >= 0)
        val violations = Regex("""\bcontainer\.""")
            .findAll(body)
            .map { it.range.first }
            .filter { it < assign }
            .toList()
        assertTrue(
            "container 在赋值之前被访问（lateinit 未初始化 → 启动即崩）：偏移 " + violations,
            violations.isEmpty(),
        )
    }

    @Test
    fun `容器自启动动作必须写在 AppContainer 的 init 里`() {
        // 探针这类"建好之后自己跑"的动作必须留在容器内部，Application 里就不需要（也不能）调它
        val container = File("src/main/java/io/github/gua123/mediagate/app/AppContainer.kt")
        assertTrue(container.exists())
        assertTrue("AppContainer 应自己调度启动探针", container.readText().contains("vlcProbeNow()"))
    }
}
