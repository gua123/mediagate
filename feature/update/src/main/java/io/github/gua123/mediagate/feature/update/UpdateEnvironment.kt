package io.github.gua123.mediagate.feature.update

import java.io.File

/**
 * 更新功能需要的宿主能力（**R20**）——由 :app 实现（只有它拿得到包管理器、FileProvider 等）。
 *
 * 把这些挡在接口后面，:feature:update 才能在 JVM 单测里用假实现把状态机跑全：
 * 检查 → 下载（进度/续传）→ 签名校验 → 调起安装器。
 */
interface UpdateEnvironment {

    /** 当前安装包的版本名（如 0.1.1）。 */
    val currentVersionName: String

    /** 当前安装包的 versionCode（比较新旧只看它）。 */
    val currentVersionCode: Long

    /** 下载落点（父目录由实现负责创建；文件名建议带版本号，便于人工辨认）。 */
    fun downloadTarget(manifest: UpdateManifest): File

    /**
     * 校验下载好的 APK 的**签名证书**是否与当前安装包一致。
     *
     * 这是防"中间人换包"的最后一道闸：清单里的 SHA-256 只能证明"下到的和清单一致"，
     * 只有签名证书一致才能证明"这个包和现在跑着的是同一个作者签的"。
     */
    fun verifySignature(apk: File): SignatureCheck

    /** 调起系统安装器（Android 不允许静默安装，这一步一定有系统确认页）。 */
    fun installApk(apk: File): Boolean

    /** 打开发布页（给"连不上 GitHub 就别在应用内更新"的用户一个手动出口）。 */
    fun openReleasesPage(): Boolean
}

/** 签名校验结论。 */
sealed interface SignatureCheck {

    /** 与当前安装包同一证书，可以安装。 */
    data object Match : SignatureCheck

    /** 证书不一致（疑似被换包）——必须拒绝安装。 */
    data class Mismatch(val expected: String, val actual: String?) : SignatureCheck

    /** 读不出签名（文件坏了 / 系统不给读）。 */
    data object Unknown : SignatureCheck
}
