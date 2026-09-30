package io.github.gua123.mediagate.media.ffmpeg

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import java.io.File
import java.io.FileOutputStream

/**
 * FFmpeg 的输入准备（R11，plan 第 3 章不变量）。
 *
 * ffmpeg-kit 只吃 URL / 文件路径，而数据层是挂起的随机读（[RandomAccessSource]）：
 * 没有能直接交给 native 的文件描述符，所以中转一层临时文件。抽帧（plan 4.4）与
 * 时间戳重建（plan 4.3 第 2 条）都走这里；**调用方负责在 finally 里删掉临时文件**。
 */
object FfmpegInputs {

    /** 落盘缓冲区：1 MiB（远端读取的合理粒度，与 plan 4.2 的预读窗口同量级）。 */
    const val DEFAULT_BUFFER_SIZE: Int = 1 shl 20

    /**
     * 把 [source] 整份落到 [target]。
     *
     * 每块之间检查协程取消（[ensureActive]），取消时留下半截文件由调用方清理——
     * 这样「抽帧离屏取消」「重建中途取消」不会一直读到文件尾。
     * 调用方需要自己切到 `Dispatchers.IO`。
     */
    suspend fun dumpToFile(
        source: RandomAccessSource,
        target: File,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ) {
        require(bufferSize > 0) { "bufferSize 必须为正：$bufferSize" }
        val total = source.size
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(bufferSize)
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val want = if (total >= 0) minOf(buffer.size.toLong(), total - offset).toInt() else buffer.size
                if (want <= 0) break
                val read = source.readAt(offset, buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                offset += read
            }
            out.flush()
        }
    }

    /**
     * 在 [workDir] 下建一个临时输入文件（`prefix + 随机后缀`）。
     *
     * 不用 `File.createTempFile` 的三参默认目录：Android 上 `java.io.tmpdir` 通常不可写，
     * 临时文件必须落在 App 的 `cacheDir`。
     */
    fun createTemporaryInput(workDir: File, prefix: String = "ffmpeg-src-", suffix: String = ".bin"): File {
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            throw java.io.IOException("无法创建临时目录：${workDir.absolutePath}")
        }
        return File.createTempFile(prefix, suffix, workDir)
    }
}
