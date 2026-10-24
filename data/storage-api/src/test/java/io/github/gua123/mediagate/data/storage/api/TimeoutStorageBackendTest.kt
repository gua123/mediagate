package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * 超时保险（**2026-10-03 真机**：「放后台再回来，整个软件好像未响应，网络测试/编辑点不开，
 * 浏览页无限刷新」）。口径：任何操作都不能无限等下去——超时抛 [StorageException.Timeout]，
 * 界面才能给出中文原因与"重试"，而不是永远转圈。
 */
class TimeoutStorageBackendTest {

    /** 只会永远挂起的假后端。 */
    private class HangingBackend : StorageBackend {
        override val id: String = "hang:/"
        override val caps: Caps = Caps()
        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> {
            delay(Long.MAX_VALUE / 2)
            return emptyList()
        }
        override suspend fun stat(path: String): RemoteEntry {
            delay(Long.MAX_VALUE / 2)
            error("unreachable")
        }
        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            object : RangeStream {
                override val length: Long = 0L
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    delay(Long.MAX_VALUE / 2)
                    return -1
                }
                override suspend fun seek(position: Long) = Unit
                override fun position(): Long = 0L
                override fun close() = Unit
            }
        override suspend fun write(path: String, data: InputStream) = Unit
        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)
        override fun close() = Unit
    }

    /** 一切正常的假后端（确保包装后行为不变）。 */
    private class FastBackend : StorageBackend {
        override val id: String = "fast:/"
        override val caps: Caps = Caps(randomAccess = true)
        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> =
            listOf(RemoteEntry(name = "a.mp4", path = "/a.mp4", size = 10L, mtime = 1L))
        override suspend fun stat(path: String): RemoteEntry =
            RemoteEntry(name = "a.mp4", path = path, size = 10L, mtime = 1L)
        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            object : RangeStream {
                override val length: Long = 10L
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = -1
                override suspend fun seek(position: Long) = Unit
                override fun position(): Long = 0L
                override fun close() = Unit
            }
        override suspend fun write(path: String, data: InputStream) = Unit
        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)
        override fun close() = Unit
    }

    /** 真正**阻塞**线程的假后端（模拟 OkHttp / JSch 的阻塞网络调用，不响应协程取消）。 */
    private class BlockingBackend(private val sleepMs: Long) : StorageBackend {
        override val id: String = "blocking:/"
        override val caps: Caps = Caps()
        override suspend fun list(dir: String, page: Page?): List<RemoteEntry> {
            Thread.sleep(sleepMs)
            return emptyList()
        }
        override suspend fun stat(path: String): RemoteEntry {
            Thread.sleep(sleepMs)
            return RemoteEntry(name = "a.mp4", path = path, size = 1L, mtime = 1L)
        }
        override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream =
            object : RangeStream {
                override val length: Long = 1L
                override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
                    Thread.sleep(sleepMs)
                    return -1
                }
                override suspend fun seek(position: Long) {
                    Thread.sleep(sleepMs)
                }
                override fun position(): Long = 0L
                override fun close() = Unit
            }
        override suspend fun write(path: String, data: InputStream) = Unit
        override suspend fun probe(): ProbeReport = ProbeReport(ok = true)
        override fun close() = Unit
    }

    /**
     * **2026-10-24 真机「公网返回上一级一直转圈」的回归**：
     * 阻塞调用不响应协程取消，普通的超时包装会一直等它返回；这里必须**到点就返回**。
     */
    @Test
    fun 阻塞的列目录也会到点返回而不是等它跑完() = runBlocking {
        val backend = TimeoutStorageBackend(BlockingBackend(sleepMs = 2_000L), operationTimeoutMs = 200L)
        val started = System.currentTimeMillis()
        val error = runCatching { backend.list("", null) }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("应当是超时异常，实际：" + error, error is StorageException.Timeout)
        assertTrue("阻塞调用不响应取消，超时必须到点返回（实际 " + elapsed + " ms）", elapsed < 1_200L)
    }

    @Test
    fun 阻塞的读取也会到点返回而不是等它跑完() = runBlocking {
        val backend = TimeoutStorageBackend(BlockingBackend(sleepMs = 2_000L), readTimeoutMs = 200L)
        val stream = backend.openRead("/a.mp4", 0L, 10L)
        val started = System.currentTimeMillis()
        val error = runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("应当是超时异常，实际：" + error, error is StorageException.Timeout)
        assertTrue("阻塞读取不响应取消，超时必须到点返回（实际 " + elapsed + " ms）", elapsed < 1_200L)
    }

    @Test
    fun 挂死的列目录会超时而不是永远等() = runBlocking {
        val backend = TimeoutStorageBackend(HangingBackend(), operationTimeoutMs = 100L)
        val error = runCatching { backend.list("", null) }.exceptionOrNull()
        assertTrue("应当是超时异常，实际：" + error, error is StorageException.Timeout)
    }

    @Test
    fun 挂死的读取也会超时() = runBlocking {
        // 打开本身是立即返回的（只测读），所以打开给宽一点的期限
        val backend = TimeoutStorageBackend(
            HangingBackend(),
            operationTimeoutMs = 5_000L,
            readTimeoutMs = 100L,
        )
        val stream = backend.openRead("/a.mp4", 0L, 10L)
        val error = runCatching { stream.read(ByteArray(8), 0, 8) }.exceptionOrNull()
        assertTrue("应当是超时异常，实际：" + error, error is StorageException.Timeout)
    }

    @Test
    fun 正常后端行为不变() = runBlocking {
        val backend = TimeoutStorageBackend(FastBackend())
        assertEquals(1, backend.list("", null).size)
        assertEquals(10L, backend.stat("/a.mp4").size)
        assertTrue(backend.caps.randomAccess)
        assertEquals("fast:/", backend.id)
    }
}
