package io.github.gua123.mediagate.media.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PCM 工具的 JVM 单测（**M7-B / R14 / R11**）：s16 与 float 互转、字节偏移、窗口随机读。
 */
class AsrPcmTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun shortsAndFloats_roundTripWithinOneSampleStep() {
        val shorts = shortArrayOf(0, 1_000, -1_000, Short.MAX_VALUE, Short.MIN_VALUE)
        val floats = AsrPcm.floatsFromShorts(shorts)
        assertEquals(5, floats.size)
        assertEquals(0f, floats[0], 0.0001f)
        assertEquals(1_000f / 32_768f, floats[1], 0.0001f)

        val back = AsrPcm.shortsFromFloats(floats)
        assertEquals(shorts.toList(), back.toList())
    }

    @Test
    fun shortsFromFloats_clampsOutOfRangeValues() {
        val shorts = AsrPcm.shortsFromFloats(floatArrayOf(2f, -2f, Float.NaN))
        assertEquals(Short.MAX_VALUE, shorts[0])
        assertEquals(Short.MIN_VALUE, shorts[1])
        assertEquals(0, shorts[2].toInt())
    }

    @Test
    fun s16leBytes_roundTrip() {
        val floats = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        val bytes = AsrPcm.s16leFromFloats(floats)
        assertEquals(floats.size * 2, bytes.size)
        val back = AsrPcm.floatsFromS16le(bytes)
        assertEquals(floats.size, back.size)
        for (index in floats.indices) {
            assertEquals(floats[index], back[index], 0.001f)
        }
    }

    @Test
    fun floatsFromS16le_honoursOffsetAndOddLength() {
        val bytes = AsrPcm.s16leFromFloats(floatArrayOf(1f, -1f, 1f))
        val middle = AsrPcm.floatsFromS16le(bytes, offset = 2, length = 2)
        assertEquals(1, middle.size)
        assertEquals(-1f, middle[0], 0.001f)
        // 奇数长度丢掉最后一个字节
        assertEquals(1, AsrPcm.floatsFromS16le(bytes, offset = 0, length = 3).size)
        assertEquals(0, AsrPcm.floatsFromS16le(bytes, offset = 99).size)
    }

    @Test
    fun sampleConversions_areConsistent() {
        assertEquals(16_000L, AsrPcm.sampleCountOfMs(1_000L))
        assertEquals(32_000L, AsrPcm.byteCountOfSamples(16_000L))
        assertEquals(16_000L, AsrPcm.sampleCountOfBytes(32_000L))
        assertEquals(0L, AsrPcm.sampleCountOfMs(0L))
        assertEquals(0L, AsrPcm.sampleCountOfBytes(-5L))
        assertEquals(240_000L, AsrPcm.sampleOffsetOfMs(15_000L))
    }

    @Test
    fun readWindow_readsExactlyTheWindowSlice() {
        val total = FloatArray(AsrPcm.SAMPLE_RATE * 2) { index -> if (index % 2 == 0) 0.25f else -0.25f }
        val file = folder.newFile("audio.pcm").apply { writeBytes(AsrPcm.s16leFromFloats(total)) }

        val window = AsrWindow(
            index = 1,
            startMs = 500L,
            endMs = 1_000L,
            sampleOffset = AsrPcm.sampleOffsetOfMs(500L),
            sampleCount = AsrPcm.sampleCountOfMs(500L).toInt(),
        )
        val samples = AsrPcm.readWindow(file, window)
        assertEquals(8_000, samples.size)
        assertEquals(0.25f, samples[0], 0.001f)
        assertEquals(-0.25f, samples[1], 0.001f)
    }

    @Test
    fun readWindow_returnsEmptyForMissingOrTruncatedFile() {
        val missing = java.io.File(folder.root, "nope.pcm")
        val window = AsrWindow(0, 0L, 1_000L, 0L, 16_000)
        assertEquals(0, AsrPcm.readWindow(missing, window).size)

        val tiny = folder.newFile("tiny.pcm").apply { writeBytes(ByteArray(2)) }
        assertTrue(AsrPcm.readWindow(tiny, AsrWindow(9, 9_000L, 10_000L, 144_000L, 16_000)).isEmpty())
    }
}
