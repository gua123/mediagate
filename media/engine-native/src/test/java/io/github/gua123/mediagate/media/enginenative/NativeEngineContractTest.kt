package io.github.gua123.mediagate.media.enginenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JNI 契约的源码级护栏：**Kotlin 的 System.loadLibrary("X") 必须等于 CMake 的 add_library(X ...)**。
 *
 * 这类不一致编译期不报错、JVM 单测也不报错，只在真机上抛 UnsatisfiedLinkError ——
 * 所以用"扫文件"的方式钉住（本项目已有同类护栏的先例）。
 */
class NativeEngineContractTest {

    private fun find(relative: String): File {
        val candidates = listOf(File(relative), File("media/engine-native/$relative"), File("../../media/engine-native/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative（目录结构变了要同步改这条护栏）")
    }

    @Test
    fun 库名与_CMake_target_必须一致() {
        val cmake = find("src/main/cpp/CMakeLists.txt").readText()
        assertTrue(
            "CMake 里找不到 add_library(${NativeEngine.LIBRARY_NAME} ...)，库名对不上会 UnsatisfiedLinkError",
            cmake.contains("add_library(${NativeEngine.LIBRARY_NAME}"),
        )
    }

    @Test
    fun JNI_符号名前缀要与_kotlin_包名一致() {
        val jni = find("src/main/cpp/engine_jni.cpp").readText()
        // Kotlin 包 io.github.gua123.mediagate.media.enginenative + 类 NativeEngine
        val prefix = "Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_"
        assertTrue("JNI 导出符号前缀应为 $prefix", jni.contains(prefix))
    }

    @Test
    fun 导出的每个_native_方法都要在_Kotlin_侧声明() {
        val jni = find("src/main/cpp/engine_jni.cpp").readText()
        val kotlin = find("src/main/java/io/github/gua123/mediagate/media/enginenative/NativeEngine.kt").readText()
        val exported = Regex("""Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_(\w+)""")
            .findAll(jni)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("JNI 里一个导出函数都没有？", exported.isNotEmpty())
        for (name in exported) {
            assertTrue(
                "JNI 导出了 $name，但 Kotlin 里没有对应的 external fun $name()",
                kotlin.contains("external fun $name(") ,
            )
        }
        assertEquals("导出的数量应与 external 声明一致", exported.size, Regex("""external fun \w+\(""").findAll(kotlin).count())
    }

    @Test
    fun 必须做_16KB_页对齐() {
        val cmake = find("src/main/cpp/CMakeLists.txt").readText()
        assertTrue("要显式声明 16 KB 页对齐（Android 15+ 要求）", cmake.contains("max-page-size=16384"))
    }

    @Test
    fun 音频输出要真的参与编译并链接_AAudio() {
        val cmake = find("src/main/cpp/CMakeLists.txt").readText()
        assertTrue(
            "audio_output.cpp 没进 add_library ⇒ 音频那段代码根本没编进 .so",
            cmake.contains("audio_output.cpp"),
        )
        assertTrue("要链接 aaudio，否则 AAudio 的符号在真机上找不到", cmake.contains("find_library(aaudio-lib aaudio)"))
        assertTrue("aaudio 要进 target_link_libraries", cmake.contains("\${aaudio-lib}"))
    }

    @Test
    fun 数据层调用的_JNI_签名要与_Kotlin_接口逐字一致() {
        // GetMethodID 的名字或描述符写错一个字符，编译期与 JVM 单测都不会报，
        // 只在真机上表现成「数据来源接口不匹配」——所以这里逐条钉住。
        val native = find("src/main/cpp/avio_source.cpp").readText()
        val kotlin = find("src/main/java/io/github/gua123/mediagate/media/enginenative/NativeMediaSource.kt").readText()
        val expected = mapOf(
            "read" to "(JLjava/nio/ByteBuffer;I)I",
            "length" to "()J",
            "lastError" to "()Ljava/lang/String;",
        )
        for ((name, descriptor) in expected) {
            assertTrue(
                "avio_source.cpp 里要按 \"$name\" / \"$descriptor\" 取方法（改名或改类型都会在真机上失败）",
                native.contains("\"$name\", \"$descriptor\""),
            )
            assertTrue("Kotlin 的 NativeMediaSource 里没有 fun $name(...)", kotlin.contains("fun $name("))
        }
    }

    @Test
    fun 数据层播放链路要真的参与编译并且能拿到_JavaVM() {
        val cmake = find("src/main/cpp/CMakeLists.txt").readText()
        assertTrue("avio_source.cpp 没进 add_library ⇒ 数据层那条路根本没编进去", cmake.contains("avio_source.cpp"))
        val jni = find("src/main/cpp/engine_jni.cpp").readText()
        // 播放线程是 native 自己建的，JVM 不认识它：没有 JavaVM 就没法 AttachCurrentThread，
        // 于是远端/SAF 播放会在读第一个字节时失败。
        assertTrue("engine_jni.cpp 要提供 JNI_OnLoad", jni.contains("JNI_OnLoad"))
        assertTrue("JNI_OnLoad 里要把 JavaVM 交给引擎", jni.contains("setJavaVm(vm)"))
    }

    @Test
    fun 音画同步的纯逻辑要有能跑的开发机单测() {
        // 这一层错了（时间换算 / 同步判据 / 环形缓冲）在真机上就是"音画错位"这种最难查的问题，
        // 所以它必须有一份**不需要设备**的单测，并且每次 gradlew test 都会跑（见 NativeHostLogicTest）。
        val header = find("src/main/cpp/av_sync.h")
        assertTrue("av_sync.h 不见了（纯逻辑别又塞回 native_player.cpp）", header.exists())
        val hostTest = find("src/test/cpp/av_sync_host_test.cpp")
        assertTrue("宿主单测源文件不见了", hostTest.exists())
        val text = hostTest.readText()
        assertTrue("宿主单测要覆盖 PCM 环形缓冲", text.contains("PcmRing"))
        assertTrue("宿主单测要覆盖音画同步判据", text.contains("decideVideoSync"))
        assertTrue("宿主单测要覆盖音频时钟", text.contains("audioClockMs"))
        assertTrue("宿主单测要覆盖自定义 AVIO 的 seek 换算", text.contains("resolveSeekPosition"))
    }
}
