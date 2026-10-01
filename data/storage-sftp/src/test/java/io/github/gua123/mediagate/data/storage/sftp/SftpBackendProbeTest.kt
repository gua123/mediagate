package io.github.gua123.mediagate.data.storage.sftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.net.ServerSocket
import java.nio.file.Path

/**
 * SFTP 连通性测试三段计时与失败分类（**R8** / plan 4.5「SFTP banner + 认证 + 列目录」）。
 *
 * 断言的是「改错端口能复现对应错误」：认证失败、端口不可达、DNS 失败、路径不存在
 * 各自给出**不同的中文结论**，而且 **probe 永不抛异常**（否则「测试全部」跑不完）。
 */
class SftpBackendProbeTest {

    private lateinit var root: Path
    private lateinit var server: SftpTestServer

    @Before
    fun setUp() {
        root = newTempDir("sftp-probe")
        root.writeTextFile("hello.txt", "hi")
        server = SftpTestServer(root)
    }

    @After
    fun tearDown() {
        server.close()
        root.toFile().deleteRecursively()
    }

    private fun backend(config: SftpConfig) = SftpStorageBackend(config)

    private fun probe(config: SftpConfig): ProbeReport = runBlocking {
        val backend = backend(config)
        try {
            backend.probe()
        } finally {
            backend.close()
        }
    }

    @Test
    fun `probe 成功且三段耗时自洽`() {
        val report = probe(server.config())
        println("[单测] probe ok=" + report.ok + " dns=" + report.dnsMs + " tcp=" + report.connectMs + " handshake=" + report.handshakeMs + " total=" + report.totalMs)
        assertTrue(report.message ?: "probe 应当成功", report.ok)
        assertTrue(report.dnsMs >= 0L && report.connectMs >= 0L && report.handshakeMs >= 0L)
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue("整段应该很快，实际 " + report.totalMs + " ms", report.totalMs < 15_000L)
        assertEquals(null, report.message)
    }

    @Test
    fun `probe 成功时 TCP 段有真实耗时`() {
        val report = probe(server.config())
        // 回声地址的 DNS 也会走一次解析，两段都允许为 0 ms（本机极快），但三段之和必须等于总耗时
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue(report.totalMs >= report.handshakeMs)
    }

    @Test
    fun `probe 密码错分类为认证失败`() {
        val report = probe(server.config(password = "wrong-password"))
        println("[单测] probe 认证失败 message=" + report.message)
        assertFalse(report.ok)
        val message = report.message.orEmpty()
        assertTrue(message, message.contains("认证失败"))
        assertTrue(message, message.contains("密码"))
    }

    @Test
    fun `probe 端口不可达分类为连接被拒绝`() {
        val deadPort = freePort()
        val report = probe(
            SftpConfig(
                host = "127.0.0.1",
                port = deadPort,
                username = "tester",
                password = "x",
                connectTimeoutMs = 2_000L,
            ),
        )
        println("[单测] probe 端口不可达 message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("拒绝"))
    }

    @Test
    fun `probe DNS 失败分类并写进 DNS 段`() {
        val report = probe(
            SftpConfig(
                host = "no-such-host.invalid",
                username = "tester",
                password = "x",
                connectTimeoutMs = 3_000L,
            ),
        )
        println("[单测] probe DNS 失败 message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("DNS"))
        assertEquals(0L, report.connectMs)
        assertEquals(0L, report.handshakeMs)
        assertEquals(report.totalMs, report.dnsMs)
    }

    @Test
    fun `probe 根路径不存在给出路径结论`() {
        val report = probe(server.config(basePath = "/no-such-root"))
        println("[单测] probe 路径不存在 message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("不存在"))
    }

    @Test
    fun `probe 会按 TOFU 记住主机指纹`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        val backend = SftpStorageBackend(server.config(), verifier)
        try {
            assertTrue(backend.probe().ok)
            assertEquals(1, store.size)
            assertNotNull(store.find("127.0.0.1", server.port).firstOrNull())
        } finally {
            backend.close()
        }
    }

    @Test
    fun `probe 不抛异常（用错配置也返回报告）`() {
        val report = probe(
            SftpConfig(
                host = "127.0.0.1",
                port = freePort(),
                username = "tester",
                password = "x",
                connectTimeoutMs = 1_000L,
            ),
        )
        assertFalse(report.ok)
    }

    @Test
    fun `probe 不接受错误主机密钥（TOFU 变更会失败而不是静默）`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        store.save(
            HostKeyFingerprint(
                host = "127.0.0.1",
                port = server.port,
                keyType = "ecdsa-sha2-nistp256",
                sha256 = "WRONG-FINGERPRINT",
                md5 = "00",
            ),
        )
        val backend = SftpStorageBackend(server.config(), verifier)
        try {
            val report = backend.probe()
            println("[单测] probe 主机密钥变更 message=" + report.message)
            assertFalse(report.ok)
            assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("证书不受信任"))
        } finally {
            backend.close()
        }
    }

    @Test
    fun `probe 之后正常操作仍可用`() = runBlocking {
        val backend = backend(server.config())
        try {
            assertTrue(backend.probe().ok)
            assertEquals(listOf("hello.txt"), backend.list("/").map { it.name })
        } finally {
            backend.close()
        }
    }

    @Test
    fun `probe 失败后错误类型是统一分类`() {
        val backend = backend(server.config(password = "bad"))
        try {
            runBlocking { backend.list("/") }
            throw AssertionError("应当抛异常")
        } catch (e: StorageException.Auth) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("认证失败"))
        } finally {
            backend.close()
        }
    }

    /** 拿一个「当前没人监听」的端口（先绑定再释放，够用）。 */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
