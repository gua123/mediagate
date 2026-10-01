package io.github.gua123.mediagate.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore 版的凭据加解密（**R6**：凭据只以密文落库）。
 *
 * 为什么不用 `androidx.security:security-crypto`：该库已被 Google 标记废弃
 * （EncryptedSharedPreferences / EncryptedFile 不再维护），而我们要的只是
 * 「一把不可导出的 AES 密钥 + AES-GCM」——直接用 Keystore 更短、更可控、无额外依赖。
 *
 * 行为：
 * - 密钥别名固定（默认 [DEFAULT_ALIAS]），首次调用时在 **AndroidKeyStore** 里生成 256 位 AES 密钥，
 *   之后一直复用；`setUserAuthenticationRequired(false)`（不与锁屏绑定，符合"App 无登录"的 R6 口径）；
 * - 密钥**不可导出**（Keystore 保证），进程里只拿得到 [SecretKey] 句柄；
 * - 加密/解密算法与信封格式复用 [AesGcmCredentialCipher]，与本模块的 JVM 单测完全同源；
 * - 密文与应用绑定：卸载重装、清除应用数据、恢复出厂后密钥消失，
 *   此时解密会抛 [CredentialException.Unavailable] / [CredentialException.DecryptFailed]，
 *   界面提示「重新录入密码」。
 *
 * **只在真机上才能验证**（JVM 单测里 AndroidKeyStore 不存在）：本模块的单测只覆盖
 * [CredentialEnvelope] 与 [AesGcmCredentialCipher]（用本地随机密钥），Keystore 部分保证编译通过。
 *
 * @param context 应用上下文（只用于取 Keystore，不落盘任何文件）。
 * @param alias 密钥别名；同一 App 内固定。
 */
class KeystoreCredentialCipher(
    context: Context,
    private val alias: String = DEFAULT_ALIAS,
) : CredentialCipher {

    /** AndroidKeyStore 是系统级 Provider，取句柄很便宜；这里仍然缓存一次避免重复加载。 */
    private val keyStore: KeyStore = loadKeyStore()

    private val delegate = AesGcmCredentialCipher { loadOrCreateKey() }

    // context 参与签名是为了强调「凭据随应用沙箱」；Keystore 本身按 uid 隔离，不需要真的用 context
    private val appContext: Context = context.applicationContext

    override fun encrypt(plaintext: String): String = delegate.encrypt(plaintext)

    override fun decrypt(envelope: String): String = delegate.decrypt(envelope)

    /** 取回密钥；不存在则生成。 */
    private fun loadOrCreateKey(): SecretKey {
        // 先看是否有：Keystore 里的密钥在应用生命周期内一直有效
        keyStore.getKey(alias, null)?.let { existing ->
            return existing as? SecretKey
                ?: throw CredentialException.Unavailable("密钥库里的 " + alias + " 不是 AES 密钥")
        }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // GCM 必须用随机 IV：Keystore 会拒绝调用方自带的 IV（除非显式关掉这一项）
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            generator.generateKey()
        } catch (t: Throwable) {
            throw CredentialException.Unavailable(
                "无法创建系统密钥（" + appContext.packageName + "）：请重试或重启应用",
                t,
            )
        }
    }

    companion object {
        /** AndroidKeyStore 是固定名字，不是可配置项。 */
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"

        /** 默认密钥别名（同一个 App 内唯一）。 */
        const val DEFAULT_ALIAS: String = "mediagate.credentials.v1"

        /** AES-256（澎湃 OS / Android 13+ 均支持）。 */
        private const val KEY_SIZE_BITS = 256

        private fun loadKeyStore(): KeyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (t: Throwable) {
            throw CredentialException.Unavailable("系统密钥库不可用", t)
        }
    }
}
