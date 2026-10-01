package io.github.gua123.mediagate.core.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * 通用文件下载器（**R20** 应用内更新的 APK 下载；与 **R14** 的模型下载同一套口径）。
 *
 * 流程（JVM 单测用假 [HttpTransport] 加真临时目录逐条走查）：
 * 1. 先下到「文件名 + .part」；已存在就从它的长度**续传**（发 Range 头）；
 * 2. 服务端回 206 → 接着写；回 200 → 说明它不支持 Range，**从头重下**（不拼接，避免脏数据）；
 * 3. 每读一块检查协程是否被取消：取消时**保留 .part**（下次还能续），不写坏最终文件；
 * 4. 给了 [expectedBytes] 就必须相等（防"被链路截断却报成功"）；给了 [expectedSha256] 再核一遍；
 * 5. 校验不过 → **删掉 .part 并抛 [DownloadException]**（下次从头下，不拿坏文件反复失败）；
 * 6. 都过了才改名为最终文件名。
 */
class FileDownloader(private val transport: HttpTransport) {

    /**
     * 下载 [url] 到 [target]（父目录会自动建）。
     *
     * @param expectedBytes 期望字节数；null 或 <= 0 表示"不知道大小"（此时不校验长度，只按流读完）。
     * @param expectedSha256 期望的 SHA-256（小写十六进制）；null/空白表示不校验。
     * @param headers 额外请求头（续传时 Range 由下载器自己加）。
     * @param onProgress 进度回调（在下载协程里同步调用，别做重活）。
     * @throws DownloadException 分类失败。
     * @throws kotlinx.coroutines.CancellationException 用户取消（.part 保留，下次可续）。
     */
    suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        target.parentFile?.mkdirs()
        val part = partFileOf(target)
        val total = expectedBytes?.takeIf { it > 0L } ?: 0L

        var received = existingPartBytes(part, total)
        onProgress(DownloadProgress(received, total, received))

        if (total <= 0L || received < total) {
            val request = HttpRequest(url, rangeStart = received.takeIf { it > 0L }, headers = headers)
            val stream = try {
                transport.open(request)
            } catch (e: IOException) {
                throw DownloadException(DownloadError.NETWORK, e.message, e)
            }
            stream.use { response ->
                when (response.code) {
                    // 服务端忽略了 Range：从头写，绝不把新内容接在旧内容后面
                    HttpURLConnection.HTTP_OK -> received = 0L
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    else -> throw DownloadException(DownloadError.HTTP, "HTTP " + response.code)
                }
                writeBody(part, stream, received, total, onProgress)
            }
        }

        verify(part, expectedBytes, expectedSha256)
        if (target.exists() && !target.delete()) {
            throw DownloadException(DownloadError.STORAGE, "覆盖旧文件失败：" + target.name)
        }
        if (!part.renameTo(target)) {
            throw DownloadException(DownloadError.STORAGE, "重命名 " + part.name + " 失败")
        }
        return target
    }

    /** 已经下了多少（.part 长度）；超过期望大小说明是坏文件，删掉重来。 */
    private fun existingPartBytes(part: File, expectedBytes: Long): Long {
        if (!part.isFile) return 0L
        val size = part.length()
        if (size <= 0L || (expectedBytes > 0L && size > expectedBytes)) {
            part.delete()
            return 0L
        }
        return size
    }

    /** 边读边写；每块检查一次取消。 */
    private suspend fun writeBody(
        part: File,
        stream: HttpStream,
        startBytes: Long,
        totalBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        var received = startBytes
        try {
            FileOutputStream(part, startBytes > 0L).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    received += read
                    onProgress(DownloadProgress(received, totalBytes, startBytes))
                }
                output.flush()
            }
        } catch (e: IOException) {
            throw DownloadException(DownloadError.STORAGE, e.message, e)
        }
    }

    /** 字节数加可选 SHA-256 校验；不过就删掉 .part 让下次从头下。 */
    private fun verify(part: File, expectedBytes: Long?, expectedSha256: String?) {
        if (expectedBytes != null && expectedBytes > 0L) {
            val size = part.length()
            if (size != expectedBytes) {
                part.delete()
                throw DownloadException(
                    DownloadError.SIZE_MISMATCH,
                    "实际 " + size + " 字节，预期 " + expectedBytes + " 字节",
                )
            }
        }
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val actual = sha256Of(part)
        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
            part.delete()
            throw DownloadException(DownloadError.CHECKSUM_MISMATCH, "校验和不符，已删除损坏文件")
        }
    }

    companion object {

        /** 断点续传用的临时文件后缀。 */
        const val PART_SUFFIX = ".part"

        /** 读块大小（64 KiB）。 */
        const val BUFFER_BYTES = 64 * 1024

        /** .part 文件（供"检查有没有下到一半"用）。 */
        fun partFileOf(target: File): File = File(target.parentFile, target.name + PART_SUFFIX)

        /** 计算文件 SHA-256（小写十六进制）；不存在或读失败返回 null。 */
        fun sha256Of(file: File): String? {
            if (!file.isFile) return null
            return runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }.getOrNull()
        }
    }
}
