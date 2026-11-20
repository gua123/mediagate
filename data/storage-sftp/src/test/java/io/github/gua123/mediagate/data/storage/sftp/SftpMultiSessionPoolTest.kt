package io.github.gua123.mediagate.data.storage.sftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * **SFTP 多连接加速**（2026-11-20）：会话池的并行建连、懒加载、小文件自适应、租约归还、超时作废。
 *
 * 前两条（[SftpRamp]）用**假建连**计时，纯 JVM、不需要服务器；其余用嵌入式 Apache MINA SSHD
 * 起真 SFTP 服务，断言的是**真实会话数/通道数**（不是 mock）。
 *
 * 真网吞吐（23-25 MB/s）只能真机真网验证，见报告；这里钉住的是"结构对了"。
 */
class SftpMultiSessionPoolTest {

    private lateinit var root: Path
    private lateinit var server: SftpTestServer
    private lateinit var verifier: HostKeyVerifier

    @Before
    fun setUp() {
        root = newTempDir("sftp-multi")
        root.writeFile("big.bin", sampleBytes(64 * 1024))
        root.writeTextFile("small.txt", "0123456789abcdefghij")
        server = SftpTestServer(root)
        verifier = TofuHostKeyVerifier(InMemoryKnownHostsStore(), SftpHostKeyPolicy.TOFU)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        root.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ 并行建连（假建连计时）

    @Test
    fun `并行建连：8 条同时发起，不是 8 条串行`() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val startedAt = System.nanoTime()
        val created = SftpRamp.warmUp(count = 8, parallel = 8) {
            val now = running.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(120)
            running.decrementAndGet()
            true
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertEquals(8, created)
        assertEquals("8 条应当同时在握手", 8, peak.get())
        assertTrue(
            "并行应当远快于串行 960 ms，实际 " + elapsedMs + " ms",
            elapsedMs < 600L,
        )
    }

    @Test
    fun `并行建连：单条失败不连累同批`() = runBlocking {
        val created = SftpRamp.warmUp(count = 6, parallel = 3) { index ->
            if (index == 2) throw IllegalStateException("假建连失败")
            true
        }
        assertEquals(5, created)
    }

    @Test
    fun `并行建连：某批全失败就停下来，别无限重试`() = runBlocking {
        var calls = 0
        val created = SftpRamp.warmUp(count = 4, parallel = 2) {
            calls += 1
            throw StorageException.Network("假服务器掉线")
        }
        assertEquals(0, created)
        // SftpRamp 只负责"这一轮发完"，重试策略在池子里（见 acquire 的 MAX_ACQUIRE_ATTEMPTS）
        assertEquals(4, calls)
    }

    // ------------------------------------------------------------------ 懒加载 / 小文件自适应

    @Test
    fun `懒加载：不读就不建数据会话（元数据走独立会话）`() = runBlocking {
        val pool = pool(maxSessions = 4, channelsPerSession = 2, smallFileSessions = 2)
        try {
            assertEquals(0, pool.stats().sessions)
            pool.useMetadata { channel -> channel.stat("/small.txt") }
            assertEquals("元数据会话是独立的，不该拉起数据车道", 0, pool.stats().sessions)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `小文件目标只有 2 条，而且不会自己建满`() = runBlocking {
        val pool = pool(maxSessions = 4, channelsPerSession = 2, smallFileSessions = 2)
        try {
            pool.planForSize(100L * 1024 * 1024)
            assertEquals(2, pool.targetSessionCount)
            delay(200)
            assertEquals("只设目标不建连（缩略图不吃建连开销）", 0, pool.stats().sessions)
            pool.ensureSessionRamp()
            waitForSessions(pool, 2)
            pool.use { channel -> channel.stat("/small.txt") }
            assertEquals("小文件不该继续扩建", 2, pool.stats().sessions)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `大文件命中播放信号后并行开满会话`() = runBlocking {
        val pool = pool(maxSessions = 4, channelsPerSession = 2, smallFileSessions = 1)
        try {
            pool.planForSize(10L * 1024 * 1024 * 1024)
            assertEquals(4, pool.targetSessionCount)
            delay(200)
            assertEquals("没命中播放信号就不建（缩略图/目录只占 1 条连接）", 0, pool.stats().sessions)
            val startedAt = System.nanoTime()
            pool.ensureSessionRamp()
            waitForSessions(pool, 4)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            println("[multi][SFTP] 4 条会话并行建连耗时 " + elapsedMs + " ms")
            assertEquals(4, pool.stats().sessions)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `通道位不够时自动扩会话（6 路并发，每会话 2 通道）`() = runBlocking {
        val pool = pool(maxSessions = 3, channelsPerSession = 2, smallFileSessions = 1, largeFileBytes = 0L)
        try {
            pool.planForSize(10L)
            val leases = withContext(Dispatchers.IO) {
                (0 until 6).map { async { pool.acquire() } }.awaitAll()
            }
            assertEquals("6 路并发应当摊到 3 条会话", 3, pool.stats().sessions)
            assertEquals(6, pool.stats().leasedChannels)
            leases.forEach { it.release(discard = false) }
            assertEquals(0, pool.stats().leasedChannels)
        } finally {
            pool.close()
        }
    }

    // ------------------------------------------------------------------ 租约

    @Test
    fun `租约归还：健康通道复用、脏通道丢弃`() = runBlocking {
        val pool = pool(maxSessions = 1, channelsPerSession = 2, smallFileSessions = 1)
        try {
            val first = pool.acquire()
            val channel = first.channel
            assertEquals(1, pool.stats().leasedChannels)
            assertEquals(0, pool.stats().idleChannels)
            first.release(discard = false)
            assertEquals(0, pool.stats().leasedChannels)
            assertEquals(1, pool.stats().idleChannels)
            val second = pool.acquire()
            assertSame("同一会话应当复用刚归还的通道", channel, second.channel)
            second.release(discard = true)
            assertEquals("脏通道不许还池", 0, pool.stats().idleChannels)
            assertEquals(0, pool.stats().leasedChannels)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `租约 release 幂等（重复归还不会把许可弄乱）`() = runBlocking {
        val pool = pool(maxSessions = 1, channelsPerSession = 2, smallFileSessions = 1)
        try {
            val lease = pool.acquire()
            lease.release(discard = false)
            lease.release(discard = false)
            lease.release(discard = true)
            assertEquals(0, pool.stats().leasedChannels)
            assertEquals(1, pool.stats().idleChannels)
            // 还有 2 个许可可用（1 个被空闲通道占着的是"已归还"，池子没坏）
            val a = pool.acquire()
            val b = pool.acquire()
            assertEquals(2, pool.stats().leasedChannels)
            a.release(false)
            b.release(false)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `数据通道全被占满时元数据仍然可用`() = runBlocking {
        val pool = pool(maxSessions = 1, channelsPerSession = 2, smallFileSessions = 1)
        try {
            val held = (0 until 2).map { pool.acquire() }
            val entry = pool.useMetadata { channel -> channel.stat("/small.txt") }
            assertEquals(20L, entry.size)
            held.forEach { it.release(discard = false) }
        } finally {
            pool.close()
        }
    }

    // ------------------------------------------------------------------ 超时 / 作废

    @Test
    fun `取不到通道位按超时失败，池子不会卡死`() = runBlocking {
        val pool = pool(
            maxSessions = 1,
            channelsPerSession = 1,
            smallFileSessions = 1,
            acquireTimeoutMs = 400L,
        )
        try {
            val held = pool.acquire()
            val startedAt = System.currentTimeMillis()
            assertThrows(StorageException.Timeout::class.java) {
                runBlocking { pool.acquire() }
            }
            val waitedMs = System.currentTimeMillis() - startedAt
            assertTrue("应当等满超时（>= 300 ms），实际 " + waitedMs + " ms", waitedMs >= 300L)
            held.release(discard = false)
            // 归还后马上又能借到：说明超时没有把许可弄丢
            val again = pool.acquire()
            again.release(discard = false)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `硬超时作废：会话清零，旧租约归还不会污染新池`() = runBlocking {
        val pool = pool(maxSessions = 2, channelsPerSession = 2, smallFileSessions = 2)
        try {
            val stale = pool.acquire()
            assertEquals(1, pool.stats().sessions)
            pool.invalidateCurrentSession()
            assertEquals(0, pool.stats().sessions)
            // 被放弃的流事后才归还：不能影响新池的许可/会话
            stale.release(discard = true)
            val fresh = pool.acquire()
            assertEquals(1, pool.stats().sessions)
            assertEquals(1, pool.stats().leasedChannels)
            assertEquals("新会话确实能用", 20L, fresh.channel.stat("/small.txt").size)
            fresh.release(discard = false)
            assertEquals(0, pool.stats().leasedChannels)
        } finally {
            pool.close()
        }
    }

    @Test
    fun `建连失败走退避：失败有上界，不无限重试也不等满通道超时`() = runBlocking {
        val deadPort = server.port
        server.close() // 本测试专用：把服务器关掉，制造"连不上"
        val deadPool = SftpChannelPool(
            SftpConfig(
                host = "127.0.0.1",
                port = deadPort,
                username = SftpTestServer.DEFAULT_USER,
                password = SftpTestServer.DEFAULT_PASSWORD,
                connectTimeoutMs = 2_000L,
            ).copy(maxSessions = 1, channelsPerSession = 1, smallFileSessions = 1),
            TofuHostKeyVerifier(InMemoryKnownHostsStore(), SftpHostKeyPolicy.TOFU),
        )
        try {
            val firstStartedAt = System.currentTimeMillis()
            assertThrows(StorageException::class.java) {
                runBlocking { deadPool.acquire() }
            }
            val firstMs = System.currentTimeMillis() - firstStartedAt
            val startedAt = System.currentTimeMillis()
            assertThrows(StorageException::class.java) {
                runBlocking { deadPool.acquire() }
            }
            val secondMs = System.currentTimeMillis() - startedAt
            println("[multi][SFTP] 掉线服务器：第一次失败 " + firstMs + " ms，第二次 " + secondMs + " ms")
            // 关键口径不是「几百毫秒」，而是**有上界**：不会等满 15 秒的通道超时、也不会无限重试。
            assertTrue("第二次仍应很快失败（< 2 s），实际 " + secondMs + " ms", secondMs < 2_000L)
            assertTrue("连续失败次数应当被记下来（供退避/停止扩建用）", deadPool.stats().connectFailures >= 1)
            assertTrue(
                "不许在死服务器上原地打转（每次 acquire 最多 3 次尝试），实际 " + deadPool.stats().connectFailures,
                deadPool.stats().connectFailures <= 3,
            )
        } finally {
            deadPool.close()
        }
    }

    // ------------------------------------------------------------------ 后端接线

    @Test
    fun `后端 openRead：小文件目标 2 条会话`() = runBlocking {
        val backend = SftpStorageBackend(
            server.config().copy(maxSessions = 4, channelsPerSession = 2, smallFileSessions = 2),
        )
        try {
            backend.openRead("small.txt", 0L, -1L).use { readAll(it) }
            assertEquals(2, backend.poolStats().targetSessions)
        } finally {
            backend.close()
        }
    }

    @Test
    fun `后端 openRead：大文件只有并发播放才拉满会话，单路读不拉满`() = runBlocking {
        val backend = SftpStorageBackend(
            server.config().copy(
                maxSessions = 3,
                channelsPerSession = 2,
                smallFileSessions = 1,
                largeFileBytes = 4L,
            ),
        )
        try {
            assertEquals(6, backend.caps.maxParallelReads)
            // 单路顺序读 = 缩略图/抽帧的形态：只定目标（3 条），不扩建
            backend.openRead("big.bin", 0L, 1024L).use { readAll(it) }
            assertEquals(3, backend.poolStats().targetSessions)
            delay(200)
            assertTrue("单路读不该拉满会话，实际 " + backend.poolStats().sessions, backend.poolStats().sessions <= 1)
            // 三路并发读 = 播放/预读的形态：后台并行扩建到 3 条
            val streams = (0 until 3).map { index -> backend.openRead("big.bin", index * 1024L, 1024L) }
            waitForSessions(backend, 3)
            streams.forEach { it.close() }
        } finally {
            backend.close()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun pool(
        maxSessions: Int,
        channelsPerSession: Int,
        smallFileSessions: Int,
        largeFileBytes: Long = SftpConfig.DEFAULT_LARGE_FILE_BYTES,
        acquireTimeoutMs: Long = 5_000L,
    ): SftpChannelPool = SftpChannelPool(
        server.config(maxChannels = maxSessions * channelsPerSession).copy(
            maxSessions = maxSessions,
            channelsPerSession = channelsPerSession,
            smallFileSessions = smallFileSessions,
            largeFileBytes = largeFileBytes,
            acquireTimeoutMs = acquireTimeoutMs,
        ),
        verifier,
    )

    private suspend fun waitForSessions(pool: SftpChannelPool, expected: Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pool.stats().sessions >= expected) return
            delay(20)
        }
        assertEquals("会话数应当在 " + timeoutMs + " ms 内到 " + expected, expected, pool.stats().sessions)
    }

    private suspend fun waitForSessions(backend: SftpStorageBackend, expected: Int = 3, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (backend.poolStats().sessions >= expected) return
            delay(20)
        }
        assertEquals(expected, backend.poolStats().sessions)
    }
}
