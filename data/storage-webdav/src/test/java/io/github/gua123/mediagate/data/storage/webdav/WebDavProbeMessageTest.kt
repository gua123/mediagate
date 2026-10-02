package io.github.gua123.mediagate.data.storage.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针失败提示（**2026-10-03 真机**：服务端在开发机上验证是好的，必须让 App 把"收到了什么"说出来）。
 */
class WebDavProbeMessageTest {

    @Test
    fun `带上状态行 请求地址与响应片段`() {
        val message = probeFailureMessage(
            code = 403,
            httpMessage = "Forbidden",
            url = "http://dav.example.com:8080/",
            bodySnippet = "<html><body>You do not have permission</body></html>",
        )
        assertTrue(message, message.contains("HTTP 403 Forbidden"))
        assertTrue(message, message.contains("dav.example.com:8080"))
        assertTrue(message, message.contains("You do not have permission"))
    }

    @Test
    fun `URL 里的用户信息要被抹掉`() {
        val redacted = redactUrl("http://demo:secret@dav.example.com:8080/")
        assertEquals("http://dav.example.com:8080/", redacted)
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun `响应片段压成单行并限长`() {
        val snippet = sanitizeSnippet("line1\n\nline2\t\tline3 " + "x".repeat(300))
        assertFalse(snippet.contains("\n"))
        assertTrue(snippet.length <= MAX_SNIPPET + 1) // 允许结尾的省略号
    }

    @Test
    fun `没有响应体时只给状态与地址`() {
        val message = probeFailureMessage(404, "Not Found", "http://h:1/", null)
        assertEquals("HTTP 404 Not Found（请求：http://h:1/）", message)
    }
}
