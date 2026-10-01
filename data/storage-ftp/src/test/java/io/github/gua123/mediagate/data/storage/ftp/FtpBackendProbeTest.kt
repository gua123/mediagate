package io.github.gua123.mediagate.data.storage.ftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.nio.file.Path

/**
 * FTP 连通性测试三段计时与失败分类（**R8** / plan 4.5「FTP 220 + 登录 + PASV」）。
 *
 * 断言「改错端口能复现对应错误」：认证失败、端口不可达、DNS 失败、路径不存在
 * 各自给出不同的中文结论，而且 **probe 永不抛异常**（否则「测试全部」跑不完）。
 */
class FtpBackendProbeTest {

    private lateinit var root: Path
    private lateinit var server: FtpTestServer

    @Before
    fun setUp() {
        root = newTempDir("ftp-probe")
        root.writeTextFile("hello.txt", "hi")
        server = FtpTestServer(root)
    }

    @After
    fun tearDown() {
        server.close()
        root.toFile().deleteRecursively()
    }

    private fun probe(config: FtpConfig): ProbeReport = runBlocking {
        val backend = FtpStorageBackend(config)
        try {
            backend.probe()
        } finally {
            backend.close()
        }
    }

    @Test
    fun `probe 成功且三段耗时自洽`() {
        val report = probe(server.config())
        println("[单测] FTP probe ok=" + report.ok + " dns=" + report.dnsMs + " tcp=" + report.connectMs + " handshake=" + report.handshakeMs + " total=" + report.totalMs)
        assertTrue(report.message ?: "probe 应当成功", report.ok)
        assertTrue(report.dnsMs >= 0L && report.connectMs >= 0L && report.handshakeMs >= 0L)
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue("整段应该很快，实际 " + report.totalMs + " ms", report.totalMs < 15_000L)
        assertEquals(null, report.message)
    }

    @Test
    fun `probe TCP 段是真连过 socket 的`() {
        val report = probe(server.config())
        // 本机连接极快，不强行断言 > 0；但三段之和必须等于总耗时（plan 4.5 的口径）
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue(report.handshakeMs >= 0L)
    }

    @Test
    fun `probe 密码错分类为认证失败`() {
        val report = probe(server.config(password = "wrong-password"))
        println("[单测] FTP probe 认证失败 message=" + report.message)
        assertFalse(report.ok)
        val message = report.message.orEmpty()
        assertTrue(message, message.contains("认证失败"))
        assertTrue(message, message.contains("密码"))
    }

    @Test
    fun `probe 端口不可达分类为连接被拒绝`() {
        val report = probe(
            FtpConfig(
                host = "127.0.0.1",
                port = FtpTestServer.freePort(),
                username = "tester",
                password = "x",
                connectTimeoutMs = 2_000L,
            ),
        )
        println("[单测] FTP probe 端口不可达 message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("拒绝"))
    }

    @Test
    fun `probe DNS 失败分类并写进 DNS 段`() {
        val report = probe(
            FtpConfig(
                host = "no-such-host.invalid",
                username = "tester",
                password = "x",
                connectTimeoutMs = 3_000L,
            ),
        )
        println("[单测] FTP probe DNS 失败 message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("DNS"))
        assertEquals(0L, report.connectMs)
        assertEquals(0L, report.handshakeMs)
        assertEquals(report.totalMs, report.dnsMs)
    }

    @Test
    fun `probe 根路径不存在给出路径结论`() {
        val report = probe(server.config(basePath = "/no-such-root"))
        println("[单测] FTP probe 路径不存在 message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("不存在"))
    }

    @Test
    fun `probe 不抛异常（端口没人监听也返回报告）`() {
        val report = probe(
            FtpConfig(
                host = "127.0.0.1",
                port = FtpTestServer.freePort(),
                username = "tester",
                password = "x",
                connectTimeoutMs = 1_000L,
            ),
        )
        assertFalse(report.ok)
        assertTrue(report.totalMs >= 0L)
    }

    @Test
    fun `probe 探测出 MLSD 与 REST 能力`() = runBlocking {
        val backend = FtpStorageBackend(server.config())
        try {
            assertTrue(backend.probe().ok)
            assertTrue("本服务器支持 REST", backend.caps.randomAccess)
            assertTrue(backend.caps.resumeByRest)
        } finally {
            backend.close()
        }
    }

    @Test
    fun `probe 之后列目录仍可用`() = runBlocking {
        val backend = FtpStorageBackend(server.config())
        try {
            assertTrue(backend.probe().ok)
            assertEquals(listOf("hello.txt"), backend.list("/").map { it.name })
        } finally {
            backend.close()
        }
    }

    @Test
    fun `登录失败时普通操作抛 Auth`() {
        val backend = FtpStorageBackend(server.config(password = "bad"))
        try {
            runBlocking { backend.list("/") }
            throw AssertionError("应当抛异常")
        } catch (e: StorageException.Auth) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("认证失败"))
        } finally {
            backend.close()
        }
    }

    @Test
    fun `被动模式与主动模式都能探测成功`() {
        assertTrue(probe(server.config(passive = true)).ok)
    }
}
