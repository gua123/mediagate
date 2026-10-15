package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.data.storage.api.RandomAccessSource
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

/**
 * 自研引擎的**数据来源**接口：native 侧不自己开文件，而是通过它把读请求交回我们的数据层。
 *
 * 为什么要有这一层：全项目只认一个抽象——`StorageBackend → RandomAccessSource`
 *（plan 第 3 章「所有媒体数据只经过这一个抽象」）。引擎自己调 `fopen` 的话，
 * **SAF（content://）与 WebDAV/SFTP/FTP 全都播不了**，分段缓存、超时层、预取也都绕过去了。
 *
 * 实现约定：
 * - native 只在**播放线程**上调用它（同一个文件不会并发进来），但实现仍要能承受重入；
 * - [read] 是**阻塞**语义：native 会在读文件的线程上等它返回，所以实现内部可以放心阻塞；
 * - 返回负数的语义见 [read]。
 */
interface NativeMediaSource {

    /** 数据总长度（字节）；未知返回 -1。 */
    fun length(): Long

    /**
     * 从 [offset] 读最多 [size] 字节到 [buffer]（native 给的**直接缓冲**，写它就等于写进解码器）。
     *
     * @return 实际读到的字节数；已到末尾返回 **-1**；出错返回 **-2**（原因放 [lastError]）
     */
    fun read(offset: Long, buffer: ByteBuffer, size: Int): Int

    /**
     * 最近一次失败的**中文**原因（null＝没出错）。
     *
     * native 在读失败时会取它写进播放错误里——所以库抛的英文 message 必须在这里就翻成中文
     *（界面单语红线）。
     */
    fun lastError(): String?

    companion object {
        /** [read] 的返回码：到末尾。 */
        const val EOF: Int = -1

        /** [read] 的返回码：出错（详细原因看 [lastError]）。 */
        const val ERROR: Int = -2
    }
}

/**
 * 把我们的 [RandomAccessSource]（本地 / SAF / WebDAV / SFTP / FTP + 分段缓存 + 超时层）
 * 接到自研引擎上。
 *
 * 两个实现细节值得留意：
 * 1. [RandomAccessSource.readAt] 是挂起函数，而 native 侧要的是**阻塞**读 ⇒ 这里用
 *    `runBlocking` 把它同步化（native 的播放线程本来就该在这里等）；
 * 2. 每次读 32–64 KB、频率很高 ⇒ 复用一块中转数组，不为每次读分配一个新 ByteArray。
 *
 * **谁负责 close**：native 只借用（播放结束会丢掉引用），[RandomAccessSource] 由创建它的
 * 那一侧（M4 之后是播放会话）负责关闭；本类提供 [close] 只是方便调用方顺手复用。
 */
class RandomAccessMediaSource(
    private val source: RandomAccessSource,
    private val ownsSource: Boolean = true,
) : NativeMediaSource {

    private val lock = Any()
    private var scratch = ByteArray(0)

    @Volatile
    private var error: String? = null

    override fun length(): Long = source.size

    override fun read(offset: Long, buffer: ByteBuffer, size: Int): Int {
        if (size <= 0) return 0
        if (offset < 0) {
            error = "读取位置不合法（$offset）"
            return NativeMediaSource.ERROR
        }
        synchronized(lock) {
            val bytes = if (scratch.size >= size) scratch else ByteArray(size).also { scratch = it }
            val read = try {
                runBlocking { source.readAt(offset, bytes, 0, size) }
            } catch (t: Throwable) {
                error = ErrorText.of(t)
                return NativeMediaSource.ERROR
            }
            if (read <= 0) return read
            buffer.put(bytes, 0, read)
            return read
        }
    }

    override fun lastError(): String? = error

    /** 释放底层数据源（[ownsSource] 为 false 时只清自己的状态）。 */
    fun close() {
        if (!ownsSource) return
        runCatching { source.close() }
    }
}
