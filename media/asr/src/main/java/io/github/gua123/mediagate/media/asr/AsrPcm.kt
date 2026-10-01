package io.github.gua123.mediagate.media.asr

import java.io.File
import java.io.RandomAccessFile

/**
 * 16 kHz 单声道 PCM 的纯函数工具（**M7-B / R14 / R11**）。
 *
 * 数据流：FFmpeg 把音轨解成 pcm_s16le（[io.github.gua123.mediagate.media.ffmpeg.FfmpegCommand.asrPcmDecode]
 * 的 16 kHz / 单声道口径）→ 落到临时 .pcm 文件 → 按 30 s 窗口（5 s 重叠）随机读成 float 交给 whisper。
 *
 * **为什么落文件而不是管道**：whisper 要的是「窗口」而不是「整条流」——30 s 窗口 + 5 s 重叠意味着
 * 同一段音频会被读两次，偏移量还能按「采样序号 × 2 字节」直接算（见 [AsrWindow.sampleOffset]）。
 * 管道形态（FfmpegCommand.asrPcmDecode 的 stdout）需要 ffmpeg-kit 的命名管道，且没法回头重读；
 * 1 小时 16 kHz 单声道 PCM 也才 115 MB，放 cacheDir 完全可接受。
 *
 * 本对象全部是纯函数（[readWindow] 只读一个已经存在的本地文件），可以在 JVM 单测里穷举。
 */
object AsrPcm {

    /** whisper.cpp 要求的采样率（plan 4.7 B）。 */
    const val SAMPLE_RATE = 16_000

    /** pcm_s16le：每个采样 2 字节。 */
    const val BYTES_PER_SAMPLE = 2

    /** s16 满量程（-32768..32767 → -1..1 的除数）。 */
    const val S16_SCALE = 32768f

    /** 字节数 → 采样点数（向下取整：宁可少半个采样也不要越界）。 */
    fun sampleCountOfBytes(byteCount: Long): Long = if (byteCount <= 0L) 0L else byteCount / BYTES_PER_SAMPLE

    /** 采样点数 → 字节数。 */
    fun byteCountOfSamples(sampleCount: Long): Long = if (sampleCount <= 0L) 0L else sampleCount * BYTES_PER_SAMPLE

    /** 毫秒 → 采样点数（16 kHz 下 1 ms = 16 个采样）。 */
    fun sampleCountOfMs(durationMs: Long, sampleRate: Int = SAMPLE_RATE): Long =
        if (durationMs <= 0L) 0L else durationMs * sampleRate / 1000L

    /** 毫秒 → 采样偏移。 */
    fun sampleOffsetOfMs(positionMs: Long, sampleRate: Int = SAMPLE_RATE): Long =
        if (positionMs <= 0L) 0L else positionMs * sampleRate / 1000L

    /** s16 采样 → [-1, 1] 的 float（**纯函数**）。 */
    fun floatsFromShorts(shorts: ShortArray): FloatArray =
        FloatArray(shorts.size) { index -> shorts[index] / S16_SCALE }

    /** [-1, 1] 的 float → s16 采样（**纯函数**，超范围夹紧，不环绕）。 */
    fun shortsFromFloats(floats: FloatArray): ShortArray = ShortArray(floats.size) { index ->
        val value = floats[index]
        val scaled = if (value.isNaN()) 0f else value * S16_SCALE
        when {
            scaled >= 32767f -> Short.MAX_VALUE
            scaled <= -32768f -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    /**
     * pcm_s16le 小端字节 → [-1, 1] 的 float（**纯函数**）。
     *
     * @param offset 起始字节偏移。
     * @param length 读取的字节数（自动夹到数组范围内；奇数长度丢掉最后一个字节）。
     */
    fun floatsFromS16le(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): FloatArray {
        val start = offset.coerceIn(0, bytes.size)
        val available = bytes.size - start
        val usable = minOf(length.coerceAtLeast(0), available) / BYTES_PER_SAMPLE
        if (usable <= 0) return FloatArray(0)
        return FloatArray(usable) { index ->
            val low = bytes[start + index * 2].toInt() and 0xFF
            val high = bytes[start + index * 2 + 1].toInt()
            val value = (high shl 8) or low
            value.toShort() / S16_SCALE
        }
    }

    /** [-1, 1] 的 float → pcm_s16le 小端字节（**纯函数**）。 */
    fun s16leFromFloats(floats: FloatArray): ByteArray {
        val shorts = shortsFromFloats(floats)
        val out = ByteArray(shorts.size * BYTES_PER_SAMPLE)
        shorts.forEachIndexed { index, value ->
            val raw = value.toInt()
            out[index * 2] = (raw and 0xFF).toByte()
            out[index * 2 + 1] = ((raw shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 读出一个窗口的采样（**唯一有 IO 的函数**，读的是本地文件，JVM 单测可用临时文件覆盖）。
     *
     * 读到的采样数可能少于 [AsrWindow.sampleCount]（文件被截断 / 最后一窗落在一半），
     * 这是正常的：返回多少算多少，由调用方决定要不要跳过（whisper 需要至少 100 ms）。
     */
    fun readWindow(pcmFile: File, window: AsrWindow): FloatArray {
        if (!pcmFile.isFile || pcmFile.length() <= 0L) return FloatArray(0)
        val byteOffset = byteCountOfSamples(window.sampleOffset)
        if (byteOffset >= pcmFile.length()) return FloatArray(0)
        val wanted = byteCountOfSamples(window.sampleCount.toLong()).coerceAtMost(pcmFile.length() - byteOffset)
        if (wanted < BYTES_PER_SAMPLE) return FloatArray(0)
        RandomAccessFile(pcmFile, "r").use { file ->
            file.seek(byteOffset)
            val buffer = ByteArray(wanted.toInt())
            file.readFully(buffer)
            return floatsFromS16le(buffer)
        }
    }
}
