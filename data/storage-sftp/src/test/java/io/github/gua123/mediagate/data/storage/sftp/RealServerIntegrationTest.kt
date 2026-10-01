package io.github.gua123.mediagate.data.storage.sftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.io.File
import java.util.Properties

/**
 * **真网集成测试（opt-in）**：对用户提供的局域网 SFTP 服务器跑一遍（**R2/R4/R8**）。
 *
 * 凭据来自 `.toolchain/real-server.properties`（java.util.Properties，整个 `.toolchain/` 已被 gitignore）：
 * `sftp.lan.host` / `sftp.lan.port` / `user` / `password`。**文件不存在就整类跳过**
 * （[Assume.assumeTrue]），所以 CI / 别的机器上不会因为缺少私有凭据而红。
 *
 * 覆盖链路：probe 三段计时 → list("/") → 挑一个小文件 stat →
 * `openRead(path, 0, 100)` 与 `openRead(path, 200, 100)`（各 100 字节且内容不同）→
 * 全量读取与分段拼接逐字节一致（R4 拖拽 seek 的正确性基础）。
 *
 * **只读**：全程没有任何写操作，不会污染服务器上的目录。
 * 报告里也不打印口令——本类从不打印 password。
 */
class RealServerIntegrationTest {

    private var backend: SftpStorageBackend? = null

    @Before
    fun setUp() {
        val properties = RealServer.load()
        Assume.assumeTrue(
            "跳过真网集成测试：没有找到 .toolchain/real-server.properties（opt-in）",
            properties != null,
        )
        backend = SftpStorageBackend(
            properties!!.config(),
            TofuHostKeyVerifier(InMemoryKnownHostsStore(), SftpHostKeyPolicy.TOFU),
        )
    }

    @After
    fun tearDown() {
        backend?.close()
    }

    @Test
    fun `probe 成功且三段耗时自洽`() = runBlocking {
        val report = requireBackend().probe()
        println(
            "[真网][SFTP] probe ok=" + report.ok + " dns=" + report.dnsMs + "ms tcp=" + report.connectMs +
                "ms handshake=" + report.handshakeMs + "ms total=" + report.totalMs + "ms message=" + report.message,
        )
        assertTrue("局域网 SFTP 应当可用：" + report.message, report.ok)
        assertTrue(report.dnsMs >= 0L && report.connectMs >= 0L && report.handshakeMs >= 0L)
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue("整段应在 15 秒内，实际 " + report.totalMs + " ms", report.totalMs < 15_000L)
    }

    @Test
    fun `capability 声明随机读与通道池`() {
        val caps = requireBackend().caps
        assertTrue(caps.randomAccess)
        assertTrue(caps.maxParallelReads >= 1)
        println("[真网][SFTP] caps randomAccess=" + caps.randomAccess + " writable=" + caps.writable + " maxParallelReads=" + caps.maxParallelReads)
    }

    @Test
    fun `list 根目录有内容`() = runBlocking {
        val entries = requireBackend().list("/")
        println("[真网][SFTP] list(/) 条目数=" + entries.size + " 前几个=" + entries.take(5).map { it.name + if (it.isDirectory) "/" else "" })
        assertTrue("根目录应当有内容（>=1 条）", entries.isNotEmpty())
        entries.forEach { entry ->
            assertNotNull(entry.name)
            assertFalse("路径里不应出现连续斜杠：" + entry.path, entry.path.contains("//"))
        }
        assertTrue(entries.none { it.name == "." || it.name == ".." })
    }

    @Test
    fun `找小文件 stat 并做两段偏移读取`() = runBlocking {
        val backend = requireBackend()
        val file = findSmallFile(backend)
        Assume.assumeTrue("服务器上没有找到足够大的小文件，跳过偏移断言", file != null)
        val path = file!!.path
        val stat = backend.stat(path)
        println("[真网][SFTP] stat(" + path + ") size=" + stat.size + " mtime=" + stat.mtime + " dir=" + stat.isDirectory)
        assertFalse("stat 的目标应当是文件", stat.isDirectory)
        assertTrue("文件应当至少 300 字节，实际 " + stat.size, stat.size >= 300L)

        val head = readExactly(backend, path, 0L, 100L)
        val mid = readExactly(backend, path, 200L, 100L)
        println(
            "[真网][SFTP] 偏移读取：offset0 长度=" + head.size + " offset200 长度=" + mid.size +
                " 前 8 字节=" + head.take(8).joinToString(",") + " / " + mid.take(8).joinToString(","),
        )
        assertEquals("第一段必须精确 100 字节", 100, head.size)
        assertEquals("第二段必须精确 100 字节", 100, mid.size)
        assertFalse("两段偏移的字节不应相同（否则说明没按偏移读）", head.contentEquals(mid))
    }

    @Test
    fun `全量读取与分段拼接逐字节一致`() = runBlocking {
        val backend = requireBackend()
        val file = findSmallFile(backend)
        Assume.assumeTrue("服务器上没有找到足够大的小文件，跳过拼接断言", file != null)
        val path = file!!.path
        val whole = backend.openRead(path, 0L, -1L).use { readAll(it) }
        val first = readExactly(backend, path, 0L, 100L)
        val second = readExactly(backend, path, 100L, 100L)
        val third = readExactly(backend, path, 200L, 100L)
        val joined = first + second + third
        println("[真网][SFTP] 拼接比对 path=" + path + " 全量=" + whole.size + " 字节, 三段=" + first.size + "+" + second.size + "+" + third.size)
        assertTrue("全量读取应 >= 300 字节，实际 " + whole.size, whole.size >= 300L)
        assertEquals("三段拼接长度应为 300", 300, joined.size)
        assertTrue("全量前 300 字节必须与三段拼接一致", whole.copyOfRange(0, 300).contentEquals(joined))
        assertEquals("全量读取应拿到文件全部内容", file.size, whole.size.toLong())
    }

    @Test
    fun `深偏移读取与本地字节一致`() = runBlocking {
        val backend = requireBackend()
        val file = findSmallFile(backend)
        Assume.assumeTrue("服务器上没有找到足够大的小文件，跳过深偏移断言", file != null)
        val path = file!!.path
        val size = backend.stat(path).size
        Assume.assumeTrue("文件太小，跳过深偏移", size >= 1_000L)
        val offset = size - 64L
        val tail = readExactly(backend, path, offset, 64L)
        println("[真网][SFTP] 末尾偏移读取 offset=" + offset + " 长度=" + tail.size)
        assertEquals(64, tail.size)
        // 用全量读取的结果做基准比对（同一份数据、两种取法必须一致）
        val whole = backend.openRead(path, 0L, -1L).use { readAll(it) }
        assertTrue(tail.contentEquals(whole.copyOfRange(offset.toInt(), (offset + 64L).toInt())))
    }

    // ------------------------------------------------------------ 工具

    private fun requireBackend(): SftpStorageBackend =
        backend ?: error("backend 未初始化（@Before 里 assume 失败过？）")

    private suspend fun readExactly(
        backend: SftpStorageBackend,
        path: String,
        offset: Long,
        length: Long,
    ): ByteArray = backend.openRead(path, offset, length).use { stream -> readExactly(stream, length.toInt()) }

    /** 在服务器上找一个「小文件」（300 B ~ 2 MB），最多扫两层目录（只读，不改动任何东西）。 */
    private suspend fun findSmallFile(backend: SftpStorageBackend): RemoteEntry? {
        val roots = listOf("/") + runCatching { backend.list("/") }.getOrDefault(emptyList())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.path }
            .take(8)
        for (dir in roots) {
            val entries = runCatching { backend.list(dir) }.getOrDefault(emptyList())
            entries.firstOrNull { !it.isDirectory && it.size in 300L..2_000_000L }?.let { return it }
            entries.filter { it.isDirectory && !it.name.startsWith(".") }.take(8).forEach { sub ->
                val nested = runCatching { backend.list(sub.path) }.getOrDefault(emptyList())
                nested.firstOrNull { !it.isDirectory && it.size in 300L..2_000_000L }?.let { return it }
            }
        }
        return null
    }

    /** 从工作区里读 opt-in 凭据（沿着父目录找 `.toolchain/real-server.properties`）。 */
    private data class RealServer(val host: String, val port: Int, val user: String, val password: String) {

        fun config(): SftpConfig = SftpConfig(
            host = host,
            port = port,
            username = user,
            password = password,
            basePath = "/",
            connectTimeoutMs = 8_000L,
            readTimeoutMs = 20_000L,
            hostKeyPolicy = SftpHostKeyPolicy.TOFU,
        )

        companion object {

            fun load(): RealServer? {
                val file = locateProperties() ?: return null
                val properties = Properties()
                return try {
                    file.inputStream().use { properties.load(it) }
                    val host = properties.getProperty("sftp.lan.host")?.trim().orEmpty()
                    if (host.isEmpty()) return null
                    RealServer(
                        host = host,
                        port = properties.getProperty("sftp.lan.port")?.trim()?.toIntOrNull() ?: 22,
                        user = properties.getProperty("user")?.trim().orEmpty(),
                        password = properties.getProperty("password").orEmpty(),
                    )
                } catch (e: Exception) {
                    null
                }
            }

            /** Gradle 单测的工作目录是模块目录，所以从 user.dir 往上找。 */
            private fun locateProperties(): File? {
                var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
                while (dir != null) {
                    val candidate = File(dir, ".toolchain/real-server.properties")
                    if (candidate.isFile) return candidate
                    dir = dir.parentFile
                }
                return null
            }
        }
    }
}
