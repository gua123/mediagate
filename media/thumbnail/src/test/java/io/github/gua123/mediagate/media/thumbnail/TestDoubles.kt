package io.github.gua123.mediagate.media.thumbnail

import kotlinx.coroutines.delay
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 内存里的 [RandomAccessSource]：语义与 FileStorageBackend 的流一致，用来在纯 JVM 单测里替代真实网络。 */
internal class FakeRandomAccessSource(private val data: ByteArray) : RandomAccessSource {

    @Volatile
    var closed: Boolean = false
        private set

    override val size: Long get() = data.size.toLong()

    override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset < 0 || offset >= data.size) return -1
        val n = minOf(len.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buf, off, n)
        return n
    }

    override suspend fun readFully(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= data.size) return ByteArray(0)
        val n = minOf(len.toLong(), data.size - offset).toInt()
        return data.copyOfRange(offset.toInt(), offset.toInt() + n)
    }

    override fun close() {
        closed = true
    }
}

/** [StorageBackend] 的假实现：只实现抽帧需要的 [StorageBackend.openRead]。 */
internal class FakeBackend(
    override val id: String = "local-file:/tmp/root",
    override val caps: Caps = Caps(randomAccess = true, maxParallelReads = 8, writable = true),
    private val payload: ByteArray = ByteArray(4096) { it.toByte() },
    private val failOpenRead: Boolean = false,
    /** 目录内容（同目录封面兜底要用 list：`/music` → 该目录下的条目）。 */
    private val entries: Map<String, List<RemoteEntry>> = emptyMap(),
) : StorageBackend {

    /** openRead 被调用的次数（验证「缓存命中不碰远端」）。 */
    val openCount = AtomicInteger()

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = entries[dir].orEmpty()

    override suspend fun stat(path: String): RemoteEntry =
        RemoteEntry(name = path.substringAfterLast('/'), path = path, size = payload.size.toLong(), mtime = 1L)

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        openCount.incrementAndGet()
        if (failOpenRead) throw StorageException.Network("打不开：$path")
        return FakeRangeStream(payload, offset, length)
    }

    override suspend fun write(path: String, data: InputStream) = Unit

    override suspend fun probe(): ProbeReport = ProbeReport(ok = true)

    override fun close() = Unit
}

/** 以 [FakeBackend] 的数据为基础的可 seek 流。 */
internal class FakeRangeStream(
    private val data: ByteArray,
    private val start: Long,
    requestedLength: Long,
) : RangeStream {

    private val available = (data.size - start).coerceAtLeast(0L)

    override val length: Long =
        if (requestedLength < 0) available else requestedLength.coerceAtMost(available)

    private var pos = 0L

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val remaining = length - pos
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
        System.arraycopy(data, (start + pos).toInt(), buf, off, n)
        pos += n
        return n
    }

    override suspend fun seek(position: Long) {
        pos = position.coerceIn(0L, length)
    }

    override fun position(): Long = pos

    override fun close() = Unit
}

/**
 * 假抽帧器：记录调用位置与调用次数，可注入延时与并发峰值统计。
 *
 * 同时实现 [MediaDurationProbe]，用于验证「10% → 1% → 25%」的换位重试。
 */
internal class FakeExtractor(
    private val delayMs: Long = 0,
    private val responder: (positionMs: Long, targetWidth: Int) -> ByteArray? = { _, _ -> null },
) : FrameExtractor, MediaDurationProbe {

    /** 每次 extract 的 positionMs，按调用顺序。 */
    val positions = CopyOnWriteArrayList<Long>()

    /** extract 调用总次数。 */
    val calls = AtomicInteger()

    /** 同时在 extract 内的协程数峰值（验证并发上限）。 */
    val peak = AtomicInteger()

    private val concurrent = AtomicInteger()

    /** [MediaDurationProbe.durationMs] 的返回值；null 表示探测不到时长。 */
    var durationHint: Long? = null

    /** 非空时 extract 直接抛出该异常（验证「抽帧异常不外泄」）。 */
    var throwOnExtract: Throwable? = null

    override suspend fun extract(
        source: RandomAccessSource,
        mimeHint: String?,
        positionMs: Long,
        targetWidth: Int,
    ): ByteArray? {
        calls.incrementAndGet()
        positions += positionMs
        val now = concurrent.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        try {
            if (delayMs > 0) delay(delayMs)
            throwOnExtract?.let { throw it }
            return responder(positionMs, targetWidth)
        } finally {
            concurrent.decrementAndGet()
        }
    }

    override suspend fun durationMs(source: RandomAccessSource, mimeHint: String?): Long? = durationHint
}
