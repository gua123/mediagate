package io.github.gua123.mediagate.media.tsext

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/**
 * [TsIndex] 的紧凑二进制编解码（R3/R4）。
 *
 * **为什么是二进制而不是 JSON**：
 * - 2 GB 的 TS 关键帧点可达数千到数万个，每个点 16 字节（时间 8 + 偏移 8）；
 *   同内容的 JSON 大约 4~6 倍体积，且解析要建大量字符串/对象，与「命中即秒回」的目标相悖；
 * - 二进制带固定 magic + version + CRC32，读到损坏/半截文件能明确判废，而不用靠 JSON 解析异常兜底。
 *
 * 布局（大端）：
 * ```
 * 0   magic  "MGTI"
 * 4   version u8  (= 1)
 * 5   flags   u8  (bit0 = complete)
 * 6   codec   u8  (TsVideoCodec.ordinal)
 * 7   videoPid i32 (-1 = 未知)
 * 11  durationMs i64 (-1 = null)
 * 19  scannedBytes i64
 * 27  pointCount i32
 * 31  points: pointCount × (timeMs i64, byteOffset i64)
 * 末 4 字节: CRC32(前面全部字节)
 * ```
 *
 * 纯 Kotlin，JVM 可测。
 */
object TsIndexCodec {

    /** "MGTI" = Mediagate TS Index。 */
    const val MAGIC: Int = 0x4D475449

    const val VERSION: Int = 1

    /** 头部字节数（不含点数组与末尾 CRC）。 */
    const val HEADER_BYTES: Int = 31

    /** 每个关键帧点 16 字节。 */
    const val POINT_BYTES: Int = 16

    /** 尾部 CRC32 4 字节。 */
    const val FOOTER_BYTES: Int = 4

    /** 解码时接受的最大点数（防御损坏文件导致的内存放大）。 */
    const val MAX_POINTS: Int = 5_000_000

    private const val FLAG_COMPLETE = 0x01

    /** 编码后的字节数。 */
    fun sizeOf(pointCount: Int): Int = HEADER_BYTES + pointCount * POINT_BYTES + FOOTER_BYTES

    /** 头部是否匹配（用于快速判断一个文件是不是索引）。 */
    fun looksLikeIndex(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_BYTES + FOOTER_BYTES && readInt(bytes, 0) == MAGIC && (bytes[4].toInt() and 0xFF) == VERSION

    /** 编码成紧凑二进制。 */
    fun encode(index: TsIndex): ByteArray {
        val body = ByteArrayOutputStream(sizeOf(index.points.size))
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(if (index.complete) FLAG_COMPLETE else 0)
            out.writeByte(index.videoCodec.ordinal)
            out.writeInt(index.videoPid)
            out.writeLong(index.durationMs ?: -1L)
            out.writeLong(index.scannedBytes)
            out.writeInt(index.points.size)
            for (point in index.points) {
                out.writeLong(point.timeMs)
                out.writeLong(point.byteOffset)
            }
        }
        val payload = body.toByteArray()
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val result = ByteArray(payload.size + FOOTER_BYTES)
        System.arraycopy(payload, 0, result, 0, payload.size)
        writeInt(result, payload.size, crc)
        return result
    }

    /**
     * 解码；任何异常（magic/版本不符、长度不够、CRC 不符、点数越界、内容破坏索引不变量）
     * 都返回 null——调用方按「缓存失效」处理，绝不崩。
     */
    fun decode(bytes: ByteArray): TsIndex? {
        if (!looksLikeIndex(bytes)) return null
        val crcOffset = bytes.size - FOOTER_BYTES
        val expectedCrc = readInt(bytes, crcOffset)
        val crc = CRC32().apply { update(bytes, 0, crcOffset) }.value.toInt()
        if (crc != expectedCrc) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes, 0, crcOffset)).use { input ->
                input.readInt() // magic
                input.readByte() // version
                val flags = input.readByte().toInt() and 0xFF
                val codecOrdinal = input.readByte().toInt() and 0xFF
                val videoPid = input.readInt()
                val duration = input.readLong()
                val scannedBytes = input.readLong()
                val pointCount = input.readInt()
                if (pointCount < 0 || pointCount > MAX_POINTS) return null
                if (crcOffset < HEADER_BYTES + pointCount * POINT_BYTES) return null
                val points = ArrayList<KeyframePoint>(pointCount)
                for (ignored in 0 until pointCount) {
                    val timeMs = input.readLong()
                    val byteOffset = input.readLong()
                    points += KeyframePoint(timeMs, byteOffset)
                }
                TsIndex(
                    points = points,
                    videoPid = videoPid,
                    videoCodec = TsVideoCodec.entries.getOrElse(codecOrdinal) { TsVideoCodec.OTHER },
                    durationMs = if (duration < 0) null else duration,
                    scannedBytes = scannedBytes,
                    complete = flags and FLAG_COMPLETE != 0,
                )
            }
        } catch (e: RuntimeException) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}
