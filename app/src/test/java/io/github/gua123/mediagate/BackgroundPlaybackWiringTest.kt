package io.github.gua123.mediagate

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **锁屏后视频还在播**的四条接线护栏（2026-11-20 用户报告：锁屏后播放停止）。
 *
 * 这些东西全都「编译器看得过、单测跑得绿、真机才炸」：
 * 1. 清单里前台服务类型/权限/stopWithTask —— 缺一项就是「锁屏后进程被收走」；
 * 2. 服务里两把锁（CPU 唤醒锁 + Wi-Fi 锁）与释放路径 —— 缺 Wi-Fi 锁就是「锁屏后 Wi-Fi 睡、读超时」；
 * 3. 会话服务必须被 startService（不能只 bind）—— bound-only 服务的 onTaskRemoved 永不回调；
 * 4. 播放中不许因网络现场变化重建远端后端 —— 重建会 close 掉正在被代理读的 SFTP/WebDAV 会话。
 *
 * 用源码级护栏的理由与本项目既有的 SessionServiceDeclarationTest 一致：这类约束横跨清单与源码，
 * 没有编译期检查，只能"把事实钉在文字上"。
 */
class BackgroundPlaybackWiringTest {

    private val manifest = File("src/main/AndroidManifest.xml")

    private val serviceSource = File(
        "src/main/java/io/github/gua123/mediagate/app/VideoSessionController.kt",
    )

    // ------------------------------------------------------------ ① 前台服务声明

    @Test
    fun 视频前台服务必须是_mediaPlayback_且不可随任务一起回收() {
        assertTrue("找不到清单：" + manifest.absolutePath, manifest.exists())
        val block = serviceBlock(manifest.readText(), "VideoPlaybackService")
        assertNotNull("清单里没有 VideoPlaybackService 的声明", block)
        assertTrue(
            "前台服务类型必须是 mediaPlayback（Android 14+ 起类型与权限对不上就是 SecurityException）",
            block!!.contains("android:foregroundServiceType=" + quote("mediaPlayback")),
        )
        assertTrue(
            "必须显式 stopWithTask=false：锁屏/划掉最近任务都不是「停止播放」",
            block.contains("android:stopWithTask=" + quote("false")),
        )
    }

    @Test
    fun 清单必须声明播放_CPU唤醒锁与WiFi状态三项权限() {
        val text = manifest.readText()
        listOf(
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.WAKE_LOCK",
            "android.permission.ACCESS_WIFI_STATE",
        ).forEach { permission ->
            assertTrue(
                "清单缺 " + permission + "：没有它就既持不了唤醒锁也持不了 Wi-Fi 锁",
                text.contains(permission),
            )
        }
    }

    // ------------------------------------------------------------ ② 两把锁

    @Test
    fun 服务必须同时持唤醒锁与WiFi锁_并在销毁时都释放() {
        val keepAwake = sourceOf("media/playback", "io/github/gua123/mediagate/media/playback/PlaybackKeepAwake.kt")
        assertTrue("找不到 PlaybackKeepAwake.kt：" + keepAwake.absolutePath, keepAwake.exists())
        assertTrue("保活判定必须走 PlaybackKeepAwake（宽限期口径只能有一份）", keepAwake.readText().contains("object PlaybackKeepAwake"))

        val service = sourceOf("media/playback", "io/github/gua123/mediagate/media/playback/VideoPlaybackService.kt")
        assertTrue("找不到 VideoPlaybackService.kt：" + service.absolutePath, service.exists())
        val text = service.readText()
        assertTrue("缺 CPU 唤醒锁：锁屏后 CPU 睡了，解码与回环代理的读线程都会被冻住", text.contains("PARTIAL_WAKE_LOCK"))
        assertTrue("缺 Wi-Fi 锁：锁屏后 Wi-Fi 进省电会把远端读拖过超时", text.contains("createWifiLock"))
        assertTrue("锁的持有判定必须用 PlaybackKeepAwake.shouldHold", text.contains("PlaybackKeepAwake.shouldHold"))
        assertTrue("到期续期判定必须用 PlaybackKeepAwake.shouldRenew", text.contains("PlaybackKeepAwake.shouldRenew"))

        val onDestroy = Regex("override fun onDestroy\\(\\) \\{[\\s\\S]*?\\n    \\}").find(text)
        assertNotNull("找不到 onDestroy（签名变了？同步更新本护栏）", onDestroy)
        val body = onDestroy!!.value
        assertTrue("onDestroy 必须释放唤醒锁", body.contains("releaseWakeLock()"))
        assertTrue("onDestroy 必须释放 Wi-Fi 锁", body.contains("releaseWifiLock()"))
    }

    // ------------------------------------------------------------ ③ 服务必须被 start

    @Test
    fun 会话服务必须被startService拉起_不能只bind() {
        assertTrue("找不到 " + serviceSource.path, serviceSource.exists())
        val text = serviceSource.readText()
        assertTrue("起播时必须 startService：bound-only 的服务收不到 onTaskRemoved，也没有重启语义", text.contains("startService("))
        assertFalse(
            "不能用 startForegroundService：Media3 要等到真的进 READY+playWhenReady 才前台化，5 秒内没 startForeground 会抛异常（一起播就闪退）",
            text.contains("startForegroundService("),
        )
        assertTrue("会话收起后要把推迟的后端重建补上", text.contains("onSessionClosed()"))
    }

    // ------------------------------------------------------------ ④ 播放中不拆后端

    @Test
    fun 网络现场变化在播放中必须推迟_不许重建远端后端() {
        val container = sourceOf("app", "io/github/gua123/mediagate/app/AppContainer.kt")
        assertTrue("找不到 AppContainer.kt：" + container.absolutePath, container.exists())
        val text = container.readText()
        assertTrue(
            "网络现场那条 collect 必须带 deferWhilePlaying = true，否则锁屏时 Wi-Fi 一抖就把正在读的会话关掉",
            text.contains("deferWhilePlaying = true"),
        )
        assertTrue("applyCurrentConnection 里必须有「播放中先记账」的守卫", text.contains("deferredConnectionApply"))
        assertTrue("推迟的重建要有补做入口", text.contains("flushDeferredPlaybackReconnect"))
        assertTrue(
            "会话控制器要把「会话收起」回调出来，补做才有触发点",
            text.contains("onSessionClosed = { flushDeferredPlaybackReconnect() }"),
        )
    }

    // ------------------------------------------------------------ 工具

    private fun quote(value: String): String = "\"" + value + "\""

    /** 按模块相对路径读源码（测试工作目录是 :app 模块，兄弟模块要走上一级）。 */
    private fun sourceOf(module: String, relative: String): File {
        val direct = File("src/main/java/" + relative)
        if (module == "app" && direct.exists()) return direct
        return File("../" + module + "/src/main/java/" + relative)
    }

    /** 取清单里某个服务块（锚点是 android:name，不能是「服务名第一次出现」，见 SessionServiceDeclarationTest 的教训）。 */
    private fun serviceBlock(manifestText: String, simpleName: String): String? {
        val q = "\""
        val nameAt = Regex("android:name=" + q + "[^" + q + "]*" + Regex.escape(simpleName) + q)
            .find(manifestText)?.range?.first ?: return null
        val start = manifestText.lastIndexOf("<service", nameAt)
        if (start < 0) return null
        val selfClosing = manifestText.indexOf("/>", nameAt)
        val withBody = manifestText.indexOf("</service>", nameAt)
        val end = when {
            selfClosing >= 0 && (withBody < 0 || selfClosing < withBody) -> selfClosing + 2
            withBody >= 0 -> withBody + 9
            else -> return null
        }
        return manifestText.substring(start, end)
    }
}
