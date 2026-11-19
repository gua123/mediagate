package io.github.gua123.mediagate.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [redactPath] / [redactUrl] / [redactProxyUrl] / [redactHost] 的 JVM 单测。
 *
 * 锁住 2026-11-18 定的口径：**保留可辨识性，去掉可定位性**。
 * 反向断言（不含共享名 / 账号 / 上层目录 / base64 载荷）与正向断言同样重要——
 *
 * 输入全部是示例值（10.1.2.3 / demo / share/videos/…）：真实环境值只从
 * `.toolchain/real-server.properties`（gitignore）读，绝不写进源码。
 * 正则是死的，日志泄漏往往是「以为改了其实没改」。
 */
class LogRedactTest {

    /** 典型远端路径（示例值：假共享名 + 带连字符的文件名）。 */
    private val remotePath = "share/videos/demo-sample.mp4"

    @Test
    fun `普通远端路径只留末段目录与打码后的文件名`() {
        val redacted = redactPath(remotePath)
        assertEquals("…/videos/demo-s….mp4", redacted)
        assertFalse("上层目录必须消失：" + redacted, redacted.contains("share"))
        assertFalse("完整文件名必须消失：" + redacted, redacted.contains("demo-sample"))
        assertFalse("文件名尾巴不能整段留下：" + redacted, redacted.contains("sample"))
    }

    @Test
    fun `只有文件名时原样保留（低危，排障要靠它）`() {
        assertEquals("song.mp3", redactPath("song.mp3"))
        assertEquals("#Demo Sample 2024.mp4", redactPath("#Demo Sample 2024.mp4"))
        assertEquals("movie.mkv", redactPath("  movie.mkv  "))
    }

    @Test
    fun `空串与纯空白返回空串`() {
        assertEquals("", redactPath(""))
        assertEquals("", redactPath("   "))
        assertEquals("", redactPath("\n\t"))
        assertEquals("", redactUrl(""))
        assertEquals("", redactProxyUrl(""))
        assertEquals("", redactHost(""))
    }

    @Test
    fun `超长路径被压到很短且不留上层目录`() {
        val longName = "x".repeat(300) + ".mkv"
        val longPath = (1..40).joinToString("/") { "dir" + it } + "/" + longName
        val redacted = redactPath(longPath)
        assertTrue("必须显示为折叠：" + redacted, redacted.startsWith("…/dir40/"))
        assertTrue("长度必须压住（实际 " + redacted.length + "）", redacted.length <= 60)
        assertFalse("不能留着完整文件名：" + redacted, redacted.contains("x".repeat(20)))
        assertFalse("不能留着上层目录：" + redacted, redacted.contains("dir1/"))
        assertTrue("扩展名要留着：" + redacted, redacted.endsWith(".mkv"))
    }

    @Test
    fun `目录路径（结尾斜杠）保留末段目录`() {
        assertEquals("…/videos/", redactPath("/storage/emulated/0/videos/"))
        assertEquals("…/videos/", redactPath("share/videos/"))
        assertEquals("/", redactPath("/"))
    }

    @Test
    fun `keepTail 可调，且名字本来就短时不打码`() {
        assertEquals("…/videos/dem….mp4", redactPath(remotePath, keepTail = 3))
        assertEquals("…/videos/….mp4", redactPath(remotePath, keepTail = 0))
        assertEquals("…/movies/a.mp4", redactPath("movies/a.mp4"))
        assertEquals("…/a/b.mp4", redactPath("a/b.mp4"))
    }

    @Test
    fun `反斜杠路径按同一口径处理`() {
        assertEquals("…/videos/demo-s….mp4", redactPath("share\\videos\\demo-sample.mp4"))
    }

    @Test
    fun `主机脱敏留得住网段与域名后缀`() {
        assertEquals("10.1.*.*", redactHost("10.1.2.3"))
        assertEquals("10.0.*.*", redactHost("10.0.0.9"))
        assertEquals("127.0.0.1", redactHost("127.0.0.1"))
        assertEquals("localhost", redactHost("localhost"))
        assertEquals("::1", redactHost("::1"))
        assertEquals("d***.com", redactHost("dav.example.com"))
        assertEquals("d***", redactHost("demo"))
        assertEquals("2001:…", redactHost("2001:db8::1"))
        assertEquals("10.1.*.*:2222", redactEndpoint("10.1.2.3", 2222))
        assertEquals("localhost", redactEndpoint("localhost", 0))
    }

    @Test
    fun `异常文案里的已知主机名也能打码`() {
        val message = "SFTP 连接被拒绝（10.1.2.3:2222）"
        assertEquals("SFTP 连接被拒绝（10.1.*.*:2222）", redactHostInText(message, "10.1.2.3"))
        assertEquals("DNS 解析失败（d***.com）", redactHostInText("DNS 解析失败（dav.example.com）", "dav.example.com"))
        // 空主机名时原样返回，绝不把文本改成空
        assertEquals(message, redactHostInText(message, ""))
        assertEquals("", redactHostInText("", "10.1.2.3"))
    }

    @Test
    fun `异常文案里的账号名也能打码`() {
        assertEquals("SFTP 认证失败：***@10.1.*.*:2222", redactUserInText("SFTP 认证失败：demo@10.1.*.*:2222"))
        assertEquals("没有 @ 的文本原样返回", redactUserInText("没有 @ 的文本原样返回"))
        assertEquals("", redactUserInText(""))
    }

    @Test
    fun `URL 脱敏去掉凭据与主机，端口与路径末段留着`() {
        val redacted = redactUrl("sftp://demo@10.1.2.3:2222/share/videos/demo-sample.mp4")
        assertTrue("凭据要标出来但不留值：" + redacted, redacted.contains("sftp://***@10.1.*.*:2222"))
        assertTrue("路径末段要留着：" + redacted, redacted.contains("videos"))
        assertFalse("账号不能留：" + redacted, redacted.contains("demo@"))
        assertFalse("共享名不能留：" + redacted, redacted.contains("share"))
        assertEquals("http://10.1.*.*:8080", redactUrl("http://10.1.2.3:8080"))
        assertEquals("http://***@10.1.*.*:8080/…/movies/a.mkv", redactUrl("http://demo:secret@10.1.2.3:8080/media/movies/a.mkv"))
        assertFalse("密码不能留", redactUrl("http://demo:secret@10.1.2.3:8080/media/movies/a.mkv").contains("secret"))
        assertEquals("…/not-a-url/x.mp4", redactUrl("not-a-url/x.mp4"))
        // 文件名里的 # 是名字的一部分，不是片段标记
        assertEquals("…/videos/#demo.mp4", redactPath("share/videos/#demo.mp4"))
    }

    @Test
    fun `回环代理地址只留 scheme 端口与载荷长度`() {
        val token = "t.c2Z0cDovL2RlbW9AMTAuMS4yLjM6MjIyMi9zaGFyZS92aWRlb3MvZGVtby1zYW1wbGUubXA0"
        val url = "http://127.0.0.1:42749/m/" + token
        val redacted = redactProxyUrl(url)
        assertEquals("http://127.0.0.1:42749/m/***（" + token.length + " 字符）", redacted)
        assertFalse("base64 载荷一个字都不能留：" + redacted, redacted.contains("ZGVtb0A"))
        assertFalse("载荷不能留：" + redacted, redacted.contains("demo"))
    }

    @Test
    fun `可读 legacy 形态的代理地址同样被打码`() {
        val url = "http://127.0.0.1:42749/m/sftp%3A%2F%2Fdemo%4010.1.2.3%3A2222%2Fshare%2Fvideos%2Fa.mp4"
        val redacted = redactProxyUrl(url)
        assertTrue("形态要认得出来：" + redacted, redacted.startsWith("http://127.0.0.1:42749/m/***（"))
        assertFalse("账号不能留：" + redacted, redacted.contains("demo%40"))
        assertFalse("主机不能留：" + redacted, redacted.contains("10.1.2.3"))
    }

    @Test
    fun `不是代理地址时委托 URL 口径`() {
        assertEquals("https://d***.com/…/dav/a.mp4", redactProxyUrl("https://dav.example.com/dav/a.mp4"))
        assertEquals("http://127.0.0.1:42749", redactProxyUrl("http://127.0.0.1:42749"))
    }

    // ---------------------------------------------------------------- [redactForLog]（第二批：崩溃报告 / 自由文本）

    @Test
    fun `主机密钥变更告警里的主机被打码，指纹留着`() {
        val message = "SFTP 主机密钥已变更（10.1.2.3:2222）：期望 SHA256:AAA，实际 SHA256:BBB。可能是中间人攻击"
        val redacted = redactForLog(message, "10.1.2.3")
        assertTrue("网段与端口要留着：" + redacted, redacted.contains("10.1.*.*:2222"))
        assertFalse("主机不能留：" + redacted, redacted.contains("10.1.2.3"))
        assertTrue("指纹是核对依据，必须留着：" + redacted, redacted.contains("SHA256:AAA") && redacted.contains("SHA256:BBB"))
    }

    @Test
    fun `异常文案里的完整远端路径被折叠，文件名留前六字符`() {
        val message = "目录不存在：/share/videos/demo-sample.mp4"
        val redacted = redactForLog(message)
        assertTrue("前因要留着：" + redacted, redacted.startsWith("目录不存在："))
        assertTrue("末段目录与打码文件名要留着：" + redacted, redacted.contains("…/videos/demo-s….mp4"))
        assertFalse("上层目录不能留：" + redacted, redacted.contains("share"))
        assertFalse("完整文件名不能留：" + redacted, redacted.contains("demo-sample"))
    }

    @Test
    fun `文本里的 URL 与账号一起打码`() {
        val text = "打开 sftp://demo:secret@10.1.2.3:2222/share/videos/a.mp4 失败；当前账号 demo@10.1.2.3"
        val redacted = redactForLog(text)
        assertFalse("账号不能留：" + redacted, redacted.contains("demo"))
        assertFalse("密码不能留：", redacted.contains("secret"))
        assertFalse("主机不能留：", redacted.contains("10.1.2.3"))
        assertTrue("端口要留着：" + redacted, redacted.contains(":2222"))
        assertTrue("凭据痕迹要标出来：" + redacted, redacted.contains("***@"))
    }

    @Test
    fun `堆栈里的文件名与行号不会被误伤`() {
        val trace = """
            java.lang.IllegalStateException: 目录不存在
            	at io.github.gua123.mediagate.app.AppContainer.open(AppContainer.kt:443)
            	at android.os.Looper.loop(Looper.java:223)
        """.trimIndent()
        assertEquals(trace, redactForLog(trace))
    }

    @Test
    fun `原生崩溃轨迹里的系统 so 路径保持原样（打码会毁掉崩在哪个库）`() {
        val trace = """
            #00 pc 0000000000051234  /system/lib64/libc.so (abort+160)
            #01 pc 0000000000123456  /data/app/~~x==/io.github.gua123.mediagate-1/lib/arm64/libvlc.so (vlc_play+40)
        """.trimIndent()
        assertEquals(trace, redactForLog(trace))
    }

    @Test
    fun `整体脱敏可重复施加（崩溃报告会把已脱敏的日志再抄一遍）`() {
        val text = "消息：SFTP 认证失败 demo@10.1.2.3:2222 → /share/videos/demo-sample.mp4"
        val once = redactForLog(text)
        assertEquals("再施加一次必须一字不变", once, redactForLog(once))
        assertEquals(once, redactForLog(once, "10.1.2.3"))
        assertEquals("", redactForLog(""))
        assertEquals("没有路径与主机的文案原样返回", "目录不存在", redactForLog("目录不存在"))
    }
}
