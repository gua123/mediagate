package io.github.gua123.mediagate

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话服务声明护栏（**2026-10-15 真机事故之后加的**）。
 *
 * 事故：视频后台播放服务在清单里少了 Media3 的会话 action（音频那个服务有），于是
 * SessionToken(context, ComponentName(context, VideoPlaybackService::class.java)) 抛
 * IllegalArgumentException: Failed to resolve SessionToken for ComponentInfo{…VideoPlaybackService}.
 * Manifest doesn't declare one of either MediaSessionService, …
 *
 * 为什么危害大：**起播那条路把异常吞了**（只是通知栏/锁屏控制一直连不上，没人发现），
 * 而**换内核那条路没吞** ⇒ 用户点「内核」切到自研引擎时被判成「这个内核播不了」⇒ 自动回退 Media3，
 * 于是新引擎**根本切不过去**（用户真机日志原样如此）。
 *
 * 为什么必须用源码级护栏：清单少一个 action，**编译器、Lint、单测、构建全都不报**，
 * 只有在真机上连会话时才炸；而且它横跨两个文件（源码里的 SessionToken 调用点 ↔ 清单里的服务声明），
 * 所以这条护栏就按这个交叉关系来钉——**源码里凡是按组件连会话的地方，清单里那个服务必须有 action**。
 */
class SessionServiceDeclarationTest {

    @Test
    fun 凡是用_SessionToken_连的服务_清单里都要声明会话_action() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("找不到清单（测试工作目录应为 :app 模块）：" + manifest.absolutePath, manifest.exists())
        val manifestText = manifest.readText()

        // :app 全部主源码里「按组件连会话」的调用点：SessionToken(<ctx>, ComponentName(<ctx>, X::class.java))
        val mainDir = File("src/main/java")
        val pattern = Regex(
            "SessionToken\\(\\s*[A-Za-z0-9_.]+\\s*,\\s*ComponentName\\(\\s*[A-Za-z0-9_.]+\\s*,\\s*([A-Za-z0-9_]+)::class\\.java",
        )
        val services = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        assertTrue(
            "一个会话服务都没扫到——护栏自己失效了（调用点写法变了？），需要同步更新本测试",
            services.isNotEmpty(),
        )

        services.forEach { simpleName ->
            val block = serviceBlock(manifestText, simpleName)
            assertNotNull("清单里没有声明服务 " + simpleName + "（源码却在连它）", block)
            assertTrue(
                simpleName + " 缺 androidx.media3.session.MediaSessionService 这个 action —— " +
                    "SessionToken 会用 queryIntentServices 反查服务类型，查不到就抛 IllegalArgumentException，" +
                    "真机上的表现是「换内核失败 → 自动回退」",
                block!!.contains("androidx.media3.session.MediaSessionService"),
            )
        }
    }

    /**
     * 取清单里某个服务的声明块（从 service 开标签到 /> 或 service 闭标签）。
     *
     * ⚠️ 锚点必须是 **android:name 属性**，不能是「服务名第一次出现的位置」——注释里也会提到服务名
     * （本文件记录的那次事故，注释里就写了 VideoPlaybackService），那样会往前抓到**上一个**服务
     * （音频那个），而它恰好带着会话 action ⇒ 护栏**假绿**。
     * 这个坑是拿「删掉视频服务的 action」做破坏时当场发现的：破坏之后测试照样通过。
     */
    private fun serviceBlock(manifest: String, simpleName: String): String? {
        val quote = '"'
        val nameAt = Regex("android:name=" + quote + "[^" + quote + "]*" + Regex.escape(simpleName) + quote)
            .find(manifest)?.range?.first ?: return null
        val start = manifest.lastIndexOf("<service", nameAt)
        if (start < 0) return null
        val selfClosing = manifest.indexOf("/>", nameAt)
        val withBody = manifest.indexOf("</service>", nameAt)
        val end = when {
            selfClosing >= 0 && (withBody < 0 || selfClosing < withBody) -> selfClosing + 2
            withBody >= 0 -> withBody + 9
            else -> return null
        }
        return manifest.substring(start, end)
    }
}
