package io.github.gua123.mediagate.data.storage.api

/** 一个待取的字节区间（相对文件起点）。 */
data class ByteChunk(val offset: Long, val length: Long)

/**
 * 分段下载的"拆块"逻辑（**纯函数**，脱网单测）。
 *
 * **2026-10-03 用户提问**：「缓冲视频时能否采用多线程技术，这样会缓冲的快一点」。
 * 答案是可以，但要拆成两件不同的事：
 * 1. **同一段分块并发取**（本文件）——把 4 MB 的段拆成 4 个 1 MB 的 Range 请求同时发，
 *    对**高延迟 / 单连接被限速**的链路（公网 WebDAV、SFTP 单通道）提速明显：
 *    多个请求把 RTT 藏起来，也不再受单个 TCP 连接的窗口限制；
 * 2. **顺序播放本身不能并行读**——同一时刻的字节必须按序到达，所以"加速"只能来自"提前取"。
 *
 * 边界口径：块必须严格首尾相接、不重叠、不越界；最后一块可以是短的。
 */
fun chunkRanges(start: Long, length: Long, chunkBytes: Long): List<ByteChunk> {
    if (length <= 0L) return emptyList()
    val chunk = chunkBytes.coerceAtLeast(1L)
    val chunks = ArrayList<ByteChunk>((length / chunk + 1).toInt())
    var offset = start.coerceAtLeast(0L)
    var remaining = length
    while (remaining > 0L) {
        val take = minOf(chunk, remaining)
        chunks += ByteChunk(offset = offset, length = take)
        offset += take
        remaining -= take
    }
    return chunks
}

/** 默认块大小：1 MB（4 MB 的段拆 4 块并发取）。 */
const val DEFAULT_CHUNK_BYTES: Long = 1L * 1024 * 1024

/** 默认并发块数上限（别开太大：手机上行/服务端连接数都有限）。 */
const val DEFAULT_PARALLEL_CHUNKS: Int = 4

/** 默认预读段数（当前段之后顺手拉几段）。 */
const val DEFAULT_READ_AHEAD_SEGMENTS: Int = 2
