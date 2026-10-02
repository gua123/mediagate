
package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.InputStream

/**
 * **给任何后端套一层"超时保险"**（2026-10-03 真机反馈：「把软件放后台再返回时，整个软件好像未响应，
 * 网络测试/编辑点不开，浏览页无限刷新」）。
 *
 * 为什么需要：网络层的**阻塞式实现**（JSch 的 channel 读、某些 OkHttp 边界）在链路悄悄断掉之后
 * 可能一直等下去——超时设置未必覆盖所有路径（例如连接还在、但 socket 已经死了）。
 * 一旦某个操作永远不返回，**界面就会停在"刷新中"**，用户看到的就是"卡住了"。
 *
 * 口径：
 * - 每个**操作**（list / stat / write / probe）与每次**流读取**都有上限；
 * - 超时抛 [StorageException.Timeout]（界面已有中文文案与"重试"出口），绝不静默挂起；
 * - 超时**不**吞掉取消（协程取消照常向上传播）。
 *
 * @param delegate 被包装的后端。
 * @param operationTimeoutMs 单次元数据操作的上限（列目录 / 取属性 / 写 / 探测）。
 * @param readTimeoutMs 单次流读取的上限（比操作宽松：一次读可能要拉几 MB）。
 */
class TimeoutStorageBackend(
    private val delegate: StorageBackend,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) : StorageBackend {

    override val id: String get() = delegate.id

    override val caps: Caps get() = delegate.caps

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> =
        guard("列目录 " + dir) { delegate.list(dir, page) }

    override suspend fun stat(path: String): RemoteEntry = guard("取属性 " + path) { delegate.stat(path) }

    override suspend fun write(path: String, data: InputStream): Unit =
        guard("写入 " + path) { delegate.write(path, data) }

    override suspend fun probe(): ProbeReport = guard("探测 " + id) { delegate.probe() }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        val stream = guard("打开 " + path) { delegate.openRead(path, offset, length) }
        return TimeoutRangeStream(stream, readTimeoutMs)
    }

    override fun close() = delegate.close()

    private suspend fun <T> guard(what: String, block: suspend () -> T): T = try {
        withTimeout(operationTimeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        AppLog.w(TAG, "后端操作超时（" + operationTimeoutMs + " ms）：" + what)
        throw StorageException.Timeout("等待超过 " + (operationTimeoutMs / 1000) + " 秒：" + what)
    }

    companion object {
        private const val TAG = "storage-timeout"

        /** 元数据操作上限：手机上 15 秒还没结果，基本就是链路死了（浏览页也不该转圈更久）。 */
        const val DEFAULT_OPERATION_TIMEOUT_MS: Long = 15_000L

        /** 单次读上限：一次读可能拉几 MB，给宽松些。 */
        const val DEFAULT_READ_TIMEOUT_MS: Long = 30_000L
    }
}

/** 每次 [read] 都设上限的流包装（其余行为原样透传）。 */
internal class TimeoutRangeStream(
    private val delegate: RangeStream,
    private val readTimeoutMs: Long,
) : RangeStream {

    override val length: Long get() = delegate.length

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = try {
        withTimeout(readTimeoutMs) { delegate.read(buf, off, len) }
    } catch (e: TimeoutCancellationException) {
        // 注意顺序：TimeoutCancellationException 也是 CancellationException，
        // 必须先把它转成"超时"，否则会被下面那条当成"调用方取消"原样抛出去。
        throw StorageException.Timeout("读取超过 " + (readTimeoutMs / 1000) + " 秒未返回")
    } catch (e: CancellationException) {
        throw e
    }

    override suspend fun seek(position: Long) {
        try {
            withTimeout(readTimeoutMs) { delegate.seek(position) }
        } catch (e: TimeoutCancellationException) {
            throw StorageException.Timeout("定位超过 " + (readTimeoutMs / 1000) + " 秒未返回")
        } catch (e: CancellationException) {
            throw e
        }
    }

    override fun position(): Long = delegate.position()

    override fun close() = delegate.close()
}
