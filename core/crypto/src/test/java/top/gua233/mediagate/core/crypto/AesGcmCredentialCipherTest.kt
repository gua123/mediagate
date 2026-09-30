package io.github.gua123.mediagate.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [AesGcmCredentialCipher] 的 JVM 单测（**R6**：协议凭据只以密文落库）。
 *
 * 这里用**本地随机 AES 密钥**替真机的 AndroidKeyStore 密钥——加解密代码完全同源，
 * 所以「每次密文不同 / 篡改必被拒 / 换密钥解不开」这些性质在真机上同样成立。
 */
class AesGcmCredentialCipherTest {

    private fun randomKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun cipher(key: SecretKey = randomKey()): AesGcmCredentialCipher =
        AesGcmCredentialCipher { key }

    @Test
    fun `加密后能解回原文（ASCII）`() {
        val c = cipher()
        val plain = "hunter2"
        assertEquals(plain, c.decrypt(c.encrypt(plain)))
    }

    @Test
    fun `加密后能解回原文（中文与表情）`() {
        val c = cipher()
        val plain = "密码：中文测试 🔐 line2"
        assertEquals(plain, c.decrypt(c.encrypt(plain)))
    }

    @Test
    fun `空字符串也能往返（部分服务器接受空密码）`() {
        val c = cipher()
        val sealed = c.encrypt("")
        assertTrue(CredentialEnvelope.isEnvelope(sealed))
        assertEquals("", c.decrypt(sealed))
    }

    @Test
    fun `长密码（4KB）往返一致`() {
        val c = cipher()
        val plain = "p".repeat(4096)
        assertEquals(plain, c.decrypt(c.encrypt(plain)))
    }

    @Test
    fun `同一明文两次加密的密文不同（随机 IV）`() {
        val c = cipher()
        val a = c.encrypt("same-secret")
        val b = c.encrypt("same-secret")
        assertNotEquals(a, b)
        // 但都能解回同一明文
        assertEquals("same-secret", c.decrypt(a))
        assertEquals("same-secret", c.decrypt(b))
    }

    @Test
    fun `密文里看不出明文`() {
        val c = cipher()
        val sealed = c.encrypt("hunter2")
        assertTrue(!sealed.contains("hunter2"))
        assertEquals("MGC1", sealed.substringBefore('.'))
    }

    @Test
    fun `换一把密钥解不开（DecryptFailed）`() {
        val sealed = cipher(randomKey()).encrypt("hunter2")
        val error = try {
            cipher(randomKey()).decrypt(sealed)
            null
        } catch (e: CredentialException.DecryptFailed) {
            e
        }
        assertTrue("必须抛 DecryptFailed", error != null)
        assertTrue(error!!.message!!.contains("解密失败"))
    }

    @Test
    fun `密文被篡改一个字节就解不开（GCM 认证标签）`() {
        val key = randomKey()
        val c = cipher(key)
        val sealed = c.encrypt("hunter2")
        val parts = sealed.split('.').toMutableList()
        val body = java.util.Base64.getUrlDecoder().decode(parts[2])
        body[0] = (body[0].toInt() xor 0x01).toByte()
        parts[2] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body)
        val tampered = parts.joinToString(".")
        var failed = false
        try {
            val out = c.decrypt(tampered)
            failed = out != "hunter2"
        } catch (e: CredentialException.DecryptFailed) {
            failed = true
        }
        assertTrue("篡改后必须解不出原明文", failed)
    }

    @Test
    fun `信封坏掉报 Malformed 而不是 DecryptFailed`() {
        val c = cipher()
        val error = try {
            c.decrypt("not-an-envelope")
            null
        } catch (e: CredentialException.Malformed) {
            e
        }
        assertTrue("必须抛 Malformed", error != null)
    }

    @Test
    fun `密钥来源不可用时加密与解密都报 Unavailable`() {
        val broken = AesGcmCredentialCipher { throw IllegalStateException("keystore 被清了") }
        val onEncrypt = try {
            broken.encrypt("x")
            null
        } catch (e: CredentialException.Unavailable) {
            e
        }
        assertTrue(onEncrypt != null)
        // 解密：信封合法但密钥拿不到 → 同样是 Unavailable（提示重新录入）
        val good = cipher()
        val sealed = good.encrypt("x")
        val onDecrypt = try {
            broken.decrypt(sealed)
            null
        } catch (e: CredentialException.Unavailable) {
            e
        }
        assertTrue(onDecrypt != null)
        assertTrue(onDecrypt!!.message!!.contains("密钥库"))
    }

    @Test
    fun `密钥来源抛 CredentialException 时原样透出`() {
        val broken = AesGcmCredentialCipher { throw CredentialException.Unavailable("自定义提示") }
        val error = try {
            broken.encrypt("x")
            null
        } catch (e: CredentialException.Unavailable) {
            e
        }
        assertEquals("自定义提示", error!!.message)
    }
}
