package io.github.gua123.mediagate.core.crypto

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥来源：每次加密/解密时取一把 AES 密钥（**R6**）。
 *
 * 抽成接口是为了让「纯逻辑」可 JVM 单测：单测给一把本地随机密钥，
 * 真机给 [KeystoreCredentialCipher] 的 AndroidKeyStore 密钥——两边跑的是同一份 AES-GCM 代码。
 */
fun interface AesKeyProvider {

    /**
     * 取回（必要时生成）AES 密钥。
     *
     * @throws CredentialException.Unavailable 密钥不可用。
     */
    fun key(): SecretKey
}

/**
 * AES-GCM 凭据加解密（**R6**：协议凭据只以密文落库）。
 *
 * - 算法：`AES/GCM/NoPadding`，128 位认证标签，12 字节随机 IV（由 Cipher 在 ENCRYPT_MODE 下生成）；
 * - 每次加密都是新 IV（`SecureRandom`），因此同一明文两次加密结果不同——
 *   这一点很重要：能从密文看出「两个连接用的是同一个密码」本身就是信息泄漏；
 * - 密文自包含 IV 与认证标签（见 [CredentialEnvelope]），不需要额外的列/字段；
 * - 解密时认证标签校验失败 → [CredentialException.DecryptFailed]，绝不返回错误的明文。
 *
 * 本类**不碰 Android API**，JVM 单测直接可跑（见 `AesGcmCredentialCipherTest`）。
 *
 * @param keys 密钥来源（真机是 Keystore，单测是本地随机密钥）。
 */
class AesGcmCredentialCipher(private val keys: AesKeyProvider) : CredentialCipher {

    override fun encrypt(plaintext: String): String {
        val key = obtainKey()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            return CredentialEnvelope.seal(cipher.iv, ciphertext)
        } catch (e: GeneralSecurityException) {
            throw CredentialException.EncryptFailed("密码加密失败：" + e.javaClass.simpleName, e)
        }
    }

    override fun decrypt(envelope: String): String {
        val opened = CredentialEnvelope.open(envelope)
        val key = obtainKey()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(CredentialEnvelope.GCM_TAG_BITS, opened.iv),
            )
            val plain = cipher.doFinal(opened.ciphertext)
            return String(plain, StandardCharsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // 认证标签不匹配：密钥换了 / 密文被改动 / 不是本机写的密文
            throw CredentialException.DecryptFailed("密码解密失败（密钥已更换或密文被改动），请重新录入", e)
        } catch (e: GeneralSecurityException) {
            throw CredentialException.DecryptFailed("密码解密失败：" + e.javaClass.simpleName, e)
        }
    }

    private fun obtainKey(): SecretKey = try {
        keys.key()
    } catch (e: CredentialException) {
        throw e
    } catch (t: Throwable) {
        throw CredentialException.Unavailable("系统密钥库不可用，请重新录入密码", t)
    }

    companion object {
        /** JCE 变换串：AES-GCM，无填充（GCM 自带认证）。 */
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
    }
}
