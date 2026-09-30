package io.github.gua123.mediagate.data.storage.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [WebDavConfig] 的校验与派生值 + 后端 id 的稳定性（R2 缓存 key / R7 多地址 / R8 测试）。 */
class WebDavConfigTest {

    @Test
    fun `baseUrl 必须是 http 或 https`() {
        for (bad in listOf("ftp://h/dav", "dav.example.com", "http://", "  ")) {
            try {
                WebDavConfig(baseUrl = bad)
                fail("应当拒绝：$bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `不允许明文 http 时构造失败`() {
        try {
            WebDavConfig(baseUrl = "http://192.168.1.10:8080", allowInsecureHttp = false)
            fail("应当拒绝明文 http")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("明文"))
        }
        // https 不受开关影响
        assertEquals("https://dav.example.com", WebDavConfig(baseUrl = "https://dav.example.com", allowInsecureHttp = false).requestBaseUrl)
        // 局域网自用默认允许明文
        assertEquals("http://192.168.1.10:8080", WebDavConfig(baseUrl = "http://192.168.1.10:8080").requestBaseUrl)
    }

    @Test
    fun `rootPath 不允许越界`() {
        try {
            WebDavConfig(baseUrl = "https://h", rootPath = "../etc")
            fail("应当拒绝越界 rootPath")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("rootPath"))
        }
    }

    @Test
    fun `超时必须为正`() {
        try {
            WebDavConfig(baseUrl = "https://h", connectTimeoutMs = 0)
            fail("应当拒绝 0 超时")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `派生值 requestBaseUrl basePath host`() {
        val config = WebDavConfig(baseUrl = "https://dav.example.com/", rootPath = "/媒体 库/")
        assertEquals("https://dav.example.com/媒体 库", config.requestBaseUrl)
        assertEquals("/媒体 库", config.basePath)
        assertEquals("dav.example.com", config.host)
        // 根路径就是 baseUrl 本身
        assertEquals("/dav", WebDavConfig(baseUrl = "https://h/dav").basePath)
    }

    @Test
    fun `后端 id 稳定且同一物理位置一致`() {
        val a = WebDavStorageBackend(WebDavConfig(baseUrl = "https://dav.example.com/dav/"))
        val b = WebDavStorageBackend(WebDavConfig(baseUrl = "https://dav.example.com/dav"))
        val c = WebDavStorageBackend(WebDavConfig(baseUrl = "https://dav.example.com", rootPath = "/dav"))
        val other = WebDavStorageBackend(WebDavConfig(baseUrl = "https://dav.example.com/dav2"))
        try {
            assertEquals("webdav:https://dav.example.com/dav", a.id)
            assertEquals(a.id, b.id)
            assertEquals(a.id, c.id)
            assertNotEquals(a.id, other.id)
        } finally {
            a.close()
            b.close()
            c.close()
            other.close()
        }
    }

    @Test
    fun `是否附加 Basic 认证`() {
        assertTrue(WebDavConfig(baseUrl = "https://h", username = "u", password = "p").useBasicAuth)
        assertFalse(WebDavConfig(baseUrl = "https://h").useBasicAuth)
        assertFalse(WebDavConfig(baseUrl = "https://h", username = "").useBasicAuth)
        // 调用方自带 Authorization（如 Bearer）时不再附加 Basic
        val custom = WebDavConfig(
            baseUrl = "https://h",
            username = "u",
            password = "p",
            extraHeaders = mapOf("authorization" to "Bearer token"),
        )
        assertFalse(custom.useBasicAuth)
    }
}
