package io.github.gua123.mediagate.core.crypto

/**
 * 协议凭据的加解密门面（**R6**：App 本身无账号体系，但 WebDAV / SFTP / FTP 等协议凭据要落库）。
 *
 * 上层（连接管理页 / :app 组合根）只认本接口，不感知 Keystore 细节：
 * - 写入：用户在表单里输入密码 → [encrypt] → 密文存进 `connection.secretRef`；
 * - 读出：[decrypt] 还原明文，仅用于构造存储后端，**不回显到界面**。
 *
 * 实现必须满足：
 * - 同一明文两次 [encrypt] 得到不同密文（随机 IV，见 [AesGcmCredentialCipher]）；
 * - 密文自包含（IV + 认证标签都在信封里），不需要额外的盐/IV 列；
 * - 失败一律抛 [CredentialException]，绝不返回半成品字符串。
 *
 * 线程安全：实现须可被多个协程并发调用（无共享可变状态）。
 */
interface CredentialCipher {

    /**
     * 加密 [plaintext]。
     *
     * @return Base64 信封字符串（可安全存进 Room 的 TEXT 列）。
     * @throws CredentialException.Unavailable 密钥不可用（Keystore 被清、设备未设锁屏等）。
     * @throws CredentialException.EncryptFailed 加密过程失败。
     */
    fun encrypt(plaintext: String): String

    /**
     * 解密 [envelope]（[encrypt] 的产物）。
     *
     * @throws CredentialException.Malformed 信封格式不对（不是本实现的产出 / 被截断）。
     * @throws CredentialException.DecryptFailed 认证标签校验失败（密钥换了或被篡改）。
     * @throws CredentialException.Unavailable 密钥不可用。
     */
    fun decrypt(envelope: String): String
}
