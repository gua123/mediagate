package io.github.gua123.mediagate.data.storage.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把顺序型的 [RangeStream] 适配成 [RandomAccessSource]（plan 4.1 的小工具）。
 *
 * 用途：某些后端只给得出「可 seek 的流」而没有原生 read-at 接口时，用它在数据层
 * 补齐 [RandomAccessSource]，让上层（播放 / 抽帧 / 图片解码）只面对一种抽象。
 *
 * 代价：内部用一把 [Mutex] 把「seek + read」串起来，**并发读性能不如后端原生实现**；
 * 有原生 read-at 的后端应直接实现 [RandomAccessSource]。
 *
 * @param knownSize 数据总长度；默认取 [RangeStream.length]，未知传 -1。
 */
fun RangeStream.asRandomAccessSource(knownSize: Long = length): RandomAccessSource =
    RangeStreamRandomAccessSource(this, knownSize)

private class RangeStreamRandomAccessSource(
    private val stream: RangeStream,
    override val size: Long,
) : RandomAccessSource {

    private val mutex = Mutex()

    override suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = mutex.withLock {
        if (len == 0) return@withLock 0
        if (offset < 0) throw StorageException.Unknown("offset 不能为负：$offset")
        if (size >= 0 && offset >= size) return@withLock -1
        stream.seek(offset)
        stream.read(buf, off, len)
    }

    override suspend fun readFully(offset: Long, len: Int): ByteArray = mutex.withLock {
        if (len <= 0) return@withLock ByteArray(0)
        if (size >= 0 && offset >= size) return@withLock ByteArray(0)
        stream.seek(offset)
        val out = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = stream.read(out, read, len - read)
            if (n < 0) break
            read += n
        }
        if (read == len) out else out.copyOf(read)
    }

    override fun close() = stream.close()
}
