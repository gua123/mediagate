
package io.github.gua123.mediagate.app

/**
 * 一次「测速」的结果（**2026-10-03**：用户实测公网 SFTP ≈0.9 MB/s、WebDAV ≈0.1 MB/s，
 * 但两边数字都远低于他 15 MB/s 的上传带宽，**需要一个走真实播放路径的仪表**才能定性）。
 *
 * @param bytes 实际读到的字节数。
 * @param millis 耗时（毫秒）。
 * @param error 失败原因（中文）；成功时为 null。
 */
data class ThroughputReport(
    val bytes: Long,
    val millis: Long,
    val error: String? = null,
) {

    /** 平均速度（MB/s）。 */
    val mbPerSecond: Double
        get() = if (millis <= 0L) 0.0 else (bytes.toDouble() / 1048576.0) / (millis.toDouble() / 1000.0)
}

/**
 * 把结果拼成一段可复制的话（**纯函数**，JVM 单测覆盖）。
 *
 * @param path 被测文件路径。
 * @param parallelChunks 当前并发块数（写进结果，方便对比不同设置）。
 * @param chunkBytes 当前块大小。
 * @param readAheadSegments 当前预读段数。
 */
fun formatThroughput(
    report: ThroughputReport,
    path: String,
    parallelChunks: Int,
    chunkBytes: Long,
    readAheadSegments: Int,
): String {
    val settings = "并发 " + parallelChunks + " · 块 " + humanBytes(chunkBytes) + " · 预读 " + readAheadSegments + " 段"
    if (report.error != null) {
        return "测速失败：" + report.error + "\n（文件：" + path + "；设置：" + settings + "）"
    }
    val speed = "%.2f".format(report.mbPerSecond)
    val seconds = "%.1f".format(report.millis / 1000.0)
    return "速度 " + speed + " MB/s" +
        "（读了 " + humanBytes(report.bytes) + "，用时 " + seconds + " 秒）" +
        "\n文件：" + path +
        "\n设置：" + settings +
        "\n提示：换一组设置再测一次就能对比；测一个**没播放过**的文件最准（已缓存的段不走网络）。"
}

/** 字节 → 人话。 */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
