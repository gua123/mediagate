package io.github.gua123.mediagate.media.asr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.gua123.mediagate.core.download.DownloadError
import io.github.gua123.mediagate.core.download.DownloadException
import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.core.download.HttpRequest
import io.github.gua123.mediagate.core.download.HttpStream
import io.github.gua123.mediagate.core.download.HttpTransport
import java.io.IOException
import java.net.HttpURLConnection

/**
 * App 内下载模型（**M7-B / R14 的默认获取方式**：进度 / 断点续传 / 取消 / 校验和失败重下）。
 *
 * 流程（每一步都在 JVM 单测里用假 [HttpTransport] 加真临时目录走查过）：
 * 1. 先下到「文件名 + .part」；已存在就从它的长度**续传**（发 Range 头）；
 * 2. 服务端回 206 → 接着写；回 200 → 说明它不支持 Range，**从头重下**（不拼接，避免脏数据）；
 * 3. 每读一块检查协程是否被取消：取消时**保留 .part**（下次还能续），不写坏最终文件；
 * 4. 字节数必须等于 [WhisperModel.sizeBytes]，有 [WhisperModel.sha256] 时再核一遍；
 * 5. 校验不过 → **删掉 .part 并抛错**（下次从头重下，不会拿着坏文件反复失败）；
 * 6. 都过了才改名为最终文件名。
 *
 * 2026-10-02（R20）：HTTP 传输、进度与错误分类已抽到 `:core:download`，与 APK 更新下载共用
 * （[io.github.gua123.mediagate.core.download.FileDownloader]）；本类保留"模型目录 + ModelStore"这层。
 */
class ModelDownloader(
    private val store: ModelStore,
    private val transport: HttpTransport,
) {

    /**
     * 下载 [model]，成功返回最终文件名（用 [ModelStore.pathOf] 取绝对路径）。
     *
     * @param mirror 镜像前缀；null 表示直连。
     * @param onProgress 进度回调（在下载协程里同步调用，别做重活）。
     * @throws DownloadException 分类失败。
     * @throws kotlinx.coroutines.CancellationException 用户取消（.part 保留）。
     */
    suspend fun download(
        model: WhisperModel,
        mirror: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): String {
        val partName = partNameOf(model)
        var received = existingPartBytes(partName, model)
        onProgress(DownloadProgress(received, model.sizeBytes, received))

        if (received < model.sizeBytes) {
            val request = HttpRequest(model.downloadUrl(mirror), rangeStart = received.takeIf { it > 0L })
            val stream = try {
                transport.open(request)
            } catch (e: IOException) {
                throw DownloadException(DownloadError.NETWORK, e.message, e)
            }
            stream.use { response ->
                when (response.code) {
                    HttpURLConnection.HTTP_OK -> {
                        // 服务端忽略了 Range：从头写，绝不把新内容接在旧内容后面
                        received = 0L
                    }

                    HttpURLConnection.HTTP_PARTIAL -> Unit

                    else -> throw DownloadException(DownloadError.HTTP, "HTTP " + response.code)
                }
                writeBody(model, partName, stream, received, onProgress)
            }
        }

        verify(model, partName)
        if (!store.rename(partName, model.fileName)) {
            // 罕见：改名失败（目标被占 / 权限）。至少把内容留在 .part，报 STORAGE 让用户重试。
            throw DownloadException(DownloadError.STORAGE, "重命名 " + partName + " 失败")
        }
        return model.fileName
    }

    /** 已经下了多少（.part 长度）；超过官方大小说明是坏文件，删掉重来。 */
    private fun existingPartBytes(partName: String, model: WhisperModel): Long {
        if (!store.exists(partName)) return 0L
        val size = store.size(partName)
        if (size <= 0L || size > model.sizeBytes) {
            store.delete(partName)
            return 0L
        }
        return size
    }

    /** 边读边写；每块检查一次取消。 */
    private suspend fun writeBody(
        model: WhisperModel,
        partName: String,
        stream: HttpStream,
        startBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        var received = startBytes
        try {
            store.openWrite(partName, append = startBytes > 0L).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    received += read
                    onProgress(DownloadProgress(received, model.sizeBytes, startBytes))
                }
                output.flush()
            }
        } catch (e: IOException) {
            throw DownloadException(DownloadError.STORAGE, e.message, e)
        }
    }

    /** 字节数加可选 SHA-256 校验；不过就删掉 .part 让下次从头下。 */
    private fun verify(model: WhisperModel, partName: String) {
        val size = store.size(partName)
        if (size != model.sizeBytes) {
            store.delete(partName)
            throw DownloadException(
                DownloadError.SIZE_MISMATCH,
                "实际 " + size + " 字节，预期 " + model.sizeBytes + " 字节",
            )
        }
        val expected = model.sha256?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        val actual = store.sha256(partName)
        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
            store.delete(partName)
            throw DownloadException(DownloadError.CHECKSUM_MISMATCH, "校验和不符，已删除损坏文件")
        }
    }

    /** 断点续传用的临时文件名。 */
    fun partNameOf(model: WhisperModel): String = model.fileName + WhisperModel.PART_SUFFIX

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
