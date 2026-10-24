
package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
 * ⚠️ **2026-10-24 真机反馈「公网时返回上一个文件夹一直转圈」后的修正**：
 * 只把超时套在挂起调用上是**不够**的——底层是阻塞式网络调用时，协程取消
 * 打断不了它，超时会一直等到阻塞调用自己返回（实测：2000 ms 的阻塞调用套 100 ms 超时，
 * 实际 2059 ms 才返回）。所以这里改成**硬截止**：阻塞调用跑在一个独立作用域里，调用方到点就
 * 抛超时返回，绝不被它拖住；被放弃的调用由底层库自己的 socket 超时收尾（我们无法强杀线程）。
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

    /**
     * 阻塞调用的**宿主作用域**：与调用方（浏览页/播放器）解耦，
     * 于是调用方到点或取消时能立刻返回，不用等阻塞调用自己结束。
     */
    private val hardDeadlineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> =
        guard("列目录 " + dir) { delegate.list(dir, page) }

    override suspend fun stat(path: String): RemoteEntry = guard("取属性 " + path) { delegate.stat(path) }

    override suspend fun write(path: String, data: InputStream): Unit =
        guard("写入 " + path) { delegate.write(path, data) }

    override suspend fun probe(): ProbeReport = guard("探测 " + id) { delegate.probe() }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        val stream = guard("打开 " + path) { delegate.openRead(path, offset, length) }
        return TimeoutRangeStream(stream, readTimeoutMs, hardDeadlineScope)
    }

    override fun close() {
        // 关掉这个后端的硬截止作用域：仍在排队的操作不必再跑；已经在阻塞里的调用
        // 由底层 socket 超时收尾（线程无法被强杀）
        hardDeadlineScope.cancel()
        delegate.close()
    }

    private suspend fun <T> guard(what: String, block: suspend () -> T): T =
        withHardDeadline(hardDeadlineScope, operationTimeoutMs, what, block)

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
    private val deadlineScope: CoroutineScope,
) : RangeStream {

    override val length: Long get() = delegate.length

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int =
        withHardDeadline(deadlineScope, readTimeoutMs, "读取") { delegate.read(buf, off, len) }

    override suspend fun seek(position: Long) {
        withHardDeadline(deadlineScope, readTimeoutMs, "定位") { delegate.seek(position) }
    }

    override fun position(): Long = delegate.position()

    override fun close() = delegate.close()
}

/**
 * **硬截止**：把可能阻塞的调用放到 [scope] 里跑，调用方到点（或取消）立即返回。
 *
 * 为什么不用普通的超时包装：协程取消打断不了阻塞调用，
 * 超时会一直挂到阻塞调用自己返回（实测 2000 ms 阻塞 + 100 ms 超时 = 2059 ms）。
 * 这里的等待等的是另一个作用域里的 Deferred，超时只取消"等待"，不连累阻塞线程。
 *
 * 被放弃的调用**无法强杀**（协程取消对阻塞 socket 无效），它会由底层库自己的超时收尾；
 * 我们能保证的是：界面/播放器不会为它无限等待。
 */
internal suspend fun <T> withHardDeadline(
    scope: CoroutineScope,
    timeoutMs: Long,
    what: String,
    block: suspend () -> T,
): T {
    val worker = scope.async { block() }
    try {
        return withTimeout(timeoutMs) { worker.await() }
    } catch (e: TimeoutCancellationException) {
        // 顺序：TimeoutCancellationException 也是 CancellationException，先转成"超时"
        worker.cancel()
        AppLog.w("storage-timeout", "硬超时（" + timeoutMs + " ms）：" + what)
        throw StorageException.Timeout("等待超过 " + (timeoutMs / 1000) + " 秒：" + what)
    } catch (e: CancellationException) {
        // 调用方自己取消（换目录/退出页面）：把后台任务也放掉
        worker.cancel()
        throw e
    }
}
