package io.github.gua123.mediagate.data.storage.webdav

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：`execute()` 手动跟随重定向时的**凭据边界**。
 *
 * 历史 bug：`current.newBuilder().url(next)` 会把 Authorization 头原样带过去，跨主机跳转
 * 等于把 Basic 账号密码送给第三方主机（https → http 还会走明文）。
 * 修复后：跨主机（host 忽略大小写比较）或 https → http 降级时 `removeHeader("Authorization")`，
 * 同主机（Apache 补尾斜杠那类 301）保留。这里三条用例分别钉住这三个分支。
 */
class WebDavRedirectAuthTest {

    private val expectedAuthorization: String = Credentials.basic("user", "pass")

    /**
     * 用例 A：server1 回 302/301 跳到另一台主机，server2 收到的请求里**没有** Authorization，
     * 且跟随后的响应正常（能按 Range 读到文件内容）。
     *
     * 两台假服务器都跑在回环上，但 host 字符串不同（127.0.0.1 → 127.0.0.2），
     * 正好命中后端的 host 判定分支。
     */
    @Test
    fun `跨主机重定向不把 Authorization 发给第三方主机`() = runTest {
        val origin = FakeWebDavServer()
        val otherHost = FakeWebDavServer(bindHost = "127.0.0.2")
        origin.addDir("dav")
        origin.addFile("dav/moved.bin", "origin-copy")
        otherHost.addDir("dav")
        otherHost.addFile("dav/target.bin", "from-other-host")
        origin.redirects["/dav/moved.bin"] = otherHost.baseUrl + "/dav/target.bin"
        origin.redirectStatus = 302 // 任务口径：server1 用 302 跳到 server2
        origin.start()
        otherHost.start()
        val backend = WebDavStorageBackend(
            WebDavConfig(baseUrl = origin.baseUrl + "/dav", username = "user", password = "pass"),
        )
        try {
            // 正常返回：重定向后的 206/200 能读完，不因为摘头而失败（server2 不校验认证）
            backend.openRead("moved.bin").use { stream ->
                assertEquals("from-other-host", text(readAll(stream)))
            }
        } finally {
            backend.close()
            origin.stop()
            otherHost.stop()
        }

        // 第一跳确实带了凭据（否则这条用例测不到「摘头」）
        val initial = origin.requestsFor("GET", "/dav/moved.bin").single()
        assertEquals(expectedAuthorization, initial.headers["authorization"])
        // 第二跳（第三方主机）不得出现 Authorization
        val redirected = otherHost.requestsFor("GET", "/dav/target.bin").single()
        assertNull("跨主机重定向后不应再带 Basic 凭据", redirected.headers["authorization"])
    }

    /**
     * 用例 B：同主机内补尾斜杠的 301（stat 集合 /dav/album → /dav/album/），
     * 重发后的 PROPFIND **仍然**带 Authorization。
     */
    @Test
    fun `同主机补尾斜杠的 301 仍然携带 Authorization`() = runTest {
        val server = FakeWebDavServer()
        server.addDir("dav/album")
        server.redirects["/dav/album"] = "/dav/album/"
        server.start()
        val backend = WebDavStorageBackend(
            WebDavConfig(baseUrl = server.baseUrl + "/dav", username = "user", password = "pass"),
        )
        try {
            val entry = backend.stat("album")
            assertTrue("跟随 301 后应识别为目录", entry.isDirectory)
        } finally {
            backend.close()
            server.stop()
        }

        val first = server.requestsFor("PROPFIND", "/dav/album").single()
        val followed = server.requestsFor("PROPFIND", "/dav/album/").single()
        assertEquals(expectedAuthorization, first.headers["authorization"])
        assertEquals("同主机重定向应保留 Basic 凭据", expectedAuthorization, followed.headers["authorization"])
    }

    /**
     * 用例 C：https → http 降级（同主机）也要摘头。
     *
     * 假服务器只有明文 HTTP，没法真的起 TLS；这里用 OkHttp 应用层拦截器把第一跳
     * （https URL）合成成 301 → http，第二跳照常打到假服务器。`execute()` 是私有实现，
     * 通过反射把「合成客户端」注入它的第二个参数（默认参数 [client]），只影响测试。
     */
    @Test
    fun `https 降级到 http 时摘掉 Authorization`() {
        val server = FakeWebDavServer()
        server.addDir("dav")
        server.addFile("dav/a.bin", "plaintext-body")
        server.start()
        val seen = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                seen += request
                if (request.url.scheme == "https") {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(301)
                        .message("Moved Permanently")
                        .header("Location", "http://127.0.0.1:${server.port}/dav/a.bin")
                        .body("".toResponseBody(null))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()
        val backend = WebDavStorageBackend(
            WebDavConfig(baseUrl = server.baseUrl + "/dav", username = "user", password = "pass"),
        )
        try {
            val httpsRequest = Request.Builder()
                .url("https://127.0.0.1:${server.port}/dav/a.bin")
                .header("Authorization", expectedAuthorization)
                .build()
            val execute = WebDavStorageBackend::class.java
                .getDeclaredMethod("execute", Request::class.java, OkHttpClient::class.java)
                .apply { isAccessible = true }
            val response = execute.invoke(backend, httpsRequest, client) as Response
            response.use {
                assertEquals(200, it.code)
                assertEquals("plaintext-body", it.body.string())
            }
        } finally {
            backend.close()
            server.stop()
        }

        assertEquals("应恰好两跳：https 合成 301 + http 真实请求", 2, seen.size)
        assertEquals(expectedAuthorization, seen[0].header("Authorization"))
        assertNull("https→http 降级后不应再带 Basic 凭据", seen[1].header("Authorization"))
    }
}
