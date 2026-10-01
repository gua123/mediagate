package io.github.gua123.mediagate.data.storage.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersistentKnownHostsStore] 的 JVM 单测（**R2 / plan 4.9**：TOFU 要真的落盘）。
 *
 * 关键回归点：**冷启动后立刻校验**也必须能认出旧指纹——
 * 否则一个被换掉的密钥会被当作"未知主机"按 TOFU 放行，变更检测形同虚设。
 */
class PersistentKnownHostsStoreTest {

    private val lan = HostKeyFingerprint(host = "nas.local", port = 2222, keyType = "ssh-ed25519", sha256 = "AAA", md5 = "aa")
    private val changed = lan.copy(sha256 = "BBB", md5 = "bb")

    @Test
    fun `首次访问就把库里的指纹加载进内存`() {
        val persistence = FakePersistence(listOf(lan))
        val store = PersistentKnownHostsStore(persistence)

        assertEquals(1, store.size)
        assertEquals(listOf(lan), store.find("nas.local", 2222))
        assertTrue(store.find("other.local", 22).isEmpty())
    }

    @Test
    fun `TOFU 记下的指纹会写穿到持久层`() {
        val persistence = FakePersistence()
        val store = PersistentKnownHostsStore(persistence)

        store.save(lan)
        store.save(lan) // 幂等：重复保存不产生第二条

        assertEquals(1, store.size)
        assertEquals(listOf(lan), persistence.loadAll())
        assertEquals(1, persistence.insertCalls)
    }

    @Test
    fun `重启后仍认识旧指纹并拒绝变更`() {
        val persistence = FakePersistence()
        val first = PersistentKnownHostsStore(persistence)
        val verifier = TofuHostKeyVerifier(store = first, policy = SftpHostKeyPolicy.TOFU)
        assertTrue("首见应放行并记住", verifier.verify(lan).accepted)

        // 模拟 App 重启：同一份持久层，新的内存缓存
        val restarted = PersistentKnownHostsStore(persistence)
        val second = TofuHostKeyVerifier(store = restarted, policy = SftpHostKeyPolicy.TOFU)
        val check = second.verify(changed)

        assertFalse("换过钥的指纹绝不能被当作新主机放行", check.accepted)
        assertTrue(check is HostKeyCheck.Rejected)
        assertEquals(HostKeyRejectReason.CHANGED, (check as HostKeyCheck.Rejected).reason)
    }

    @Test
    fun `删除会同时清内存与持久层`() {
        val persistence = FakePersistence(listOf(lan))
        val store = PersistentKnownHostsStore(persistence)

        store.remove("nas.local", 2222)

        assertEquals(0, store.size)
        assertTrue(persistence.loadAll().isEmpty())
        assertEquals(listOf("nas.local:2222"), persistence.deleted)
    }

    /** 内存版持久层：模拟 Room。 */
    private class FakePersistence(
        initial: List<HostKeyFingerprint> = emptyList(),
    ) : HostKeyPersistence {

        private val rows = initial.toMutableList()
        var insertCalls = 0
        val deleted = mutableListOf<String>()

        override fun loadAll(): List<HostKeyFingerprint> = rows.toList()

        override fun insert(fingerprint: HostKeyFingerprint) {
            insertCalls++
            if (rows.none { it.storeKey == fingerprint.storeKey && it.sha256 == fingerprint.sha256 }) {
                rows.add(fingerprint)
            }
        }

        override fun delete(host: String, port: Int) {
            deleted += HostKeyFingerprint.key(host, port)
            rows.removeAll { it.host == host && it.port == port }
        }
    }
}
