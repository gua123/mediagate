package io.github.gua123.mediagate.media.enginenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 把 native 的**纯逻辑单测**接进日常测试：每次 gradlew test 都会在开发机上
 * 用 g++ 编译 av_sync.h + av_sync_host_test.cpp 并跑一遍。
 *
 * 为什么值得这么做：音画同步、时间换算、PCM 环形缓冲这三样在真机上出错的表现
 * 是"声音慢半拍""拖着拖着音画分离"——没有设备就查不动。抽成纯逻辑之后，
 * 它们在开发机上就是普通单测（含真并发的生产/消费用例）。
 */
class NativeHostLogicTest {

    private fun findUpward(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("从当前目录向上找不到 $relative（目录结构变了要同步改这条护栏）")
    }

    @Test
    fun 宿主单测脚本要存在并且编译的是纯逻辑那一份源文件() {
        val script = findUpward("scripts/test-engine-native-host.sh")
        val text = script.readText()
        assertTrue("脚本要编译 av_sync_host_test.cpp", text.contains("av_sync_host_test.cpp"))
        assertTrue("脚本要把 cpp 目录加进 include 路径", text.contains("src/main/cpp"))
        assertTrue("编译失败必须让脚本非零退出（否则单测形同虚设）", text.contains("set -euo pipefail"))
    }

    @Test
    fun 在开发机上直接编译并运行纯逻辑单测() {
        val compiler = listOf("/usr/bin/g++", "/usr/bin/clang++", "/usr/local/bin/g++")
            .firstOrNull { File(it).canExecute() }
        assumeTrue("开发机没有 C++ 编译器，跳过运行（脚本存在性另有断言）", compiler != null)

        val script = findUpward("scripts/test-engine-native-host.sh")
        val process = ProcessBuilder("bash", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("宿主单测超时（180 秒）：\n$output", finished)
        assertEquals("宿主单测没通过：\n$output", 0, process.exitValue())
        assertTrue("输出里应当有通过标记：\n$output", output.contains("全部通过"))
    }
}
