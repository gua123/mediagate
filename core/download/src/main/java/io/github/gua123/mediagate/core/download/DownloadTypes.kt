package io.github.gua123.mediagate.core.download

import java.io.IOException

/** 下载失败的分类（每一种都有中文说明，界面直接显示）。 */
enum class DownloadError(val zhText: String) {

    /** 网络中断 / 连不上 / 超时。 */
    NETWORK("网络中断"),

    /** 服务器返回非 200/206。 */
    HTTP("服务器返回错误"),

    /** 下载完的字节数与官方大小不符（多半是被截断）。 */
    SIZE_MISMATCH("下载不完整"),

    /** 校验和与预期不符（文件被改坏 / 中间有代理插了内容）。 */
    CHECKSUM_MISMATCH("校验和不符"),

    /** 本地写不进去（磁盘满 / 目录不可写）。 */
    STORAGE("本地写入失败"),
}

/** 下载异常（带分类，便于界面给中文原因）。 */
class DownloadException(
    val kind: DownloadError,
    detail: String? = null,
    cause: Throwable? = null,
) : IOException(if (detail.isNullOrBlank()) kind.zhText else kind.zhText + "：" + detail, cause)

/**
 * 下载进度（**R14/R20：下载要显示进度**）。
 *
 * @property receivedBytes 已下载字节数（含断点续传前已有的部分）。
 * @property totalBytes 总字节数（清单里给的官方大小，已知）。
 * @property resumedFrom 本次开始时已有的字节数（> 0 表示是续传）。
 */
data class DownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long,
    val resumedFrom: Long = 0L,
) {

    /** 0..1。 */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (receivedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()

    /** 0..100。 */
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)

    /** 是否续传中。 */
    val isResuming: Boolean get() = resumedFrom > 0L
}
