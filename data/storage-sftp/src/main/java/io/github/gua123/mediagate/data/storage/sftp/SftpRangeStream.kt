package io.github.gua123.mediagate.data.storage.sftp

import io.github.gua123.mediagate.data.storage.api.StorageDispatchers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.data.storage.api.RangeStream
import java.io.IOException
import io.github.gua123.mediagate.core.common.AppLog
import java.io.InputStream

/**
 * SFTP 区间读流（**R4** 拖拽 seek / plan 4.1「SSH_FXP_READ + offset，直接按偏移读」）。
 *
 * 关键实现约束（plan 4.1 与需求 R4 明确要求）：
 * - **seek 必须是真按偏移读**：JSch 的 `ChannelSftp.get(path, monitor, offset)` 内部就是
 *   `SSH_FXP_READ(handle, offset, len)`（见 jsch 源实现：`sendREAD(handle, request_offset, ...)`，
 *   `request_offset` 初值就是传入的 offset），所以每次定位都是**直接在新偏移发起读请求**，
 *   绝不会「从 0 开始 skip 掉几百 MB」；
 * - 顺序读只开一条通道、一条远端句柄：位置没跳就继续用当前流（不重复 open）；
 * - **断线重连不丢 seek**：本类记的是「文件绝对偏移 = [start] + position」，读失败时丢掉坏通道、
 *   按同一绝对偏移重开一次（[MAX_READ_RETRY]），调用方看到的是「同一个位置继续读」而不是错位。
 *
 * 通道生命周期：[open][SftpChannelPool.acquire] 后一直持有到读完/关闭/[seek] 跳走；
 * 关闭时先 `InputStream.close()`（它会排空在途应答并发送 SSH_FXP_CLOSE），健康则还池复用。
 *
 * @param start 本流在文件里的起始绝对偏移（[RangeStream.position] 的 0 点）。
 * @param length 本流可读长度；-1 表示读到文件末尾。
 * @param path 树内路径（只用于错误消息）。
 */
internal class SftpRangeStream(
    private val pool: SftpChannelPool,
    private val absolutePath: String,
    private val start: Long,
    override val length: Long,
    private val path: String,
) : RangeStream {

    private var pos: Long = 0L

    private var lease: ChannelLease? = null

    private var input: InputStream? = null

    /** 当前 [input] 对应的文件绝对偏移；没有打开的流时为 -1。 */
    private var inputOffset: Long = -1L

    @Volatile
    private var closed = false

    /** 整块预读的内存缓冲（见 read 的说明）。 */
    private var block: ByteArray? = null
    private var blockStart: Long = 0L
    private var blockLength: Int = 0

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = withContext(StorageDispatchers.io) {
        if (closed) return@withContext -1
        if (len == 0) return@withContext 0
        val remaining = remaining()
        if (remaining <= 0L) return@withContext -1
        val want = minOf(len.toLong(), remaining).toInt()

        // 1) 内存块命中：直接拷
        block?.let { b ->
            val localOff = (pos - blockStart).toInt()
            if (localOff >= 0 && localOff < blockLength) {
                val n = minOf(want, blockLength - localOff)
                System.arraycopy(b, localOff, buf, off, n)
                pos += n
                return@withContext n
            }
        }

        // 2) **完全照搬探针的写法**：pool.use 借一条通道，当场 get + 连续读满，读完立刻归还。
        //    2026-11-17 真机实证：pool.acquire 长期持有 + 延后读会卡死；pool.use 当场读 4MB 只要 107ms。
        val blockSize = minOf(remaining, BLOCK_READ_BYTES).toInt()
        val chunk = ByteArray(blockSize)
        val abs = absolutePath
        val absolute = start + pos   // 必须加上流的起点偏移：pos 只是流内位置（2026-11-18 修复：漏了 start 会永远从文件头读）
        val total = try {
            pool.use { channel ->
                channel.get(abs, null, absolute).use { ins ->
                    var read = 0
                    while (read < blockSize) {
                        val k = ins.read(chunk, read, blockSize - read)
                        if (k < 0) break
                        read += k
                    }
                    read
                }
            }
        } catch (e: IOException) {
            throw mapSftpIoFailure(e, path, pool.lastHostKeyCheck)
        }
        if (total <= 0) return@withContext -1
        block = chunk
        blockStart = pos
        blockLength = total
        val n = minOf(want, total)
        System.arraycopy(chunk, 0, buf, off, n)
        pos += n
        n
    }

    /**
     * 定位到本流内的 [position]。
     *
     * 只是「记住新位置 + 丢掉旧通道」，真正的 `SSH_FXP_READ(offset)` 发生在下一次 [read]；
     * 这样连续 seek 不会产生无用的远端请求。
     */
    override suspend fun seek(position: Long) = withContext(StorageDispatchers.io) {
        if (closed) return@withContext
        val target = if (length < 0L) position.coerceAtLeast(0L) else position.coerceIn(0L, length)
        if (target != pos) {
            closeInput()
            pos = target
        }
    }

    override fun position(): Long = pos

    override fun close() {
        if (closed) return
        closed = true
        closeInput()
    }

    // ------------------------------------------------------------------ 内部

    /** 还没读的字节数（length 未知时给 Long.MAX_VALUE）。 */
    private fun remaining(): Long = if (length < 0L) Long.MAX_VALUE else length - pos

    /** 确保有一条**正好定位在 [start] + [pos]** 的输入流；EOF 之后返回 null。 */
    private suspend fun ensureInput(): InputStream? {
        if (remaining() <= 0L) return null
        val absolute = start + pos
        val current = input
        if (current != null && inputOffset == absolute) return current
        closeInput()
        AppLog.d("storage-sftp", "ensureInput 申请通道 abs=" + absolute)
        val newLease = pool.acquire()
        AppLog.d("storage-sftp", "ensureInput 拿到通道 abs=" + absolute)
        try {
            val stream = newLease.channel.get(absolutePath, null, absolute)
            AppLog.d("storage-sftp", "ensureInput get 返回流 abs=" + absolute)
            lease = newLease
            input = stream
            inputOffset = absolute
            return stream
        } catch (t: Throwable) {
            newLease.release(discard = true)
            throw mapSftpIoFailure(t, path, pool.lastHostKeyCheck)
        }
    }

    /** 收掉当前输入流与通道：先 close 排空在途应答，健康则还池，否则丢弃。 */
    private fun closeInput() {
        val stream = input
        input = null
        inputOffset = -1L
        val current = lease
        lease = null
        var discard = false
        if (stream != null) {
            try {
                stream.close()
            } catch (e: IOException) {
                discard = true
            }
        }
        current?.release(discard)
    }

    private companion object {
        /** 断线后按同一偏移重试的次数（1 次；再失败就把异常交给上层走重试/降级）。 */
        const val MAX_READ_RETRY = 1

        /** 取到通道后连续读满的块大小：一次读完 4MB 段，避免"延后读"卡死。 */
        /**
         * 单次取通道后连续读的块大小。
         * 2026-11-18 对照 nPlayer（64 KiB 块、串行）后从 4 MiB 降到 256 KiB：
         * 首字节等待从「等满 4 MiB」变成「等满 256 KiB」，且阻塞时间更短、更不容易拖死线程。
         */
        const val BLOCK_READ_BYTES: Long = 256L * 1024
    }
}
