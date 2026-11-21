package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * **回归护栏（2026-11-21 真机 SIGABRT）**：`MG/storage-timeout` 的打点密度。
 *
 * 事故现场：崩溃前 1–5 秒 `worker 启动：读取` 每秒 94 / 137 / 190 / 464 / 223 条，
 * 20 秒 732 条——每次存储读打「提交 / 启动 / 结束」三条。除了 logcat 自己变成负载，
 * AppLog 的环形缓冲只有 400 行，一秒就被冲干净，崩溃报告里的时间线（"出事前在干什么"）
 * 反而全是 worker 噪音。现在降级为 **每 32 次采 1 条**。
 */
class StorageTimeoutLogSamplingTest {

    /** 读多少给多少的假后端（不阻塞、不超时）。 */
    private class QuietBackend : StorageBackend {
        override val id: String = "quiet:/"
        override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 4)
        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = emptyList()
        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = "a.mp4", path = path, size = 4096L, mtime = 1L)

        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            object : RangeStream {
                override val length: Long = 4096L
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    if (len <= 0) return 0
                    return minOf(len, 16)
                }
                override suspend fun seek(position: Long) = Unit
                override fun position(): Long = 0L
                override fun close() = Unit
            }

        override suspend fun write(path: String, data: InputStream) = Unit
        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)
        override fun close() = Unit
    }

    @Test
    fun `每 32 次存储读只打一条采样日志而不是每次三条`() = runBlocking {
        StorageTimeoutLog.reset()
        AppLog.clearBuffer()
        val backend = TimeoutStorageBackend(QuietBackend())
        val stream = backend.openRead("/movie/a.mp4", 0L, -1L)
        val buf = ByteArray(16)
        repeat(100) { stream.read(buf, 0, buf.size) }

        val lines = AppLog.snapshot()
        val starts = lines.count { it.contains("worker 启动") }
        // 1 次 openRead + 100 次 read = 101 次 withHardDeadline；采样点是第 1/33/65/97 次
        assertEquals("101 次存储调用只该留 4 条采样日志", 4, starts)
        assertTrue(
            "不许再出现每调用一条的 提交/结束 日志",
            lines.none { it.contains("worker 提交") || it.contains("worker 结束") },
        )
        assertTrue("采样日志要自报家门（1/32），方便现场判断是不是它", lines.any { it.contains("1/32") || it.contains("每 32 次") })
    }

    @Test
    fun `1000 次读也冲不干净 400 行环形缓冲`() = runBlocking {
        StorageTimeoutLog.reset()
        AppLog.clearBuffer()
        val backend = TimeoutStorageBackend(QuietBackend())
        val stream = backend.openRead("/movie/big.mp4", 0L, -1L)
        val buf = ByteArray(16)
        repeat(1000) { stream.read(buf, 0, buf.size) }

        val snapshot = AppLog.snapshot()
        assertTrue(
            "1000 次读（旧实现 3000 行）现在最多留 32 行左右，实际 " + snapshot.size,
            snapshot.size <= 40,
        )
        assertTrue("但采样日志必须还在（不是把打点整个关掉）", snapshot.any { it.contains("worker 启动") })
    }
}
