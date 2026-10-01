package io.github.gua123.mediagate.data.storage.api

import java.io.Closeable

/**
 * 随机访问源（plan 第 3 章「不变量」）：**所有媒体数据只经过这一个抽象**。
 *
 * 播放（Media3 DataSource、LibVLC 的回环代理）、视频抽帧（MediaDataSource）、
 * 图片解码、ASR 取音，都消费同一份 [RandomAccessSource]；这样「能随机读 = 能拖拽」
 * 只需要在数据层实现一次（R4）。
 *
 * 实现约定：
 * - [readAt] / [readFully] 内部必须切到 Dispatchers.IO；
 * - 实现需自带并发保护（多个读者并发读同一文件是常态：播放 + 抽帧）；
 * - 用完必须 [close]。
 */
interface RandomAccessSource : Closeable {

    /** 数据总长度（字节）；未知为 -1。 */
    val size: Long

    /**
     * 从 [offset] 处读取最多 [len] 字节到 [buf] 的 [off] 处。
     *
     * @return 实际读到的字节数；[offset] 已到末尾返回 -1。
     */
    suspend fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int

    /**
     * 从 [offset] 处读取 [len] 字节。
     *
     * 读到末尾时返回的数组可能短于 [len]（不抛异常）；[offset] 已到末尾则返回空数组。
     */
    suspend fun readFully(offset: Long, len: Int): ByteArray
}
