package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.media.engine.EngineCapabilities
import io.github.gua123.mediagate.media.engine.ResizeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力矩阵的护栏：**不许把没做的能力说成有**（过渡期界面就靠它决定给不给档位、怎么提示）。
 * 等 M5 真的做出倍速/硬解，再回来把这里改掉——那时改动是"有意识"的。
 */
class NativeEngineCapabilitiesTest {

    @Test
    fun 自研引擎要如实声明不支持倍速() {
        assertFalse("倍速还没做（native 没有时间伸缩），别声明成支持", NativeEngineCapabilities.VALUE.speed)
    }

    @Test
    fun 自研引擎要如实声明只有软解一条路() {
        assertFalse("MediaCodec 硬解在 M5，现在只有 FFmpeg 软解", NativeEngineCapabilities.VALUE.decoderModes)
    }

    @Test
    fun 缩放四档都要支持_四个档位都在_native_的送屏里算() {
        assertEquals(ResizeMode.entries.toSet(), NativeEngineCapabilities.VALUE.resizeModes)
        for (mode in ResizeMode.entries) {
            assertTrue("应该支持 " + mode.label, NativeEngineCapabilities.VALUE.supports(mode))
        }
    }

    @Test
    fun 音量可以超过_100_走软件增益() {
        assertTrue(NativeEngineCapabilities.VALUE.volumeBoost)
    }

    @Test
    fun 旧内核的默认口径必须还是全支持_引入能力矩阵不许改变旧行为() {
        val all = EngineCapabilities.ALL
        assertTrue(all.speed)
        assertTrue(all.decoderModes)
        assertTrue(all.volumeBoost)
        assertEquals(ResizeMode.entries.toSet(), all.resizeModes)
        // 两个旧内核都不覆盖 capabilities ⇒ 默认值就是它们的行为
        assertEquals(EngineCapabilities(), all)
    }
}
