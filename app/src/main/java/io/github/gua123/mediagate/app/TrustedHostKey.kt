package io.github.gua123.mediagate.app

/**
 * 设置页展示用的一行"已信任的 SFTP 主机指纹"（**R2 / plan 4.9**）。
 *
 * @param sha256 SHA-256 指纹的 Base64（展示时前面加 `SHA256:`，与 OpenSSH 口径一致）。
 */
data class TrustedHostKey(
    val host: String,
    val port: Int,
    val keyType: String,
    val sha256: String,
    val md5: String,
) {

    /** 列表主标题：`host:port`。 */
    val endpoint: String get() = host + ":" + port

    /** 展示用指纹：`SHA256:xxxx`。 */
    val fingerprint: String get() = "SHA256:" + sha256
}
