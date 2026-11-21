
package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.redactPath
import io.github.gua123.mediagate.core.common.redactUrl
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

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
    /**
     * 独立线程池 + SupervisorJob：**不能用 Dispatchers.IO，也不能随 close() 取消**。
     * 2026-11-18 真机实证：close() 里 cancel 掉这个 scope 后，后续所有 guard 的 async 都不再执行，
     * 每次调用只能等满硬超时（表现为「打开」15 秒超时、零字节、只有重启 App 才恢复）。
     *
     * **2026-11-21 恢复上限**：这里曾是 `Executors.newCachedThreadPool()`（0.1.112 为修
     * 「SFTP 大文件播不出」引入，上限 = Integer.MAX_VALUE）。每个存储操作 / **每一次读**都往这里
     * 投一个任务，真机进 /home/nas/movie（数百个视频）时 MG/storage-timeout 打到 464 任务/秒，
     * 线程数随之无上限增长 → bionic 建线程栈守卫页 mprotect 返回 ENOMEM → SIGABRT。
     * 现在用 [StorageDispatchers.hardDeadline]：形状照抄历史有界池（12/32 + SynchronousQueue，
     * 见 [StorageDispatchers]，历史实证线程数曾飙到 6287），**满员时在协程里挂起等许可**，
     * 既不开新线程、也不在提交线程内联执行（详见 [StorageDispatchers] 的注释）。
     *
     * 为什么"不在提交线程内联"对这里尤其要命：见 [withHardDeadline] 的注释——内联会让
     * `block()` 在 [withTimeout] 开始计时**之前**就跑起来，硬截止形同取消。
     */
    private val hardDeadlineScope = CoroutineScope(SupervisorJob() + StorageDispatchers.hardDeadline)

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> =
        guard("列目录 " + dir, "列目录 " + redactPath(dir)) { delegate.list(dir, page) }

    override suspend fun stat(path: String): RemoteEntry =
        guard("取属性 " + path, "取属性 " + redactPath(path)) { delegate.stat(path) }

    override suspend fun write(path: String, data: InputStream): Unit =
        guard("写入 " + path, "写入 " + redactPath(path)) { delegate.write(path, data) }

    override suspend fun probe(): ProbeReport = guard("探测 " + id, "探测 " + redactUrl(id)) { delegate.probe() }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream {
        val stream = guard("打开 " + path, "打开 " + redactPath(path)) { delegate.openRead(path, offset, length) }
        return TimeoutRangeStream(stream, readTimeoutMs, hardDeadlineScope)
    }

    override fun close() {
        // 关掉这个后端的硬截止作用域：仍在排队的操作不必再跑；已经在阻塞里的调用
        // 由底层 socket 超时收尾（线程无法被强杀）
        // 不 cancel：作用域随进程存活，避免后续调用永久超时（见 hardDeadlineScope 注释）
        delegate.close()
    }

    /**
     * @param what 完整文案（会进 [StorageException.Timeout] 的消息 → 界面用它，路径照旧，用户看自己的屏幕）
     * @param logText 日志文案（2026-11-18 日志脱敏：只有它进 AppLog 与崩溃报告的环形缓冲）
     */
    private suspend fun <T> guard(what: String, logText: String = what, block: suspend () -> T): T =
        try {
            withHardDeadline(hardDeadlineScope, operationTimeoutMs, what, logText, block)
        } catch (e: StorageException.Timeout) {
            // 硬超时只放弃等待，底层阻塞线程还活着（JSch 没有自己的超时）。
            // 通知后端作废会话，否则它会一直占着连接、把后续请求全拖死（2026-11-17 真机实证）。
            runCatching { delegate.onHardTimeout() }
            throw e
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
    logText: String = what,
    block: suspend () -> T,
): T {
    // 日志只打脱敏文案（logText）；what 留给异常消息（界面文案），两者互不干扰。
    //
    // **2026-11-21 采样**：真机进大目录时这里是每个**存储读**一条（提交 / 启动 / 结束 = 3 条），
    // 实测 94–464 条/秒。后果有两层：① logcat 自己变成负载（每行都要格式化 + 写 ring）；
    // ② AppLog 的环形缓冲只有 400 行，一秒就被冲干净——崩溃报告里本该说明"出事前在干什么"
    // 的时间线因此全是 worker 噪音。现在只保留一条、且每 [StorageTimeoutLog.SAMPLE_EVERY] 次采 1 条；
    // 超时（真正的异常）照旧全量 W 级打印。
    if (StorageTimeoutLog.shouldLog()) {
        AppLog.d(
            "storage-timeout",
            "worker 启动（每 " + StorageTimeoutLog.SAMPLE_EVERY + " 次采样 1 条）：" + logText,
        )
    }
    // **计时先开始、提交后发生**（2026-11-21 P0）：`scope.async` 必须在 [withTimeout] **之内**。
    // 旧写法把 async 放在外面——只要 dispatch 退化成"提交线程内联执行"（上一版池子用
    // CallerRunsPolicy 正是如此），block() 就在 async 调用里直接跑起来，而 withTimeout 还没开始计时，
    // 于是 ① 永不超时 ② onHardTimeout() 永不触发 ③ SFTP 会话永不作废 ④ 池更满，正反馈。
    // 现在两道保险：调度器满员只挂起不内联（见 StorageDispatchers）＋ async 建在超时作用域里面。
    var pending: Deferred<T>? = null
    try {
        return withTimeout(timeoutMs) {
            val worker = scope.async { block() }
            pending = worker
            worker.await()
        }
    } catch (e: TimeoutCancellationException) {
        // 顺序：TimeoutCancellationException 也是 CancellationException，先转成"超时"
        pending?.cancel()
        AppLog.w("storage-timeout", "硬超时（" + timeoutMs + " ms）：" + logText)
        throw StorageException.Timeout("等待超过 " + (timeoutMs / 1000) + " 秒：" + what)
    } catch (e: CancellationException) {
        // 调用方自己取消（换目录/退出页面）：把后台任务也放掉
        pending?.cancel()
        throw e
    }
}

/**
 * 存储层日志采样器（2026-11-21 真机 SIGABRT 之后）。
 *
 * 为什么要采样：`MG/storage-timeout` 是**每次存储读**都打点的地方，真机进大目录时
 * 94 / 137 / 190 / 464 / 223 条/秒（20 秒 732 条）。日志本身要格式化、要进 logcat、
 * 还要写 [io.github.gua123.mediagate.core.common.AppLog] 的 400 行环形缓冲——
 * ① 它自己就是负载；② 把环形缓冲一秒冲干净，崩溃报告里的时间线反而没用了。
 *
 * 口径：**每 [SAMPLE_EVERY] 次打 1 条**（约 3% 的量），异常（硬超时）不受采样影响。
 */
internal object StorageTimeoutLog {

    /** 采样间隔：每 32 次调用打 1 条。 */
    const val SAMPLE_EVERY: Int = 32

    private val counter = AtomicLong(0L)

    /** 这一次该不该打（第 1 次就打，便于开局确认打点还活着）。 */
    fun shouldLog(): Boolean = counter.incrementAndGet() % SAMPLE_EVERY == 1L

    /** 单测用：把计数归零，好数采样条数。 */
    fun reset() {
        counter.set(0L)
    }
}
