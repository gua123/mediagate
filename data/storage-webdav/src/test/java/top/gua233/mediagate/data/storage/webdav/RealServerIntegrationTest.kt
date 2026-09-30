package io.github.gua123.mediagate.data.storage.webdav

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
 * **真网集成测试（opt-in）**：对用户提供的局域网 WebDAV 服务器跑一遍（**R2/R4/R8**）。
 *
 * 凭据来自 `.toolchain/real-server.properties`（java.util.Properties，整个 `.toolchain/` 已被 gitignore）：
 * `dav.lan.url` / `user` / `password`。**文件不存在就整类跳过**（[Assume.assumeTrue]），
 * 所以 CI / 别的机器上不会因为缺少私有凭据而红。
 *
 * 覆盖的链路：probe 三段计时 → list 根目录 → 挑一个小文件 stat → 两段 Range 读取（字节必须不同、
 * 长度必须精确）→ 全量读取与"分段拼接"逐字节一致（R4 拖拽 seek 的正确性基础）。
 *
 * **只读**：全程没有任何 PUT / MKCOL / DELETE，不会污染服务器上的目录。
 * 报告里也不要打印口令——本类从不打印 password。
 */
class RealServerIntegrationTest {

    private var backend: WebDavStorageBackend? = null

    @Before
    fun setUp() {
        val config = RealServer.load()
        Assume.assumeTrue(
            "跳过真网集成测试：没有找到 .toolchain/real-server.properties（opt-in）",
            config != null,
        )
        backend = WebDavStorageBackend(
            WebDavConfig(
                baseUrl = config!!.davLanUrl,
                username = config.user,
                password = config.password,
                rootPath = "/",
                connectTimeoutMs = 8_000L,
                readTimeoutMs = 20_000L,
                allowInsecureHttp = true,
            ),
        )
    }

    @After
    fun tearDown() {
        backend?.close()
    }

    @Test
    fun `probe 成功且三段耗时合理`() = runBlocking {
        val report = requireBackend().probe()
        println("[真网] probe ok=" + report.ok + " dns=" + report.dnsMs + "ms tcp=" + report.connectMs + "ms handshake=" + report.handshakeMs + "ms total=" + report.totalMs + "ms message=" + report.message)
        assertTrue("局域网 WebDAV 应当可用：" + report.message, report.ok)
        assertTrue("三段耗时不应为负", report.dnsMs >= 0L && report.connectMs >= 0L && report.handshakeMs >= 0L)
        assertEquals(report.totalMs, report.dnsMs + report.connectMs + report.handshakeMs)
        assertTrue("整段耗时应该在 10 秒内，实际 " + report.totalMs + " ms", report.totalMs < 10_000L)
    }

    @Test
    fun `list 根目录有内容且都是合法路径`() = runBlocking {
        val entries = requireBackend().list("/")
        println("[真网] list(/) 条目数=" + entries.size + " 前几个=" + entries.take(5).map { it.name + if (it.isDirectory) "/" else "" })
        assertTrue("根目录应当有内容（>=1 条）", entries.isNotEmpty())
        entries.forEach { entry ->
            assertNotNull(entry.name)
            assertFalse("路径不应以 / 之外的前缀出现：" + entry.path, entry.path.contains("//"))
        }
        assertTrue("应当至少有目录或文件", entries.any { it.isDirectory } || entries.any { !it.isDirectory })
    }

    @Test
    fun `stat 一个小文件并做两段 Range 读取`() = runBlocking {
        val backend = requireBackend()
        val file = findSmallFile(backend)
        Assume.assumeTrue("服务器上没有找到足够大的小文件，跳过 Range 断言", file != null)
        val path = file!!.path
        val stat = backend.stat(path)
        println("[真网] stat(" + path + ") size=" + stat.size + " mtime=" + stat.mtime + " etag=" + stat.etag)
        assertFalse("stat 的目标应当是文件", stat.isDirectory)
        assertTrue("文件应当至少有 300 字节，实际 " + stat.size, stat.size >= 300L)

        val head = readExactly(backend, path, offset = 0L, length = 100L)
        val mid = readExactly(backend, path, offset = 200L, length = 100L)
        println("[真网] range 读取：offset0 长度=" + head.size + " offset200 长度=" + mid.size + " 前 8 字节=" + head.take(8).joinToString(",") + " / " + mid.take(8).joinToString(","))
        assertEquals("第一段必须精确 100 字节", 100, head.size)
        assertEquals("第二段必须精确 100 字节", 100, mid.size)
        assertFalse("两段 Range 的字节内容不应相同（否则是服务器忽略了 Range）", head.contentEquals(mid))
    }

    @Test
    fun `全量读取与分段拼接逐字节一致`() = runBlocking {
        val backend = requireBackend()
        val file = findSmallFile(backend)
        Assume.assumeTrue("服务器上没有找到足够大的小文件，跳过拼接断言", file != null)
        val path = file!!.path
        // 全长读取（length = -1 表示读到末尾）
        val whole = readAll(backend.openRead(path, 0L, -1L))
        val firstBlock = readAll(backend.openRead(path, 0L, 100L))
        val secondBlock = readAll(backend.openRead(path, 100L, 100L))
        val thirdBlock = readAll(backend.openRead(path, 200L, 100L))
        val joined = firstBlock + secondBlock + thirdBlock
        println("[真网] 拼接比对 path=" + path + " 全量=" + whole.size + " 字节, 三段=" + firstBlock.size + "+" + secondBlock.size + "+" + thirdBlock.size + "=" + joined.size)
        assertTrue("全量读取应当 >= 300 字节，实际 " + whole.size, whole.size >= 300L)
        assertEquals("三段拼接长度应等于 300", 300, joined.size)
        assertTrue("全量前 300 字节必须与三段拼接一致", whole.copyOfRange(0, 300).contentEquals(joined))
        assertEquals("全量读取应当拿到文件全部内容", file.size, whole.size.toLong())
    }

    // ------------------------------------------------------------ 工具

    private fun requireBackend(): WebDavStorageBackend =
        backend ?: error("backend 未初始化（@Before 里 assume 失败过？）")

    /** 精确读 [length] 字节（少读就算失败——Range 语义必须精确，R4 靠它）。 */
    private suspend fun readExactly(
        backend: WebDavStorageBackend,
        path: String,
        offset: Long,
        length: Long,
    ): ByteArray = backend.openRead(path, offset, length).use { stream -> readAll(stream) }

    /**
     * 在服务器上找一个"小文件"（300 B ~ 2 MB），最多扫 2 层目录。
     *
     * 挑小文件是为了让"全量读取 vs 分段拼接"的比对在秒级完成，且不浪费用户流量。
     */
    private suspend fun findSmallFile(backend: WebDavStorageBackend): RemoteEntry? {
        val roots = listOf("/") + runCatching { backend.list("/") }.getOrDefault(emptyList())
            .filter { it.isDirectory }
            .map { it.path }
        for (dir in roots) {
            val entries = runCatching { backend.list(dir) }.getOrDefault(emptyList())
            entries.firstOrNull { !it.isDirectory && it.size in 300L..2_000_000L }?.let { return it }
            // 再看一层子目录
            entries.filter { it.isDirectory }.forEach { sub ->
                val nested = runCatching { backend.list(sub.path) }.getOrDefault(emptyList())
                nested.firstOrNull { !it.isDirectory && it.size in 300L..2_000_000L }?.let { return it }
            }
        }
        return null
    }

    /** 从工作区里读 opt-in 凭据（沿着父目录找 `.toolchain/real-server.properties`）。 */
    private data class RealServer(val davLanUrl: String, val user: String, val password: String) {

        companion object {
            fun load(): RealServer? {
                val file = locateProperties() ?: return null
                val properties = Properties()
                return try {
                    file.inputStream().use { properties.load(it) }
                    val url = properties.getProperty("dav.lan.url")?.trim().orEmpty()
                    if (url.isEmpty()) return null
                    RealServer(
                        davLanUrl = url,
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
