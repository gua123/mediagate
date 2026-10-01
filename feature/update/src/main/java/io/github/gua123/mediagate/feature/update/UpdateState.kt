package io.github.gua123.mediagate.feature.update

import io.github.gua123.mediagate.core.download.DownloadProgress
import java.io.File

/**
 * 设置页「应用更新」的界面状态（**R20**）。
 *
 * 单一状态源：组合函数只读它，所有副作用都经 [UpdateViewModel] 发起。
 */
data class UpdateUiState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0L,
    val status: UpdateStatus = UpdateStatus.Idle,
    /** 一次性提示（Snackbar 用），消费后由 [UpdateViewModel.dismissNotice] 清空。 */
    val notice: String? = null,
) {

    /** 正在忙（检查中或下载中）时不重复触发。 */
    val busy: Boolean get() = status is UpdateStatus.Checking || status is UpdateStatus.Downloading

    /** 有新版本可下载。 */
    val canDownload: Boolean get() = status is UpdateStatus.Available

    /** 已下载待安装（签名校验通过）。 */
    val canInstall: Boolean get() = status is UpdateStatus.Downloaded
}

/** 更新流程的状态机。 */
sealed interface UpdateStatus {

    /** 还没检查过。 */
    data object Idle : UpdateStatus

    /** 正在检查（拉清单 + 比版本）。 */
    data object Checking : UpdateStatus

    /** 已是最新。 */
    data class UpToDate(val versionName: String) : UpdateStatus

    /** 有新版本，等用户点下载。 */
    data class Available(val manifest: UpdateManifest) : UpdateStatus

    /** 下载中（进度来自 [DownloadProgress]，含断点续传标记）。 */
    data class Downloading(val manifest: UpdateManifest, val progress: DownloadProgress) : UpdateStatus

    /** 下载完成（已通过大小 + SHA-256 + 签名校验），等用户点安装。 */
    data class Downloaded(val manifest: UpdateManifest, val file: File) : UpdateStatus

    /** 失败（中文原因，直接展示）。 */
    data class Failed(val message: String) : UpdateStatus
}

/** 字节数 → 「12.3 MB」；未知大小（<= 0）给空串。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
}
