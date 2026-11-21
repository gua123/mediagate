package io.github.gua123.mediagate.data.storage.api

import io.github.gua123.mediagate.core.common.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * 存储层线程池（**2026-11-21 真机 SIGABRT 之后恢复有上限**）。
 *
 * ### 事故与形状
 *
 * 0.1.113 真机进 `/home/nas/movie`（数百个视频）后 3/3 在 4–40 秒内被 SIGABRT 杀死，
 * tombstone 只有 4 帧、全在 libc：
 *
 *     abort ← __init_additional_stacks ← __pthread_start ← __start_thread
 *     "signal stack guard mprotect(0x…, 16384) failed: Out of memory"
 *
 * 崩溃点是**新建线程**时连 16 KiB 的栈守卫页都切不出来（映射条目/线程数打满内核
 * `max_map_count`；注意 VmSize 16 GB 是 Scudo 主分配器的未 commit 预留，不是泄漏，与本崩无关）。
 * 崩溃前 MG/storage-timeout 的 `worker 启动：读取` 打到 464 条/秒——每个存储操作 / **每一次读**
 * 都往池里投一个任务。
 *
 * 形状直接**照抄历史**（8b65eb1 / v0.1.113 实测过的有界形态）：
 * `ThreadPoolExecutor(corePoolSize = 12, maximumPoolSize = 32, keepAlive = 30 s, SynchronousQueue,
 * AbortPolicy)`。8676fcc（0.1.113 之后的「SFTP 多连接加速」）把它换成了 `newCachedThreadPool`
 * （max = Integer.MAX_VALUE），「线程数上限」这道闸门被整体拆掉——那是第一次回归点。
 * 历史实证：8b65eb1 之前那版无上限池曾让线程数飙到 **6287**（预读失败 1900 条/秒、打开 600 次/秒）。
 *
 * ### 12 / 32 / 30 s 三个数字的来历（保留，不要"顺手优化"掉）
 *
 * - **core = 12**：OpenSSH 单会话 `MaxSessions = 10` + 2 余量。单会话后端（WebDAV/FTP/单连接 SFTP）
 *   真正能同时干活的就是 10 条通道，再多的常驻线程只是每线程 1 MiB 栈的空转。
 * - **max = 32**：真机跑过的量级上限。「SFTP 多连接」把预读窗口拉到 20 段，20 段 = 20 条在飞通道，
 *   32 留出播放读 / 元数据 / 落盘的位置；再多就只是把线程数往上堆，正是 SIGABRT 的燃料。
 * - **keepAlive = 30 s**：历史值；空闲线程 30 秒回收，平时不吃栈。
 * - **SynchronousQueue**：零容量 = 没有"排队等线程"的中间层，线程数就是并发数（可数、可封顶）。
 *
 * ### 第二次回归点：CallerRunsPolicy（本次修掉）
 *
 * 上一版把拒绝策略改成 [ThreadPoolExecutor.CallerRunsPolicy]，想用"背压"代替"抛异常"。但
 * `asCoroutineDispatcher().dispatch` 走的是 `executor.execute`：池满时 CallerRuns **在提交线程上
 * 内联跑阻塞体**，于是
 *
 * 1. **跑错线程**：Main（`rememberCoroutineScope` 调 `listCacheFolders`/`collectCacheTargets`）、
 *    `Dispatchers.Default`（`ioScope` 当 `readAheadScope`，该 scope 在 `AppContainer.kt:173`
 *    ——2026-11-21 起是 `Dispatchers.IO`，不再是 `Dispatchers.Default`）、代理/Media3 loader 线程都可能被塞进
 *    一次 JSch 阻塞读；
 * 2. **硬截止失效**：[TimeoutStorageBackend.withHardDeadline] 里 `scope.async { block() }` 的内联执行
 *    发生在 `withTimeout` 开始计时**之前**——`block()` 已经跑起来，计时器却还没启动 ⇒ 永不超时 ⇒
 *    `onHardTimeout()` 永不触发 ⇒ SFTP 会话不作废 ⇒ 池更满，正反馈。
 * 3. **池饱和并非病态**：当时每段预读要 2 个 io 线程（一个 `withContext(io){ job.awaitDone() }` 轮询占线程
 *    + 一个 256 KiB 读），20 段 = 40 > 32——满员是常态，不是极端情况。
 *    （等待后来改成 `MutableStateFlow` 挂起等待，"2 线程/段"这个历史口径已经不再成立；但"饱和是常态"
 *    这条判断仍然成立，闸门就是按它设计的。）
 *
 * ### 现在的形态：**许可闸门**（满员时等发生在协程里）
 *
 * 池子仍是 12/32/30 s/SynchronousQueue + AbortPolicy（历史形状，一字不改）；池**前面**加一道
 * [Semaphore] 闸门（许可数 = 池上限 = 32），由 [AdmissionDispatcher] 持有：
 *
 * - 有许可 → 直接投池（快路径，零额外协程）；
 * - 没许可 → **挂起提交方协程**（`Semaphore.acquire()` 是挂起，不占线程），拿到许可再投池；
 * - `dispatch` **永远不在提交线程上执行 `block`**——这是判据：满员时不得内联执行阻塞体。
 *
 * 于是 `withTimeout` 仍然有效（等待是真正的挂起，计时器照跑），超时后
 * `onHardTimeout()` 照常作废会话，正反馈被切断。
 *
 * ### 为什么不用 `Dispatchers.IO.limitedParallelism(32)`
 *
 * 它确实"最省事"，但会把 [io] 与 [hardDeadline] **并进同一个底层池**（Dispatchers.IO，默认 64 线程）：
 * 硬截止 worker 会**等** IO 池里的下层调用，合成一个池就是"线程全在等、没人干活"的自锁
 * （本类上一版注释已经写明这条）。两个物理分开的池 + 各自的许可闸门才能同时保住
 * 「线程有上限」「不许自锁」「不内联」三条。
 *
 * ### 许可的语义（2026-11-21 修正：旧注释写错了）
 *
 * **许可不是"被整个 `withContext(io){...}` 任务持有"，而是"一段不中断的执行片"。**
 * [GatedTask.run] 包的是 `DispatchedContinuation`：`DispatchedTask.run` 把协程推进到**第一个挂起点**
 * 就返回，`finally` 随即还许可。也就是说许可只覆盖"投池 → 首次挂起"这一片，
 * 协程在挂起点让出之后就不再持许可（醒来再经 [dispatch] 重新申请）。
 *
 * 按旧的错误模型会得出两个错结论：①"一次下载必然同时吃 2 个许可"（不成立：外层挂起时就还了）；
 * ②"许可会被长任务长期占着"（也不成立）。真正成立、也必须守住的只有下面两条铁律。
 *
 * ### 两条配套铁律（否则闸门会自锁）
 *
 * **① 禁止在持许可的阻塞段里用 `runBlocking` 桥接回 [io]。**
 * 许可按"执行片"发放，但**任何还活着、正在跑阻塞体的 mg-storage-io 线程都占着一格许可**。
 * 若这个阻塞体本身是 `runBlocking` 桥（回环代理的 socket 泵、Media3 的 `BackendDataSource`），
 * 它内部 `withContext(io)` 会再去要一格：32 个这样的线程同时等第二格 ⇒ 全部永久等待，
 * 闸门死锁（现象：播放卡死、却没有任何网络流量）。这条铁律写成断言 [assertNotOnStorageThread]，
 * 每个 `runBlocking` 桥接点都必须先调它。
 *
 * **② 磁盘落盘别去占许可。**
 * 落盘（输出流 / 改名 / 淘汰扫描）是本地 IO，一次下载要横跨整段传输（公网段几十秒）。
 * 它一旦落在 [io] 上，就等于把 32 格许可里的一格长期借给磁盘写，网络读的并发被白白挤掉一格。
 * 段下载因此把落盘循环放在应用 IO 池上（见 `SegmentedCacheBackend.runDownloadJob` 与
 * `SegmentedCacheBackend.downloadDispatcher`）。
 *
 * ### 为什么两个池
 *
 * [ioPool] 跑真正的存储 IO（磁盘/网络读写、段下载）；[deadlinePool] 给
 * [TimeoutStorageBackend] 的"硬截止 worker"用。两者必须分开：worker 会**等** IO 池，
 * 合成一个池就会出现"线程全在等、没人干活"的自锁。
 */
object StorageDispatchers {

    private const val TAG = "storage-dispatchers"

    /** [ioPool] 的线程名前缀（[assertNotOnStorageThread] 与 tombstone 复盘都靠它认线程）。 */
    const val IO_THREAD_PREFIX: String = "mg-storage-io"

    /** [deadlinePool] 的线程名前缀。 */
    const val DEADLINE_THREAD_PREFIX: String = "mg-storage-deadline"

    /** IO 池常驻线程数（8b65eb1 的历史值：OpenSSH 单会话 MaxSessions=10 + 2 余量）。 */
    const val IO_CORE_THREADS: Int = 12

    /** IO 池线程上限（历史值；真机跑过的量级，再多只是每线程 1 MiB 栈的空转）。 */
    const val IO_MAX_THREADS: Int = 32

    /** 硬截止池常驻线程数。 */
    const val DEADLINE_CORE_THREADS: Int = 12

    /** 硬截止池线程上限。 */
    const val DEADLINE_MAX_THREADS: Int = 32

    /** 空闲线程回收时间（历史值 30 s）。 */
    const val IDLE_KEEP_ALIVE_SECONDS: Long = 30L

    /** 下层存储 IO 池（**AbortPolicy**：池满由闸门挡在前面，正常情况下走不到拒绝）。 */
    val ioPool: ThreadPoolExecutor = boundedPool(
        name = IO_THREAD_PREFIX,
        coreThreads = IO_CORE_THREADS,
        maxThreads = IO_MAX_THREADS,
    )

    /** 硬截止池（[TimeoutStorageBackend] 的 worker）。 */
    val deadlinePool: ThreadPoolExecutor = boundedPool(
        name = DEADLINE_THREAD_PREFIX,
        coreThreads = DEADLINE_CORE_THREADS,
        maxThreads = DEADLINE_MAX_THREADS,
    )

    /** 存储层专用 IO 调度器：**有上限**，满员时在协程里挂起等（绝不内联到提交线程）。 */
    val io: CoroutineDispatcher = AdmissionDispatcher(ioPool, IO_MAX_THREADS)

    /** 硬截止调度器：给 [TimeoutStorageBackend] 的 worker 用（同样满员挂起）。 */
    val hardDeadline: CoroutineDispatcher = AdmissionDispatcher(deadlinePool, DEADLINE_MAX_THREADS)

    /** 诊断/单测快照（不扫线程栈，只读池的计数器与闸门许可）。 */
    fun ioFacts(): PoolFacts = facts("mg-storage-io", ioPool, IO_MAX_THREADS, io)

    /** 诊断/单测快照。 */
    fun deadlineFacts(): PoolFacts = facts("mg-storage-deadline", deadlinePool, DEADLINE_MAX_THREADS, hardDeadline)

    /**
     * **铁律断言（2026-11-21 第三轮审计 P2）：不许在持许可的阻塞段里 `runBlocking` 桥接回 [io]。**
     *
     * 许可按"一段不中断的执行片"发放（见类注释），但**任何正在跑阻塞体的存储池线程都占着一格许可**。
     * 若这个阻塞体本身是 [kotlinx.coroutines.runBlocking] 桥（回环代理的 socket 泵、Media3 的
     * `BackendDataSource`、抽帧器的 native 回调），它内部 `withContext(io)` 会再要一格：
     * 32 个这样的线程同时等第二格 ⇒ 全部永久等待，闸门死锁（播放卡死但**没有任何网络流量**）。
     *
     * 所以每个桥接点先调本方法：**跑在存储池线程上就是编程错误**，直接抛 [IllegalStateException]
     * （先把提示打进 [AppLog]，崩溃报告里能看到"是哪个桥接点"）。
     *
     * @param where 桥接点的名字（出现在日志与异常消息里，便于定位）。
     */
    fun assertNotOnStorageThread(where: String = "存储桥接点") {
        val name = Thread.currentThread().name
        if (!name.startsWith(IO_THREAD_PREFIX) && !name.startsWith(DEADLINE_THREAD_PREFIX)) return
        val message = where + " 正在存储池线程（" + name + "）上做阻塞桥接：持 io 许可的阻塞段里 " +
            "runBlocking 回 StorageDispatchers.io 会自己等自己（32 格许可全被占死 ⇒ 闸门死锁）。" +
            "把这段搬到 Dispatchers.IO（或背压到调用方协程）上再读。"
        AppLog.e(TAG, message)
        throw IllegalStateException(message)
    }

    /**
     * 一个池的静态口径 + 当前状态；[threadCeiling] / [admissionCeiling] 是**写死的**上限，
     * [liveThreads] 与 [inFlight] 只能小于等于它们。
     */
    data class PoolFacts(
        val name: String,
        val threadCeiling: Int,
        val liveThreads: Int,
        val largestThreads: Int,
        val queueCapacity: Int,
        val queuedTasks: Int,
        val rejectionPolicy: String,
        /** 闸门许可数（= 池线程上限）；超过就挂起等待而不是开新线程。 */
        val admissionCeiling: Int = 0,
        /** 当前持有许可（正在跑或正在等线程）的任务数；永不超过 [admissionCeiling]。 */
        val inFlight: Int = 0,
    )

    private fun facts(
        name: String,
        pool: ThreadPoolExecutor,
        ceiling: Int,
        dispatcher: CoroutineDispatcher,
    ) = PoolFacts(
        name = name,
        threadCeiling = ceiling,
        liveThreads = pool.poolSize,
        largestThreads = pool.largestPoolSize,
        queueCapacity = pool.queue.remainingCapacity() + pool.queue.size,
        queuedTasks = pool.queue.size,
        rejectionPolicy = pool.rejectedExecutionHandler.javaClass.simpleName,
        admissionCeiling = (dispatcher as? AdmissionDispatcher)?.ceiling ?: 0,
        inFlight = (dispatcher as? AdmissionDispatcher)?.inFlight ?: 0,
    )

    /**
     * 历史形状：core/max/keepAlive/SynchronousQueue 全部照抄 8b65eb1。
     *
     * 拒绝策略固定 [ThreadPoolExecutor.AbortPolicy]（默认值，这里写明白是为了让
     * `StorageDispatchersPoolBoundsTest` 与 tombstone 复盘都能一眼看到它）。
     */
    private fun boundedPool(name: String, coreThreads: Int, maxThreads: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(
            /* corePoolSize = */ coreThreads,
            /* maximumPoolSize = */ maxThreads,
            /* keepAliveTime = */ IDLE_KEEP_ALIVE_SECONDS,
            /* unit = */ TimeUnit.SECONDS,
            /* workQueue = */ SynchronousQueue(),
            /* threadFactory = */ { r -> Thread(r, name).apply { isDaemon = true } },
            /* handler = */ ThreadPoolExecutor.AbortPolicy(),
        )

    /**
     * **许可闸门调度器**：把"满员"从"在提交线程内联跑"改成"在协程里挂起等"。
     *
     * 结构：
     * - 快路径：`tryAcquire()` 成功 → `pool.execute`，任务跑完在 `finally` 里还许可；
     * - 慢路径：没许可（或池恰好握手不上）→ 交给 [waiters] 里的小协程 `acquire()`（挂起，
     *   不占线程），拿到许可再投池。**提交线程只做一次 tryAcquire 就返回**。
     *
     * 为什么还留着重投循环：`SynchronousQueue` 的 `offer` 是**非阻塞握手**——worker 刚跑完
     * 上一个任务、还没回到 `getTask()` 的那一瞬间，`execute` 会 `offer` 失败并尝试加线程，
     * 而线程已到 32，于是抛 `RejectedExecutionException`。这不是"池满"，是**毫秒级握手竞争**：
     * 许可已经攥在手里，让出 1 ms 重投即可（不新开线程、不内联、不丢任务）。
     */
    private class AdmissionDispatcher(
        private val pool: ThreadPoolExecutor,
        private val permits: Int,
    ) : CoroutineDispatcher() {

        /** 挂起等许可的小协程跑在这里：它们只做 acquire/重投，不跑业务体。 */
        private val waiters = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val gate = Semaphore(permits)

        /** 许可数（= 池线程上限）。 */
        val ceiling: Int get() = permits

        /** 当前持有许可的任务数。 */
        val inFlight: Int get() = permits - gate.availablePermits

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val task = GatedTask(context, gate, block)
            if (gate.tryAcquire()) {
                task.hold()
                if (submit(task)) return
            }
            // 没许可 / 握手失败：一律转"协程里等"，提交线程绝不在这里跑 block。
            waiters.launch { awaitAndSubmit(task) }
        }

        private suspend fun awaitAndSubmit(task: GatedTask) {
            if (!task.holdsPermit) {
                gate.acquire()
                task.hold()
            }
            while (!submit(task)) {
                if (pool.isShutdown) {
                    // 这两个池是进程级的，正常永远不关；真被关了也别把协程吊死。
                    task.releasePermit()
                    Dispatchers.IO.dispatch(task.context, task)
                    return
                }
                // SynchronousQueue 握手竞争（毫秒级）：让出后再投，许可仍攥在手里。
                delay(1)
                yield()
            }
        }

        /**
         * 投池。返回 false **只表示** [RejectedExecutionException]（`SynchronousQueue` 的毫秒级握手竞争），
         * 调用方会让出 1 ms 重投；许可仍攥在手里，不丢不重。
         *
         * **其它 Throwable 一律就地兜住（2026-11-21 第三轮审计 P2，防死锁）**：`execute` 抛的不只是 REE——
         * 线程创建失败（`OutOfMemoryError: unable to create native thread`）、`ThreadFactory` 自己抛
         * `Error`、换成自定义 handler 后 handler 抛别的运行时异常，都会从 `execute` 里穿出来。
         * 旧实现只 catch REE，于是异常**穿透 dispatch**（提交方协程被打挂 / 一帧都还没跑），
         * 更致命的是**许可已经 `hold()` 了却没人还**：漏够 32 个（= 池上限）就永久死锁。
         * 现在：先还许可，再转投 [Dispatchers.IO]（宁可多占一个应用 IO 线程，也不能把闸门卡死）。
         */
        private fun submit(task: GatedTask): Boolean = try {
            pool.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            false
        } catch (t: Throwable) {
            // 兜住非 REE：还许可（幂等，见 [GatedTask.releasePermit]）→ 转投应用 IO 池 → 记一条带异常日志
            task.releasePermit()
            AppLog.w(
                TAG,
                "存储池投递抛了非 RejectedExecutionException（已还许可并转投 IO 池）：" +
                    t.javaClass.name + ": " + t.message,
                t,
            )
            // dispatch 成功后 task.run() 的 finally 会再次 releasePermit —— CAS 保证只还一次
            Dispatchers.IO.dispatch(task.context, task)
            true
        }
    }

    /**
     * 带许可记账的 Runnable：**跑完（或被放弃）时还许可**，任何路径都不漏。
     *
     * 可见性是 internal 而不是 private：单测要直接钉"重复 [releasePermit] 只还一次"这条不变式
     * （见 `StorageDispatchersPermitTest`），这是闸门不漏许可的最底层保证。
     */
    internal class GatedTask(
        val context: CoroutineContext,
        private val gate: Semaphore,
        private val block: Runnable,
    ) : Runnable {

        /**
         * 是否持有许可。**必须是 CAS，不能是 `@Volatile Boolean` 的 check-then-act**
         * （2026-11-21 第三轮审计 P2）。
         *
         * 两条调用路径会并发碰到它：
         * - 任务被 `execute` 接受后 worker 开跑，`run()` 的 `finally` 还许可；
         * - 同一时刻投递方判定"投递失败"（自定义 handler 内联跑完之后又抛出 ⇒ [AdmissionDispatcher.submit]
         *   走非 REE 分支；或池被关停 ⇒ [AdmissionDispatcher.awaitAndSubmit] 的关停分支），也要还许可。
         *
         * 旧实现两个线程都读到 `true` 就会 `release()` 两次——许可**凭空多出来**，闸门从此形同虚设
         * （比泄漏更难查：池照样满，但闸门再也挡不住）。`compareAndSet` 天然幂等。
         */
        private val held = AtomicBoolean(false)

        val holdsPermit: Boolean get() = held.get()

        fun hold() {
            held.set(true)
        }

        /** 还许可；**只有真正从"持有"翻到"未持有"的那一次返回 true**。 */
        fun releasePermit(): Boolean {
            if (!held.compareAndSet(true, false)) return false
            gate.release()
            return true
        }

        override fun run() {
            try {
                block.run()
            } finally {
                releasePermit()
            }
        }
    }
}
