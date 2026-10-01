package io.github.gua123.mediagate.data.storage.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 主机密钥 TOFU 与变更检测（**R2** / plan 4.9「SFTP 主机密钥 TOFU + 变更告警」）。
 *
 * 纯 JVM 单测：直接喂指纹给校验器与存储，不建连接。
 */
class SftpHostKeyTofuTest {

    private fun fingerprint(sha: String = "AAAA", type: String = "ssh-ed25519", port: Int = 22) =
        HostKeyFingerprint(host = "nas.local", port = port, keyType = type, sha256 = sha, md5 = "aa:bb")

    @Test
    fun `TOFU 首次记住并放行`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        val check = verifier.verify(fingerprint())
        assertTrue(check is HostKeyCheck.Trusted)
        assertTrue((check as HostKeyCheck.Trusted).firstSeen)
        assertEquals(1, store.size)
        assertEquals("AAAA", store.find("nas.local", 22).single().sha256)
    }

    @Test
    fun `TOFU 第二次相同指纹放行且不再是首次`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        verifier.verify(fingerprint())
        val again = verifier.verify(fingerprint())
        assertTrue(again is HostKeyCheck.Trusted)
        assertFalse((again as HostKeyCheck.Trusted).firstSeen)
        assertEquals(1, store.size)
    }

    @Test
    fun `指纹变更被拒且不覆盖旧记录`() {
        val store = InMemoryKnownHostsStore()
        var event: HostKeyChangeEvent? = null
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU) { event = it }
        verifier.verify(fingerprint(sha = "OLD"))
        val check = verifier.verify(fingerprint(sha = "NEW"))
        assertTrue(check is HostKeyCheck.Rejected)
        val rejected = check as HostKeyCheck.Rejected
        assertEquals(HostKeyRejectReason.CHANGED, rejected.reason)
        assertEquals("OLD", rejected.expected?.sha256)
        assertEquals("NEW", rejected.fingerprint.sha256)
        assertTrue("文案必须能被人看懂：" + rejected.message, rejected.message.contains("证书不受信任"))
        // 事件绝不静默：变更一定要有人知道（plan 4.9）
        val change = event
        assertNotNull(change)
        assertEquals("OLD", change!!.expectedSha256)
        assertEquals("NEW", change.actualSha256)
        // 旧指纹必须还在：否则下次就检测不出变更了
        assertEquals("OLD", store.find("nas.local", 22).single().sha256)
    }

    @Test
    fun `STRICT 拒绝未知主机`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.STRICT)
        val check = verifier.verify(fingerprint())
        assertTrue(check is HostKeyCheck.Rejected)
        assertEquals(HostKeyRejectReason.UNKNOWN_HOST, (check as HostKeyCheck.Rejected).reason)
        assertNull(check.expected)
        assertEquals("未知主机不允许被记住", 0, store.size)
    }

    @Test
    fun `STRICT 放行已记录主机`() {
        val store = InMemoryKnownHostsStore()
        store.save(fingerprint(sha = "KNOWN"))
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.STRICT)
        assertTrue(verifier.verify(fingerprint(sha = "KNOWN")) is HostKeyCheck.Trusted)
        assertTrue(verifier.verify(fingerprint(sha = "OTHER")) is HostKeyCheck.Rejected)
    }

    @Test
    fun `ACCEPT_ANY 接受变更但仍然告警不静默`() {
        val store = InMemoryKnownHostsStore()
        var event: HostKeyChangeEvent? = null
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.ACCEPT_ANY) { event = it }
        verifier.verify(fingerprint(sha = "OLD"))
        val check = verifier.verify(fingerprint(sha = "NEW"))
        assertTrue(check is HostKeyCheck.Trusted)
        assertNotNull("即便 ACCEPT_ANY 也必须产生变更事件", event)
        assertEquals(SftpHostKeyPolicy.ACCEPT_ANY, event!!.policy)
    }

    @Test
    fun `不同端口分开记录`() {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, SftpHostKeyPolicy.TOFU)
        verifier.verify(fingerprint(sha = "P22", port = 22))
        verifier.verify(fingerprint(sha = "P2222", port = 2222))
        assertEquals(2, store.size)
    }

    @Test
    fun `存储支持查询与清除`() {
        val store = InMemoryKnownHostsStore()
        store.save(fingerprint())
        assertEquals(1, store.find("nas.local", 22).size)
        assertTrue(store.find("nas.local", 23).isEmpty())
        store.remove("nas.local", 22)
        assertEquals(0, store.size)
    }

    @Test
    fun `指纹算法与 OpenSSH 口径一致`() {
        val blob = utf8("fake-key-material")
        val fingerprint = HostKeyFingerprint.of("h", 22, "ssh-ed25519", blob)
        val expectedSha = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertEquals(expectedSha, fingerprint.sha256)
        assertEquals("SHA256:" + expectedSha, fingerprint.sha256Display)
        assertTrue(fingerprint.md5.contains(":"))
        assertEquals("h:22", fingerprint.storeKey)
    }

    @Test
    fun `从密钥 blob 解析算法名`() {
        // SSH 线格式：4 字节大端长度 + 算法名
        val name = "ssh-ed25519"
        val bytes = ByteArray(4 + name.length)
        bytes[3] = name.length.toByte()
        name.toByteArray().copyInto(bytes, 4)
        assertEquals(name, HostKeyFingerprint.keyTypeOf(bytes))
        assertEquals("unknown", HostKeyFingerprint.keyTypeOf(ByteArray(2)))
    }
}
