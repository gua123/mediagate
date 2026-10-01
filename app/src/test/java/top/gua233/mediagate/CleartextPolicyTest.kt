package io.github.gua123.mediagate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真机明文策略的回归护栏（**R2** 协议接入 / **R6** 凭据 / **R8** 连通性）。
 *
 * Android 9（API 28）起，targetSdk ≥ 28 的应用**默认禁止明文 http**：清单里没有
 * `android:networkSecurityConfig`（或 usesCleartextTraffic）时，OkHttp / HttpURLConnection
 * 在真机上直接抛 "CLEARTEXT communication to ... not permitted by network security policy"。
 * 局域网那台 WebDAV 正是 `http://192.168.1.10:8080`——这条被改回去，真机上 WebDAV 全挂，
 * 而 JVM 单测跑在 JVM 上、不受系统网络策略约束，一个都抓不到（真机才暴露，与 SFTP 接线同类的坑）。
 *
 * 所以这里直接读源文件断言（纯 JVM，不依赖 Android）。
 */
class CleartextPolicyTest {

    /** 单测工作目录通常是模块目录（app/）；兼容从仓库根跑的情况。 */
    private fun source(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件：$relative（当前工作目录=" + File(".").absolutePath + "）")
    }

    @Test
    fun `清单声明了网络安全配置`() {
        val manifest = source("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest 必须挂 networkSecurityConfig，否则 targetSdk 36 下明文 http 被系统拦死",
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
    }

    @Test
    fun `网络安全配置放开明文流量并保留系统根证书`() {
        val xml = source("src/main/res/xml/network_security_config.xml").readText()
        assertTrue("局域网 WebDAV 走明文 http，必须放开", xml.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue("https 仍要信任系统根证书", xml.contains("<certificates src=\"system\""))
    }
}
