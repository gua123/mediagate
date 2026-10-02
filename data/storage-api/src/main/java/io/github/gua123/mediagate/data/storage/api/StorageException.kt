package io.github.gua123.mediagate.data.storage.api

import java.io.IOException

/**
 * 存储层统一异常（R2 四协议 / R8 连通性测试的错误分类 / R14 无写权限降级）。
 *
 * 各后端把协议细节（HTTP 状态码、SFTP 状态字、FTP 应答码、SAF SecurityException…）
 * 翻译成下面几个语义分类，上层据此决定提示文案与降级策略，不再判断协议细节。
 *
 * 继承 [IOException]，可直接被上层的 IOException 捕获兜住。
 */
sealed class StorageException(
    message: String?,
    cause: Throwable?,
) : IOException(message, cause) {

    /** 路径不存在（HTTP 404 / SFTP no such file / FileNotFound）。 */
    class NotFound(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "路径不存在", cause)

    /**
     * 无权限：无读权限，或**无写权限**。
     *
     * R14 要求明确区分「字幕写不回远端目录」这一种情况——上层收到本异常时
     * 自动把字幕落到 App 缓存并提示另存/分享，绝不静默丢弃。
     */
    class AccessDenied(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "无访问权限", cause)

    /** 协议或后端不支持该操作（如对管道型 SAF 文档后退 seek、向只读协议写文件）。 */
    class NotSupported(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "该后端不支持此操作", cause)

    /** 网络层失败：连不上、超时、传输中断（R7 选路与重试的依据）。 */
    class Network(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "网络不可用", cause)

    /** 认证失败：账号密码错、密钥被拒（HTTP 401/403、SFTP 认证失败、FTP 530）。 */
    class Auth(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "认证失败", cause)

    /**
     * **操作超时**（2026-10-03 真机"放后台再回来就卡住"）：
     * 由 [TimeoutStorageBackend] 在超过上限时抛出，界面据此给出"超时 + 重试"，
     * 而不是永远停在"刷新中"。
     */
    class Timeout(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "操作超时", cause)

    /** 其余未分类的底层 I/O 错误（保留原始 cause 便于诊断）。 */
    class Unknown(message: String? = null, cause: Throwable? = null) :
        StorageException(message ?: "存储操作失败", cause)
}
