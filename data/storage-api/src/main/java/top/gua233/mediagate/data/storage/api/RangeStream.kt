package io.github.gua123.mediagate.data.storage.api

import java.io.Closeable

/**
 * 一段可顺序读取、可定位的字节流（plan 4.1，R4 拖拽 seek / R11 抽帧与 PCM 解码）。
 *
 * 由 [StorageBackend.openRead] 产出：本地是 RandomAccessFile，WebDAV 是带 Range 的
 * HTTP 响应体，SFTP 是 SSH_FXP_READ 通道，FTP 是 REST + RETR 数据连接。
 *
 * 实现约定：
 * - [read] / [seek] 是挂起函数，实现内部必须切到 Dispatchers.IO，**不允许阻塞调用方线程**；
 * - 本接口不保证线程安全，同一实例同一时刻只应有一个协程在使用；
 * - 用完必须 [close]，否则会泄漏文件描述符或远端会话。
 */
interface RangeStream : Closeable {

    /**
     * 本流可读的总字节数（相对流的起点，而不是文件绝对长度）。
     *
     * -1 表示未知（例如不支持 Content-Length 的响应）；此时只能一路读到 [read] 返回 -1。
     */
    val length: Long

    /**
     * 读取最多 [len] 字节到 [buf] 的 [off] 处。
     *
     * @return 实际读到的字节数；已到流末尾返回 -1（与 InputStream.read 语义一致）。
     */
    suspend fun read(buf: ByteArray, off: Int, len: Int): Int

    /**
     * 定位到本流内的 [position]（0 = 本流起点，即 [StorageBackend.openRead] 的 offset 处）。
     *
     * 支持随机读的后端精确跳转；不支持的后端应抛 [StorageException.NotSupported]，
     * 由上层走分段缓存降级（plan 4.1）。
     */
    suspend fun seek(position: Long)

    /** 当前流内位置（0 = 本流起点）。 */
    fun position(): Long
}
