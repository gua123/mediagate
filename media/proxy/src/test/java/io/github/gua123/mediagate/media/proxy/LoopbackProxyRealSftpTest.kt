package io.github.gua123.mediagate.media.proxy

import io.github.gua123.mediagate.data.storage.api.TimeoutStorageBackend
import io.github.gua123.mediagate.data.storage.sftp.InMemoryKnownHostsStore
import io.github.gua123.mediagate.data.storage.sftp.SftpConfig
import io.github.gua123.mediagate.data.storage.sftp.SftpHostKeyPolicy
import io.github.gua123.mediagate.data.storage.sftp.SftpStorageBackend
import io.github.gua123.mediagate.data.storage.sftp.TofuHostKeyVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/**
 * L4+L2 集成：真 SFTP 后端 + 真回环代理，验证**大偏移 Range**（2026-11-18）。
 *
 * 为什么需要这个：L4 用假后端测过 Range 语义全绿，但真机上 VLC 请求
 * `bytes=1072357448-`（moov 位置）却仍报 `moov atom not found`。
 * 假后端测不出真实 offset 传递链路（代理 → TimeoutStorageBackend → SftpRangeStream）。
 */
class LoopbackProxyRealSftpTest {

    private var proxy: LoopbackHttpProxy? = null
    private var backend: TimeoutStorageBackend? = null
    private var path = ""
    private var moovOffset = 0L

    @Before
    fun setUp() {
        val candidates = listOf(
            ".toolchain/real-server.properties",
            "../.toolchain/real-server.properties",
            "../../.toolchain/real-server.properties",
            "/root/project/mediagate/.toolchain/real-server.properties",
        ).map { File(it) }
        val f = candidates.firstOrNull { it.isFile }
        Assume.assumeTrue("跳过：没有 real-server.properties", f != null)
        val p = Properties().apply { f!!.inputStream().use { load(it) } }
        val sftp = SftpStorageBackend(
            SftpConfig(
                host = p.getProperty("sftp.lan.host", "192.168.1.10"),
                port = p.getProperty("sftp.lan.port", "2222").toInt(),
                username = p.getProperty("user"),
                password = p.getProperty("password"),
                basePath = "/",
            ),
            TofuHostKeyVerifier(InMemoryKnownHostsStore(), SftpHostKeyPolicy.TOFU),
        )
        backend = TimeoutStorageBackend(sftp)
        path = p.getProperty("repro.path", "share/videos/demo-sample.mp4")
        moovOffset = p.getProperty("repro.moovOffset", "1072357448").toLong()
        proxy = LoopbackHttpProxy(backend!!)
    }

    @After
    fun tearDown() {
        runCatching { proxy?.close() }
        runCatching { backend?.close() }
    }

    private fun get(range: String): HttpURLConnection {
        val url = URL(proxy!!.playUrlSafe(backend!!.id, path))
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 20000
            setRequestProperty("Range", range)
        }
    }

    @Test
    fun 大偏移Range返回moov字节() {
        val c = get("bytes=" + moovOffset + "-")
        assertEquals(206, c.responseCode)
        val head = c.inputStream.use { input ->
            val b = ByteArray(32)
            var total = 0
            while (total < 32) {
                val n = input.read(b, total, 32 - total)
                if (n < 0) break
                total += n
            }
            b.copyOf(total)
        }
        // moov box: 前 4 字节是 size，紧接着是 ASCII "moov"
        val ascii = String(head, 4, 4, Charsets.US_ASCII)
        println("[REAL] offset=" + moovOffset + " 读到 " + head.size + " 字节, box=" + ascii)
        assertEquals("偏移处的 box 应为 moov", "moov", ascii)
    }

    @Test
    fun 头部Range返回ftyp() {
        val c = get("bytes=0-")
        assertEquals(206, c.responseCode)
        val head = c.inputStream.use { input ->
            val b = ByteArray(16)
            var total = 0
            while (total < 16) {
                val n = input.read(b, total, 16 - total)
                if (n < 0) break
                total += n
            }
            b.copyOf(total)
        }
        val ascii = String(head, 4, 4, Charsets.US_ASCII)
        println("[REAL] offset=0 读到 box=" + ascii)
        assertEquals("文件头第一个 box 应为 ftyp", "ftyp", ascii)
    }
}
