package io.github.gua123.mediagate.data.storage.sftp

import io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * **opt-in 真机网络缓存基准**（2026-10-25 用户提供 SFTP 测试链接后加）。
 *
 * 只在 `MEDIAGATE_BENCHMARK=1` 时跑：在服务器上自动找第一个视频，用**同一个路径**分别跑
 * 1 / 2 / 4 / 8 MB 段大小，记录冷首读、顺序读、随机 seek、缓存命中四组耗时。
 *
 * 凭据只从 `.toolchain/real-server.properties` 读（gitignore），**不打印密码、不写进报告**。
 * 结果用于回答：段大小到底该用哪一档、A 方案（访问 LRU + 自适应预读）是否值得继续。
 */
class CacheBenchmarkTest {

    private var backend: SftpStorageBackend? = null
    private val tempDirs = mutableListOf<File>()

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "跳过缓存基准：设置 MEDIAGATE_BENCHMARK=1 才跑",
            System.getenv("MEDIAGATE_BENCHMARK") == "1",
        )
        val properties = RealServer.load()
        Assume.assumeTrue(
            "跳过缓存基准：没有 .toolchain/real-server.properties",
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
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    @Test
    fun `同一视频按 1 2 4 8 MB 段大小对比`() = runBlocking {
        val sftp = backend ?: return@runBlocking
        val video = findVideo(sftp) ?: return@runBlocking
        println("[bench] 视频：path=" + video.path + " size=" + video.size + " bytes")
        val size = video.size
        Assume.assumeTrue("视频太小，无法做 16 MB 顺序读基准", size >= 32L * 1024 * 1024)

        val mb = 1024 * 1024L
        val firstBytes = minOf(8L * mb, size)
        val seqBytes = minOf(16L * mb, (size - firstBytes).coerceAtLeast(0L))
        val seekBytes = minOf(4L * mb, size)
        val seekOffset = ((size / 2) / mb) * mb

        for (segmentMb in listOf(1L, 2L, 4L, 8L)) {
            val segment = segmentMb * mb
            val root = Files.createTempDirectory("mediagate-bench-" + segmentMb + "mb").toFile()
            tempDirs += root
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val cache = SegmentedCacheBackend(
                delegate = sftp,
                rootDir = root,
                segmentBytes = segment,
                maxBytes = 256L * mb,
                io = Dispatchers.IO,
                // 基准只测"这一段要多久"，关掉预读，别让后台任务污染耗时
                readAheadSegments = 0,
                readAheadScope = scope,
            )
            try {
                val cold = timeRead(cache, video.path, 0L, firstBytes)
                val seq = timeRead(cache, video.path, firstBytes, seqBytes)
                val seek = timeRead(cache, video.path, seekOffset, seekBytes)
                val cached = timeRead(cache, video.path, seekOffset, seekBytes)
                println(
                    "[bench] seg=" + segmentMb + "MB" +
                        " cold8MB=" + cold + "ms" +
                        " seq16MB=" + seq + "ms" +
                        " seek4MB=" + seek + "ms" +
                        " cachedSeek4MB=" + cached + "ms",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun timeRead(
        cache: SegmentedCacheBackend,
        path: String,
        offset: Long,
        length: Long,
    ): Long {
        if (length <= 0L) return -1L
        val started = System.nanoTime()
        // 基准自己设硬上限：SFTP 卡住时宁可失败，也不要把整轮测试拖死
        kotlinx.coroutines.withTimeout(120_000L) {
            cache.openRead(path, offset, length).use { stream ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (total < length) {
                    val want = minOf(buffer.size.toLong(), length - total).toInt()
                    val read = stream.read(buffer, 0, want)
                    if (read <= 0) break
                    total += read
                }
            }
        }
        return ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
    }

    /** 递归找第一个 >= 32 MB 的视频；不打印凭据，只打印相对路径。 */
    private suspend fun findVideo(sftp: SftpStorageBackend): Video? {
        val extensions = setOf("mp4", "mkv", "ts", "avi", "mov", "webm", "flv", "m4v")
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += "" to 0
        var visited = 0
        var fallback: Video? = null
        while (queue.isNotEmpty() && visited < 80) {
            val (dir, depth) = queue.removeFirst()
            if (depth > 3) continue
            val entries = runCatching { sftp.list(dir) }.getOrElse { continue }
            visited++
            for (entry in entries) {
                if (entry.isDirectory) {
                    queue += entry.path to (depth + 1)
                    continue
                }
                if (entry.name.substringAfterLast('.', "").lowercase() !in extensions) continue
                if (entry.size < 32L * 1024 * 1024) continue
                val video = Video(entry.path, entry.size)
                if (fallback == null) fallback = video
                if (entry.size >= 64L * 1024 * 1024) return video
            }
        }
        return fallback
    }

    private data class Video(val path: String, val size: Long)

    /** 与 RealServerIntegrationTest 同一份 opt-in 凭据（不打印密码）。 */
    private class RealServer(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
    ) {
        fun config(): SftpConfig = SftpConfig(
            host = host,
            port = port,
            username = user,
            password = password,
            basePath = "/",
            hostKeyPolicy = SftpHostKeyPolicy.TOFU,
        )

        companion object {
            fun load(): RealServer? {
                val file = locateProperties() ?: return null
                return runCatching {
                    val properties = Properties().apply { file.inputStream().use { load(it) } }
                    RealServer(
                        host = properties.getProperty("sftp.lan.host").trim(),
                        port = properties.getProperty("sftp.lan.port").trim().toIntOrNull() ?: 22,
                        user = properties.getProperty("user").trim(),
                        password = properties.getProperty("password").orEmpty(),
                    )
                }.getOrNull()
            }

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
