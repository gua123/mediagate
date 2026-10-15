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
    fun 探针必须注册在独立进程并且真的跑自检() {
        // 探针的全部价值就在"独立进程"这四个字上：少写 android:process 就变成在主进程里
        // 跑最危险的那几步（FFmpeg/AAudio 初始化），崩了直接带走 App —— 而单测与构建都不会报错。
        var dir: File? = File("").absoluteFile
        var manifest: File? = null
        while (dir != null && manifest == null) {
            val candidate = File(dir, "app/src/main/AndroidManifest.xml")
            if (candidate.exists()) manifest = candidate
            dir = dir.parentFile
        }
        val text = manifest?.readText() ?: error("找不到 app 的 AndroidManifest.xml")
        assertTrue(
            "探针服务要在清单里声明为独立进程 :engineprobe",
            text.contains("NativeEngineProbeService") && text.contains(":engineprobe"),
        )
        val service = find("src/main/java/io/github/gua123/mediagate/media/enginenative/NativeEngineProbeService.kt").readText()
        assertTrue("探针必须真的调 NativeEngine.selfCheck()", service.contains("NativeEngine.selfCheck()"))
        assertTrue("探针要先写 start（跨进程证据）", service.contains("FILE_START"))
        assertTrue("探针要写 done 作为末尾标记", service.contains("FILE_DONE"))
    }

    @Test
    fun 数据层桥的_R8_keep_规则必须在() {
        // 这条护栏救过一次真机预览包：R8 把 NativeMediaSource 与 read/length/lastError 改名之后，
        // release 包里的 GetMethodID 会返回 null（"数据来源接口不匹配"），而 debug 与 JVM 单测都测不出来。
        val rules = find("consumer-rules.pro")
        assertTrue("引擎模块必须带 consumer-rules.pro（R8 keep 规则）", rules.exists())
        val text = rules.readText()
        assertTrue("要保留 NativeMediaSource 接口本身", text.contains("io.github.gua123.mediagate.media.enginenative.NativeMediaSource { *; }"))
        assertTrue("要保留它的实现类（RandomAccessMediaSource 等）", text.contains("implements io.github.gua123.mediagate.media.enginenative.NativeMediaSource"))
        val build = find("build.gradle.kts").readText()
        assertTrue(
            "build.gradle.kts 要声明 consumerProguardFiles，否则规则不会进 release 构建",
            build.contains("consumerProguardFiles(\"consumer-rules.pro\")"),
        )
    }

    @Test
    fun 引擎能力只能从契约层的能力表取_不许在本模块另写一份() {
        // 两份能力声明必然会在某次改动后不一致（界面按 A 提示、引擎按 B 行事）
        val source = find("src/main/java/io/github/gua123/mediagate/media/enginenative/NativeEnginePlayer.kt").readText()
        assertTrue(
            "NativeEnginePlayer 的 capabilities 应当来自 EngineCatalog.capabilitiesOf(kind)",
            source.contains("EngineCatalog.capabilitiesOf(kind)"),
        )
        assertTrue("本模块不该再有第二份能力声明", !File(find("src/main/cpp").parentFile, "java/io/github/gua123/mediagate/media/enginenative/NativeEngineCapabilities.kt").exists())
    }

    @Test
    fun 缩放档位的序号必须与_native_的_ScaleMode_一一对应() {
        // Kotlin 侧传的是 ResizeMode.ordinal（NativeEnginePlayer.setResizeMode → setScaleMode），
        // native 侧按 av_sync.h 的 ScaleMode 数值分支。两边顺序一旦错位，
        // 表现是"选了裁切填充却按适应屏幕画"——真机上很难一眼看出来，所以这里逐项比。
        var dir: File? = File("").absoluteFile
        var engineFile: File? = null
        while (dir != null && engineFile == null) {
            val candidate = File(dir, "media/engine/src/main/java/io/github/gua123/mediagate/media/engine/ResizeMode.kt")
            if (candidate.exists()) engineFile = candidate
            dir = dir.parentFile
        }
        val resize = engineFile?.readText() ?: error("找不到 media/engine 的 ResizeMode.kt")
        val native = find("src/main/cpp/av_sync.h").readText()

        val kotlinOrder = Regex("""(?m)^\s{4}([A-Z_]+)\(""").findAll(resize).map { it.groupValues[1] }.toList()
        val scaleBlock = native.substringAfter("enum class ScaleMode").substringBefore("};")
        val nativePairs = Regex("""([A-Z_]+)\s*=\s*(\d+)""")
            .findAll(scaleBlock)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toList()

        assertEquals("Kotlin 的 ResizeMode 顺序解析失败", 4, kotlinOrder.size)
        assertEquals("native 的 ScaleMode 顺序解析失败", 4, nativePairs.size)
        assertEquals("两边名字与顺序都要一致（传的是 ordinal）", kotlinOrder, nativePairs.map { it.first })
        // 只比名字不够：把 CROP 与 STRETCH 的数值对调也照样能过 —— 必须逐个核数值
        nativePairs.forEachIndexed { index, pair ->
            assertEquals(pair.first + " 的数值必须等于它在 Kotlin 里的 ordinal", index, pair.second)
        }
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
