package io.github.gua123.mediagate.data.storage.webdav

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WebDavStorageBackend.probe] 的单测（R8 连通性测试，plan 4.5 三段计时）。
 *
 * 重点：probe **绝不抛异常**，失败分类写进 message；成功时 DNS / TCP / 握手三段耗时都要有值。
 */
class WebDavBackendProbeTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var backend: WebDavStorageBackend

    @Before
    fun setUp() {
        server = FakeWebDavServer()
        server.addDir("dav")
        server.start()
        backend = WebDavStorageBackend(WebDavConfig(baseUrl = server.baseUrl + "/dav"))
    }

    @After
    fun tearDown() {
        backend.close()
        server.stop()
    }

    @Test
    fun `probe 207 成功且三段耗时被填充`() = runTest {
        server.responseDelayMs = 40L
        val report = backend.probe()
        assertTrue(report.ok)
        assertNull(report.message)
        assertTrue("dnsMs 应当被填充：${report.dnsMs}", report.dnsMs >= 0L)
        assertTrue("connectMs 应当被填充：${report.connectMs}", report.connectMs >= 0L)
        // 服务器延迟 40 ms 才回响应，握手段必须把它计进去（说明真的在计时）
        assertTrue("handshakeMs 应当 >= 30：${report.handshakeMs}", report.handshakeMs >= 30L)
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue(report.totalMs >= 30L)
    }

    @Test
    fun `probe 发的是 PROPFIND Depth 0`() = runTest {
        backend.probe()
        val request = server.requestsFor("PROPFIND", "/dav/").single()
        assertEquals("0", request.depth)
        assertEquals("identity", request.headers["accept-encoding"])
        assertTrue(request.body.contains("<D:propfind"))
    }

    @Test
    fun `probe 200 也算通`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 200
        val report = backend.probe()
        assertTrue(report.ok)
        assertNull(report.message)
    }

    @Test
    fun `probe 401 提示账号或密码错`() = runTest {
        server.requireAuthorization = "Basic bm9ib2R5Om5vcGFzcw=="
        val report = backend.probe()
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("401"))
        assertTrue(report.message!!.contains("账号或密码"))
        // 网络是通的，握手耗时依然要有
        assertTrue(report.totalMs >= 0L)
    }

    @Test
    fun `probe 403 提示无访问权限`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 403
        val report = backend.probe()
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("403"))
        assertTrue(report.message!!.contains("权限"))
    }

    @Test
    fun `probe 404 提示路径不对`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 404
        val report = backend.probe()
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("404"))
        assertTrue(report.message!!.contains("路径"))
    }

    @Test
    fun `probe 405 提示不支持 PROPFIND`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 405
        val report = backend.probe()
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("PROPFIND"))
    }

    @Test
    fun `probe 500 也只是一份失败报告`() = runTest {
        server.forcedStatus["PROPFIND /dav/"] = 500
        val report = backend.probe()
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("500"))
    }

    @Test
    fun `probe 连接失败不抛异常并归网络类`() = runTest {
        val port = server.port
        server.stop() // 端口关掉 → connect 被拒
        val offline = WebDavStorageBackend(
            WebDavConfig(baseUrl = "http://127.0.0.1:$port/dav", connectTimeoutMs = 2_000L, readTimeoutMs = 2_000L),
        )
        try {
            val report = offline.probe()
            assertFalse(report.ok)
            assertNotNull(report.message)
            assertTrue(report.message!!.contains("网络") || report.message!!.contains("连接"))
            assertTrue(report.totalMs >= 0L)
        } finally {
            offline.close()
        }
    }

    @Test
    fun `probe 带 Basic 认证可以通`() = runTest {
        server.requireAuthorization = "Basic dXNlcjpwYXNz"
        val authed = WebDavStorageBackend(
            WebDavConfig(baseUrl = server.baseUrl + "/dav", username = "user", password = "pass"),
        )
        try {
            assertTrue(authed.probe().ok)
            assertEquals("Basic dXNlcjpwYXNz", server.requestsFor("PROPFIND", "/dav/").single().headers["authorization"])
        } finally {
            authed.close()
        }
    }

    @Test
    fun `probe 连续两次都成功（连接复用也没问题）`() = runTest {
        assertTrue(backend.probe().ok)
        assertTrue(backend.probe().ok) // 连接复用后三段计时退化也不能崩
        assertEquals(2, server.requestsFor("PROPFIND", "/dav/").size)
    }
}
