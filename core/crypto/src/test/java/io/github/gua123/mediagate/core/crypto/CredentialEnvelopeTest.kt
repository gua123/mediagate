package io.github.gua123.mediagate.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [CredentialEnvelope] 的 JVM 单测（**R6** 凭据加密：信封格式与错误映射——纯逻辑部分）。
 *
 * 覆盖：往返、URL 安全 Base64、各种坏信封一律 [CredentialException.Malformed]（中文提示），
 * 以及 [CredentialEnvelope.isEnvelope] 对「密文 / 明文」的判定（连接表单据此决定是否回显）。
 */
class CredentialEnvelopeTest {

    private val iv = ByteArray(CredentialEnvelope.GCM_IV_BYTES) { it.toByte() }
    private val ciphertext = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun `seal 与 open 往返一致`() {
        val sealed = CredentialEnvelope.seal(iv, ciphertext)
        val opened = CredentialEnvelope.open(sealed)
        assertEquals(CredentialEnvelope.Opened(iv, ciphertext), opened)
        assertTrue(sealed.startsWith(CredentialEnvelope.PREFIX + "."))
    }

    @Test
    fun `信封里只有三段且 Base64 是 URL 安全无填充`() {
        val sealed = CredentialEnvelope.seal(ByteArray(12) { 0xFB.toByte() }, ByteArray(33) { 0xFF.toByte() })
        val parts = sealed.split('.')
        assertEquals(3, parts.size)
        assertFalse("不能出现标准 Base64 的 + / = 字符", sealed.contains('+') || sealed.contains('/') || sealed.contains('='))
        // 手工解码验证确实是 URL 安全变体
        assertEquals(12, Base64.getUrlDecoder().decode(parts[1]).size)
        assertEquals(33, Base64.getUrlDecoder().decode(parts[2]).size)
    }

    @Test
    fun `isEnvelope 认得密文、不认明文与空串`() {
        assertTrue(CredentialEnvelope.isEnvelope(CredentialEnvelope.seal(iv, ciphertext)))
        assertFalse(CredentialEnvelope.isEnvelope(null))
        assertFalse(CredentialEnvelope.isEnvelope(""))
        assertFalse(CredentialEnvelope.isEnvelope("   "))
        assertFalse(CredentialEnvelope.isEnvelope("hunter2"))
        assertFalse(CredentialEnvelope.isEnvelope("MGC1.only-two-segments"))
        assertFalse(CredentialEnvelope.isEnvelope("MGC2.aaa.bbb"))
    }

    @Test
    fun `前缀不对报 Malformed`() {
        val e = assertThrowsMalformed("XXX.aaa.bbb")
        assertTrue(e.message!!.contains("MGC1"))
    }

    @Test
    fun `段数不对报 Malformed`() {
        assertThrowsMalformed("MGC1.aaa")
        assertThrowsMalformed("MGC1.aaa.bbb.ccc")
    }

    @Test
    fun `Base64 段非法报 Malformed`() {
        val e = assertThrowsMalformed("MGC1.!!!.bbb")
        assertTrue(e.message!!.contains("Base64"))
    }

    @Test
    fun `IV 或密文为空报 Malformed`() {
        // "." 切分后得到一个空段（长度 0 的 Base64 解码结果为空数组）
        assertThrowsMalformed("MGC1.." + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext))
        assertThrowsMalformed("MGC1." + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + ".")
    }

    @Test
    fun `信封允许首尾空白（从数据库或剪贴板复制粘贴）`() {
        val sealed = CredentialEnvelope.seal(iv, ciphertext)
        assertEquals(CredentialEnvelope.Opened(iv, ciphertext), CredentialEnvelope.open("  " + sealed + "\n"))
    }

    private fun assertThrowsMalformed(envelope: String): CredentialException.Malformed {
        val error = try {
            CredentialEnvelope.open(envelope)
            null
        } catch (e: CredentialException.Malformed) {
            e
        }
        assertTrue("应当抛 Malformed：" + envelope, error != null)
        assertTrue("提示应是中文：" + error!!.message, error.message!!.any { it.code in 0x4E00..0x9FFF })
        return error
    }
}
