package io.github.gua123.mediagate.data.storage.sftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.nio.file.Path

/**
 * 主机密钥 TOFU 的**端到端**验证（**R2** / plan 4.9）。
 *
 * 用**同一端口、两把不同主机密钥**的两台嵌入式服务器模拟「服务器换钥 / 中间人」：
 * 第一次连接把指纹记进 [KnownHostsStore]，换钥后同一个 store 必须**拒绝**连接并告警，
 * 而且绝不能把旧指纹覆盖掉（否则下次就发现不了了）。
 */
class SftpHostKeyIntegrationTest {

    private lateinit var root: Path
    private var first: SftpTestServer? = null
    private var swapped: SftpTestServer? = null
    private val backends = mutableListOf<SftpStorageBackend>()

    @Before
    fun setUp() {
        root = newTempDir("sftp-tofu")
        root.writeTextFile("hello.txt", "hi")
        first = SftpTestServer(root)
    }

    @After
    fun tearDown() {
        backends.forEach { runCatching { it.close() } }
        first?.close()
        swapped?.close()
        root.toFile().deleteRecursively()
    }

    private fun config(server: SftpTestServer, policy: SftpHostKeyPolicy = SftpHostKeyPolicy.TOFU) =
        server.config(policy = policy)

    private fun backend(config: SftpConfig, verifier: HostKeyVerifier): SftpStorageBackend =
        SftpStorageBackend(config, verifier).also { backends.add(it) }

    /** 换一台服务器但**端口不变**（模拟服务器换钥或中间人）。 */
    private fun swapServerOnSamePort(): SftpTestServer {
        val port = first!!.port
        first!!.close()
        first = null
        val replacement = SftpTestServer(root, requestedPort = port)
        swapped = replacement
        return replacement
    }

    @Test
    fun `首次连接 TOFU 记住指纹并放行`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        val backend = backend(config(first!!), verifier)
        assertEquals(listOf("hello.txt"), backend.list("/").map { it.name })
        assertEquals(1, store.size)
        val fingerprint = store.find("127.0.0.1", first!!.port).single()
        assertEquals("ecdsa-sha2-nistp256", fingerprint.keyType)
        assertTrue(fingerprint.sha256.isNotEmpty())
        println("[单测] TOFU 记住指纹 " + fingerprint.keyType + " " + fingerprint.sha256Display)
    }

    @Test
    fun `同一服务器第二次连接指纹一致且不告警`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        var changes = 0
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU) { changes++ }
        val server = first!!
        backend(config(server), verifier).list("/")
        backend(config(server), verifier).list("/")
        assertEquals(0, changes)
        assertEquals(1, store.size)
    }

    @Test
    fun `主机密钥变更后连接被拒且旧指纹保留`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        var event: HostKeyChangeEvent? = null
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU) { event = it }
        val original = first!!
        backend(config(original), verifier).list("/")
        val originalFingerprint = store.find("127.0.0.1", original.port).single().sha256

        val replacement = swapServerOnSamePort()
        val failure = assertThrows(StorageException.Auth::class.java) {
            runBlocking { backend(config(replacement), verifier).list("/") }
        }
        println("[单测] 主机密钥变更 → " + failure.message)
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("证书不受信任"))
        // 绝不静默：事件必须发出，且新旧指纹都拿得到
        val change = event
        assertNotNull("变更必须触发告警事件", change)
        assertEquals(originalFingerprint, change!!.expectedSha256)
        assertFalse(change.actualSha256 == originalFingerprint)
        // 旧指纹必须还在，且没有被新指纹悄悄覆盖
        assertEquals(originalFingerprint, store.find("127.0.0.1", original.port).single().sha256)
    }

    @Test
    fun `主机密钥变更时 probe 也不放行且不抛异常`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        val original = first!!
        backend(config(original), verifier).list("/")
        val replacement = swapServerOnSamePort()
        val report = backend(config(replacement), verifier).probe()
        println("[单测] 变更后 probe ok=" + report.ok + " message=" + report.message)
        assertFalse(report.ok)
        assertTrue(report.message.orEmpty(), report.message.orEmpty().contains("证书不受信任"))
    }

    @Test
    fun `换钥后清掉记录可以重新 TOFU`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        val original = first!!
        backend(config(original), verifier).list("/")
        val replacement = swapServerOnSamePort()
        assertThrows(StorageException.Auth::class.java) {
            runBlocking { backend(config(replacement), verifier).list("/") }
        }
        // 用户核对后手工信任新密钥（上层提供「重新 TOFU」入口）
        store.remove("127.0.0.1", replacement.port)
        val backend = backend(config(replacement), verifier)
        assertEquals(listOf("hello.txt"), backend.list("/").map { it.name })
        assertEquals(1, store.size)
    }

    @Test
    fun `STRICT 策略下未知主机直接拒绝`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.STRICT)
        val server = first!!
        val failure = assertThrows(StorageException.Auth::class.java) {
            runBlocking { backend(config(server, SftpHostKeyPolicy.STRICT), verifier).list("/") }
        }
        println("[单测] STRICT 未知主机 → " + failure.message)
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("证书不受信任"))
        assertEquals(0, store.size)
    }

    @Test
    fun `STRICT 策略下预置指纹后可连接`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val server = first!!
        // 先用 TOFU 观察一次指纹（模拟用户从别处抄来的 known_hosts），再换成 STRICT
        backend(config(server), TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)).list("/")
        val observed = store.find("127.0.0.1", server.port).single()
        assertTrue(observed.sha256.isNotEmpty())
        val strict = TofuHostKeyVerifier(store, SftpHostKeyPolicy.STRICT)
        assertEquals(listOf("hello.txt"), backend(config(server), strict).list("/").map { it.name })
    }

    @Test
    fun `ACCEPT_ANY 策略下变更会告警但仍然放行`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        var event: HostKeyChangeEvent? = null
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.ACCEPT_ANY) { event = it }
        val original = first!!
        backend(config(original), verifier).list("/")
        val replacement = swapServerOnSamePort()
        assertEquals(listOf("hello.txt"), backend(config(replacement), verifier).list("/").map { it.name })
        assertNotNull("ACCEPT_ANY 也必须告警（不静默）", event)
        assertEquals(SftpHostKeyPolicy.ACCEPT_ANY, event!!.policy)
    }

    @Test
    fun `指纹与服务器实际主机密钥一致`() = runBlocking {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        val server = first!!
        backend(config(server), verifier).list("/")
        val stored = store.find("127.0.0.1", server.port).single()
        // 指纹必须由服务器那把密钥算出来：再连一次（同一把钥）不能产生变更事件
        var changes = 0
        val again = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU) { changes++ }
        backend(config(server), again).list("/")
        assertEquals(0, changes)
        assertEquals(server.hostKeyPair.public.algorithm, "EC")
        assertTrue(stored.md5.contains(":"))
    }
}
