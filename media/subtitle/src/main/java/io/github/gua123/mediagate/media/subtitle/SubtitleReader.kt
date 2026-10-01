package io.github.gua123.mediagate.media.subtitle

import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.ByteArrayOutputStream

/**
 * 从存储后端读一份字幕并解析（R14：本地与远端走同一套代码）。
 *
 * 远端字幕不落盘：直接经由 [StorageBackend.openRead] 拿到字节流（WebDAV 的 GET、SFTP 的读通道、
 * FTP 的 RETR 或本地文件流），因此「能列目录就能加载字幕」。
 *
 * 字幕文件很小，读满 [MAX_BYTES] 就停（防御性上限，避免误选一个几百 MB 的文件把内存打满）。
 */
object SubtitleReader {

    /** 单个字幕文件的读取上限（2 MiB）；正常字幕几十 KB 足够。 */
    const val MAX_BYTES = 2 * 1024 * 1024

    private const val READ_BUFFER_BYTES = 64 * 1024

    /**
     * 读取并解析 [path]。
     *
     * @throws StorageException 读失败（无权限 / 不存在 / 网络…），由播放页翻译成中文提示。
     */
    suspend fun read(backend: StorageBackend, path: String): SubtitleParseResult {
        val format = SubtitleFormat.fromFileName(path)
        val bytes = readBytes(backend, path)
        val text = SubtitleTextCodec.decode(bytes)
        if (text.isBlank()) throw StorageException.NotFound("字幕文件是空的：" + path)
        return SubtitleParser.parse(text, format ?: SubtitleParser.sniff(text))
    }

    /** 只读字节（不做解析），便于需要原文或另存时复用。 */
    suspend fun readBytes(backend: StorageBackend, path: String, limit: Int = MAX_BYTES): ByteArray =
        backend.openRead(path).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (out.size() < limit) {
                val want = minOf(buffer.size, limit - out.size())
                val read = stream.read(buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
}
