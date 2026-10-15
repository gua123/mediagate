package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力表的护栏（**不做静默降级**的地基）：界面在切换之前就要知道"目标内核少什么"，
 * 所以这份声明必须如实 —— 把没做的能力说成有，用户切过去只会觉得"点了没反应"。
 *
 * 等 M5 真的做出倍速/硬解，再回来改这里；那时改动是"有意识"的。
 */
class EngineCatalogTest {

    @Test
    fun 自研引擎要如实声明不支持倍速与解码档位() {
        val caps = EngineCatalog.capabilitiesOf(EngineKind.NATIVE)
        assertFalse("倍速还没做（native 没有时间伸缩），别声明成支持", caps.speed)
        assertFalse("MediaCodec 硬解在 M5，现在只有 FFmpeg 软解", caps.decoderModes)
    }

    @Test
    fun 自研引擎支持的档位要如实声明支持() {
        val caps = EngineCatalog.capabilitiesOf(EngineKind.NATIVE)
        assertEquals("缩放四档都在 native 的送屏里算", ResizeMode.entries.toSet(), caps.resizeModes)
        assertTrue("音量 >100% 走软件增益", caps.volumeBoost)
        for (mode in ResizeMode.entries) {
            assertTrue("应该支持 " + mode.label, caps.supports(mode))
        }
    }

    @Test
    fun 过渡期的两个旧内核能力齐平_不能因为引入能力表改变旧行为() {
        for (kind in listOf(EngineKind.MEDIA3, EngineKind.VLC)) {
            val caps = EngineCatalog.capabilitiesOf(kind)
            assertTrue(kind.label + " 应当支持倍速", caps.speed)
            assertTrue(kind.label + " 应当支持解码档位", caps.decoderModes)
            assertTrue(kind.label + " 应当支持音量增强", caps.volumeBoost)
            assertEquals(kind.label + " 应当支持全部缩放档位", ResizeMode.entries.toSet(), caps.resizeModes)
        }
        assertEquals(EngineCapabilities.ALL, EngineCatalog.capabilitiesOf(EngineKind.MEDIA3))
    }

    @Test
    fun 每个内核都有能力声明_没有漏网的档位() {
        for (kind in EngineKind.entries) {
            val caps = EngineCatalog.capabilitiesOf(kind)
            assertTrue("能力表里 " + kind.label + " 的缩放档位不该是空的", caps.resizeModes.isNotEmpty())
        }
    }

    @Test
    fun 缺什么就要能说成人话() {
        val native = EngineCatalog.capabilitiesOf(EngineKind.NATIVE)
        assertFalse(native.speed)
        assertFalse(native.decoderModes)
        // 能力的"缺项"是界面提示文案的来源；这里只钉住判断本身（文案在 feature 层的单测里）
        assertNull("能力齐平时不该有缺项", missingOf(EngineCatalog.capabilitiesOf(EngineKind.MEDIA3)))
        assertEquals(listOf("倍速", "硬解档位"), missingOf(native))
    }

    private fun missingOf(caps: EngineCapabilities): List<String>? = buildList {
        if (!caps.speed) add("倍速")
        if (!caps.decoderModes) add("硬解档位")
    }.ifEmpty { null }
}
