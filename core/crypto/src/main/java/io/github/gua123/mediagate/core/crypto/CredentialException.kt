package io.github.gua123.mediagate.core.crypto

/**
 * 凭据加解密失败（**R6**）。
 *
 * 分成四类是为了让 UI 能给出可操作的中文提示，而不是一句「解密失败」：
 * 格式坏 → 重新录入；密钥不可用 → 重新录入（Keystore 密钥可能已被系统清掉）；
 * 认证失败 → 同上；加密失败 → 一般是环境问题，可重试。
 *
 * 四个子类的 [message] 一律中文（R16），可直接展示。
 */
sealed class CredentialException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Keystore / 密钥不可用：密钥被系统清除、设备未启用锁屏、Provider 异常等。 */
    class Unavailable(message: String? = null, cause: Throwable? = null) :
        CredentialException(message ?: "系统密钥库不可用，请重新录入密码", cause)

    /** 密文信封格式不合法（不是本程序写入的、或被截断 / 篡改）。 */
    class Malformed(message: String? = null, cause: Throwable? = null) :
        CredentialException(message ?: "凭据密文格式不正确，请重新录入密码", cause)

    /** 加密失败。 */
    class EncryptFailed(message: String? = null, cause: Throwable? = null) :
        CredentialException(message ?: "密码加密失败，请重试", cause)

    /** 解密失败（认证标签校验不通过：密钥已更换或密文被改动）。 */
    class DecryptFailed(message: String? = null, cause: Throwable? = null) :
        CredentialException(message ?: "密码解密失败（密钥已更换或密文被改动），请重新录入", cause)
}
