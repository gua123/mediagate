package io.github.gua123.mediagate

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动期"属性初始化顺序"护栏（**2026-10-03 第二个 P0 之后加的**）。
 *
 * 事故：`VideoPlayerHost` 里写了 `override val vlcUsable: Boolean? = vlcUsableState.value`，
 * 而 `vlcUsableState` 声明在容器**后面**——宿主是容器里较早构造的属性，构造到它时那个 flow 还是 null，
 * 于是 `Application.onCreate` → `AppContainer.<init>` 直接 NPE（R8 混淆后消息是
 * `Attempt to read from field ... on a null object reference in method ph.<init>`），
 * 用户看到的就是"打不开"，而且**编译器和单测都发现不了**（Kotlin 的 val 在 JVM 上默认就是 null）。
 *
 * 规则：启动关键文件里，**跨属性引用一律用 getter**（`get() = ...`），不许在初始化器里读别的属性的状态。
 */
class StartupPropertyOrderTest {

    private val guarded = listOf(
        "src/main/java/io/github/gua123/mediagate/app/AppContainer.kt",
        "src/main/java/io/github/gua123/mediagate/MediaGateApplication.kt",
    )

    @Test
    fun `不许用初始化器读取属性状态（会踩构造顺序）`() {
        // 只认「带类型标注的属性初始化器」（val x: T = ...value）：
        // 函数里的局部变量（val backend = videoBackend.value）运行期才执行，没有构造顺序问题；
        // 写成 getter（get() = ...）的正是我们要的写法，也要放过。
        val pattern = Regex(
            "^" + "\\s*" + "(override\\s+)?" + "val\\s+" + "\\w+" + "\\s*:\\s*" +
                "[^=\\n]+" + "=\\s*" + "[A-Za-z_]" + "[\\w.]*" + "\\.value" + "\\b",
        )
        val offenders = mutableListOf<String>()
        for (path in guarded) {
            val file = File(path)
            assertTrue("找不到文件：" + file.absolutePath, file.exists())
            file.readLines().forEachIndexed { index, line ->
                // 注释里会引用"错误写法"当例子，必须跳过，否则护栏自己误报
                val trimmed = line.trim()
                val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("*/")
                val isGetter = trimmed.contains("get() =")
                if (!isComment && !isGetter && pattern.containsMatchIn(line)) {
                    offenders += path + ":" + (index + 1) + " → " + trimmed
                }
            }
        }
        assertTrue(
            "这些属性在初始化器里读了别的属性的状态，构造顺序一变就是启动即崩；请改成 get() = … ：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
