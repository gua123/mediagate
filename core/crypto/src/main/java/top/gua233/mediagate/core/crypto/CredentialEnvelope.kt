package io.github.gua123.mediagate.core.crypto

import java.util.Base64

/**
 * 凭据密文的信封格式（**R6**）——纯逻辑，可 JVM 单测。
 *
 * 格式（单行文本，可直接进 Room 的 TEXT 列）：
 *
 * ```
 * MGC1.<Base64(IV)>.<Base64(密文+认证标签)>
 * ```
 *
 * - `MGC1` = MediaGate Credential v1，前缀带版本号，将来换算法时可识别旧数据并给出明确提示；
 * - 所有 Base64 段都是 **URL 安全、无填充**（`Base64.getUrlEncoder().withoutPadding()`），
 *   避免 `+` `/` `=` 在日志/URL/命令里被转义；
 * - 解密方按 `.` 切三段，任何缺失/多余/解码失败都归为 [CredentialException.Malformed]。
 *
 * 不做任何 IO、不碰 Android API：Keystore 只在 [KeystoreCredentialCipher] 里出现。
 */
object CredentialEnvelope {

    /** 版本前缀（MediaGate Credential v1）。 */
    const val PREFIX: String = "MGC1"

    private const val SEPARATOR = '.'
    private const val SEGMENTS = 3

    /** GCM 认证标签长度（bit）：128 位是 Android Keystore 支持的最强档。 */
    const val GCM_TAG_BITS: Int = 128

    /** GCM 推荐 IV 长度（字节）：12 字节是 GCM 的标准长度（无需再做 GHASH 派生）。 */
    const val GCM_IV_BYTES: Int = 12

    /** 一段密文（IV + 密文）。 */
    data class Opened(val iv: ByteArray, val ciphertext: ByteArray) {

        // data class 里带 ByteArray，equals/hashCode 要按内容比较，否则单测里 assertEquals 会误判
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Opened) return false
            return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
    }

    /** 把 [iv] 与 [ciphertext] 打成一行信封字符串。 */
    fun seal(iv: ByteArray, ciphertext: ByteArray): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return PREFIX + SEPARATOR + encoder.encodeToString(iv) + SEPARATOR + encoder.encodeToString(ciphertext)
    }

    /**
     * 拆开信封。
     *
     * @throws CredentialException.Malformed 前缀不对 / 段数不对 / Base64 解码失败 / IV 与密文为空。
     */
    fun open(envelope: String): Opened {
        val parts = envelope.trim().split(SEPARATOR)
        if (parts.size != SEGMENTS || parts[0] != PREFIX) {
            throw CredentialException.Malformed("凭据密文格式不正确（应以 " + PREFIX + ". 开头）：" + describe(envelope))
        }
        val decoder = Base64.getUrlDecoder()
        val iv = try {
            decoder.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            throw CredentialException.Malformed("凭据密文的 IV 段不是合法 Base64：" + describe(envelope), e)
        }
        val ciphertext = try {
            decoder.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw CredentialException.Malformed("凭据密文的密文段不是合法 Base64：" + describe(envelope), e)
        }
        if (iv.isEmpty()) throw CredentialException.Malformed("凭据密文缺少 IV：" + describe(envelope))
        if (ciphertext.isEmpty()) throw CredentialException.Malformed("凭据密文内容为空：" + describe(envelope))
        return Opened(iv, ciphertext)
    }

    /**
     * 判断一段文本是否「看起来是本实现的密文信封」。
     *
     * 用于连接表单判断 secretRef 里到底是密文还是历史遗留的明文，
     * 也用于界面展示（不回显明文，只显示「已保存」）。
     */
    fun isEnvelope(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val parts = text.trim().split(SEPARATOR)
        return parts.size == SEGMENTS && parts[0] == PREFIX && parts[1].isNotEmpty() && parts[2].isNotEmpty()
    }

    /** 日志/异常里只暴露长度，绝不把密文原样写进日志。 */
    private fun describe(envelope: String): String = "长度=" + envelope.length
}
