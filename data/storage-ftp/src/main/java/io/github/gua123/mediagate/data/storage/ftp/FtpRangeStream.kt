package io.github.gua123.mediagate.data.storage.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.IOException
import java.io.InputStream

/**
 * FTP 区间读流（**R4** 拖拽 seek / plan 4.1「RETR + REST，每次 seek 一个新 REST+RETR」）。
 *
 * 语义与降级链（plan 4.1）：
 * - **支持 REST**（`caps.randomAccess = true`）：每次定位都发 `REST offset` + `RETR`，
 *   服务器直接从该偏移开始发数据——不是「从 0 拉完再丢掉」；
 * - **不支持 REST**（服务器对 REST 回 500/502，探测到即把 [FtpStorageBackend.caps] 降级）：
 *   从 0 开始 RETR 后本地顺序跳过 offset 字节；此时 [seek] 抛
 *   [StorageException.NotSupported]，由上层改走分段缓存。
 *
 * 控制连接的处置很讲究：FTP 一次传输期间控制连接被独占，**中途放弃传输会让应答序列错位**，
 * 所以「读到 EOF 的正常收尾」才把连接还池；「中途 seek 走人 / 读出错」一律断掉换新的。
 *
 * @param config 连接配置（错误分类时给中文上下文用）。
 * @param remotePath 远端绝对路径。
 * @param start 本流在文件里的起始绝对偏移（[position] 的 0 点）。
 * @param length 本流可读长度；-1 表示读到文件末尾。
 * @param path 树内路径（只用于错误消息）。
 * @param restSupported 当前是否认为服务器支持 REST（每次取连接时问一次，可能已降级）。
 * @param onRestUnsupported REST 被服务器拒绝时的回调（把 caps 降级并让上层走缓存）。
 */
internal class FtpRangeStream(
    private val pool: FtpClientPool,
    private val config: FtpConfig,
    private val remotePath: String,
    private val start: Long,
    override val length: Long,
    private val path: String,
    private val restSupported: () -> Boolean,
    private val onRestUnsupported: () -> Unit,
) : RangeStream {

    private var pos: Long = 0L

    private var lease: FtpClientLease? = null

    private var input: InputStream? = null

    /** 当前数据流对应的文件绝对偏移；没有打开时为 -1。 */
    private var inputOffset: Long = -1L

    /** 当前传输是否已经读到 EOF（读到 EOF 才允许把控制连接还池）。 */
    private var inputAtEof: Boolean = false

    @Volatile
    private var closed = false

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = withContext(Dispatchers.IO) {
        if (closed) return@withContext -1
        if (len == 0) return@withContext 0
        val remaining = remaining()
        if (remaining <= 0L) return@withContext -1
        val want = minOf(len.toLong(), remaining).toInt()
        var result = -1
        var done = false
        var attempt = 0
        while (!done) {
            val stream = ensureInput()
            if (stream == null) {
                result = -1
                done = true
            } else {
                try {
                    val read = stream.read(buf, off, want)
                    if (read < 0) {
                        inputAtEof = true
                        releaseTransfer(discard = false)
                        result = -1
                    } else {
                        pos += read
                        result = read
                    }
                    done = true
                } catch (e: IOException) {
                    // 数据连接断了：丢掉这条控制连接，按同一绝对偏移重开一次（切网/服务端踢人）
                    releaseTransfer(discard = true)
                    if (attempt++ >= MAX_READ_RETRY) {
                        throw StorageException.Network("FTP 传输中断：" + path + "（" + e.javaClass.simpleName + "）", e)
                    }
                }
            }
        }
        result
    }

    /**
     * 定位到本流内的 [position]。
     *
     * 支持 REST 时是「丢掉旧传输 + 记住新偏移」，真正的 `REST offset` 在下一次 [read] 发出；
     * 不支持 REST 时直接抛 [StorageException.NotSupported]（plan 4.1 的降级链交给上层）。
     */
    override suspend fun seek(position: Long) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        val target = if (length < 0L) position.coerceAtLeast(0L) else position.coerceIn(0L, length)
        if (target == pos) return@withContext
        if (!restSupported()) {
            throw StorageException.NotSupported("服务器不支持 REST，无法定位到 " + target + "：" + path)
        }
        releaseTransfer(discard = true)
        pos = target
    }

    override fun position(): Long = pos

    override fun close() {
        if (closed) return
        closed = true
        releaseTransfer(discard = !inputAtEof)
    }

    // ------------------------------------------------------------------ 内部

    private fun remaining(): Long = if (length < 0L) Long.MAX_VALUE else length - pos

    /** 确保有一条**正好定位在 [start] + [pos]** 的数据流；已到末尾返回 null。 */
    private suspend fun ensureInput(): InputStream? {
        if (remaining() <= 0L) return null
        val absolute = start + pos
        val current = input
        if (current != null && inputOffset == absolute && !inputAtEof) return current
        releaseTransfer(discard = !inputAtEof)
        val newLease = pool.acquire()
        return try {
            val stream = openTransfer(newLease, absolute)
            lease = newLease
            input = stream
            inputOffset = absolute
            inputAtEof = false
            stream
        } catch (t: Throwable) {
            newLease.release(discard = true)
            throw t
        }
    }

    /**
     * 在 [lease] 上按绝对偏移 [absolute] 打开数据流。
     *
     * 先试 `REST absolute` + `RETR`；服务器拒绝 REST（应答码不是 550 之类）就降级为
     * 「从 0 开始 RETR + 本地顺序跳过」——这正是 plan 4.1 的降级链，也解释了为什么
     * 降级后 [caps] 会把 `randomAccess` 标成 false。
     */
    private fun openTransfer(lease: FtpClientLease, absolute: Long): InputStream {
        val client = lease.client
        if (absolute > 0L && restSupported()) {
            client.setRestartOffset(absolute)
            val stream = client.retrieveFileStream(remotePath)
            if (stream != null) return stream
            val code = client.replyCode
            // 550/553 是文件本身的问题（不存在/无权限），不能当作「不支持 REST」
            if (code != 550 && code != 553) {
                onRestUnsupported()
            } else {
                throw classifyReply(client, config, path)
            }
        }
        val stream = client.retrieveFileStream(remotePath)
            ?: throw classifyReply(client, config, path)
        if (absolute > 0L) {
            skipFully(stream, absolute)
        }
        return stream
    }

    /** 收掉当前传输：读到 EOF 就正常收尾并还池，否则断掉换新连接。 */
    private fun releaseTransfer(discard: Boolean) {
        val stream = input
        input = null
        inputOffset = -1L
        val atEof = inputAtEof
        inputAtEof = false
        val current = lease
        lease = null
        if (stream != null) {
            runCatching { stream.close() }
        }
        if (current == null) return
        if (discard || !atEof) {
            // 中途放弃：控制连接的应答序列已不可信，直接丢弃（下一次会重连）
            current.release(discard = true)
        } else {
            val finished = runCatching { current.client.completePendingCommand() }.getOrDefault(false)
            current.release(discard = !finished)
        }
    }

    /** 本地顺序跳过 [bytes] 字节（不支持 REST 时的降级路径）。 */
    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(SKIP_BUFFER_SIZE)
        while (remaining > 0L) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = stream.read(buffer, 0, want)
            if (read < 0) {
                // 文件比 offset 短：后面 read 会直接返回 -1（与本地后端越界语义一致）
                return
            }
            remaining -= read
        }
    }

    private companion object {
        const val MAX_READ_RETRY = 1
        const val SKIP_BUFFER_SIZE = 64 * 1024
    }
}
