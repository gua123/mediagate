package io.github.gua123.mediagate.data.storage.sftp

import io.github.gua123.mediagate.data.storage.api.TimeoutStorageBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 在开发机（JVM）上复现「SFTP 播放大文件读不出数据」（2026-11-18）。
 * 真实链路：TimeoutStorageBackend -> SftpStorageBackend，直连真实服务器，只读。
 */
class SftpPlaybackReproTest {

    @Test
    fun reproduceLargeFileRead() = runBlocking {
        val candidates = listOf(
            ".toolchain/real-server.properties",
            "../.toolchain/real-server.properties",
            "../../.toolchain/real-server.properties",
            "/root/project/mediagate/.toolchain/real-server.properties",
        ).map { File(it) }
        val f = candidates.firstOrNull { it.isFile } ?: candidates.first()
        Assume.assumeTrue("跳过：没有 real-server.properties", f.isFile)
        val p = Properties().apply { f.inputStream().use { load(it) } }
        val host = p.getProperty("sftp.lan.host", "10.1.2.3")
        val port = p.getProperty("sftp.lan.port", "2222").toInt()
        val user = p.getProperty("user")
        val pass = p.getProperty("password")

        val sftp = SftpStorageBackend(
            SftpConfig(host = host, port = port, username = user, password = pass, basePath = "/"),
            TofuHostKeyVerifier(InMemoryKnownHostsStore(), SftpHostKeyPolicy.TOFU),
        )
        val backend = TimeoutStorageBackend(sftp)
        val path = "share/videos/demo-sample.mp4"
        try {
            val t0 = System.currentTimeMillis()
            val stream = withTimeout(25_000) { backend.openRead(path, 0L, 4L * 1024 * 1024) }
            println("[REPRO] openRead 返回 " + (System.currentTimeMillis() - t0) + " ms")
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (total < 4L * 1024 * 1024) {
                val n = stream.read(buf, 0, buf.size)
                if (n <= 0) break
                total += n
            }
            println("[REPRO] 读到 " + total + " 字节，总耗时 " + (System.currentTimeMillis() - t0) + " ms")
            stream.close()
        } catch (e: Throwable) {
            println("[REPRO] 失败：" + e.javaClass.simpleName + " " + e.message)
            throw e
        } finally {
            runCatching { backend.close() }
        }
    }
}
