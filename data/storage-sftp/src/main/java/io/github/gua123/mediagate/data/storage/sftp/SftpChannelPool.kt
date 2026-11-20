package io.github.gua123.mediagate.data.storage.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.redactEndpoint
import io.github.gua123.mediagate.core.common.redactHostInText
import io.github.gua123.mediagate.core.common.redactUserInText
import io.github.gua123.mediagate.data.storage.api.StorageDispatchers
import io.github.gua123.mediagate.data.storage.api.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * SFTP 会话 / 通道池（**R2** / **R4** / plan 4.1「单会话复用 + 通道池」，2026-11-20 扩成多连接）。
 *
 * ## 为什么要多连接
 * 单个 SSH 连接在公网上只能跑到 2.2 MB/s 左右（用户 2026-11-20 实测），而
 * **多连接能叠加**：2026-11-18 开发机实测 20 连接 × 10 通道 = 200 通道，公网 23-25 MB/s、零失败。
 * 一条连接最多 10 条 SFTP 通道（OpenSSH `MaxSessions` 默认 10，第 11 条报
 * `channel is not opened`），所以「要多几条通道」= 「要多几条 TCP 会话」。
 *
 * ## 结构
 * - **车道（[Lane]）= 一条 SSH 会话 + 它的 `channelsPerSession` 个通道位**。
 *   车道数上限 = [SftpConfig.maxSessions]（App 默认 20），每条会话的通道位上
 *   限 = [SftpConfig.channelsPerSession]（10）。
 * - **懒加载**：池子启动时**一条会话都不建**；第一个读请求建 1 条，通道位不够（>10 路并发）才再扩。
 *   **播放信号**（同一文件 ≥3 路并发读，见 SftpStorageBackend.openRead）才会触发后台并行扩建，
 *   目标 = 大文件 20 条 / 小文件 2 条（[SftpConfig.sessionsForSize] + [planForSize] + [ensureSessionRamp]）；
 *   缩略图 / 目录只有 1-2 路并发，永远不会为它们建 20 条（用户 2026-11-18 拍板的口径）。
 * - **并行建连**：扩容走 [SftpRamp.warmUp]（`coroutineScope + async` 一批并发发出去），
 *   20 条会话是「几批并行」而不是「一条接一条 18 秒」（2026-11-18 实测串行 0.9 s/条）。
 * - **借还**：一条通道 = 一个 [Semaphore] 许可。借出时 round-robin 摊到各条会话上
 *   （每条 TCP 连接都能并行吃数据），归还时放回**那条会话**的空闲队列复用。
 * - **metadata 独立会话**：list / stat 走 [useMetadata]（另一条 SSH 会话上的临时通道，
 *   不占数据通道位）。JSch 每条 Session 只有一个 reader 线程，一条卡死的通道会把
 *   同一会话上的所有通道一起堵死——元数据必须物理隔离（2026-11-15 真机 #demo 实证）。
 *
 * ## 已知坑（都在代码里显式处理）
 * - **固定池 + 永不返回的任务 = 池迟早死**（2026-11-18：12 线程被 JSch 阻塞读占满）：
 *   借通道有`acquireTimeoutMs` 硬上限，超时抛 [StorageException.Timeout]，不会无限排队。
 * - **硬超时必须作废资源**：[invalidateCurrentSession] 丢掉**所有**车道（连同借出去的通道），
 *   下一次请求自动重连。许可池**不整体重置**——每个车道自带 Semaphore，作废的车道整个丢掉，
 *   旧租约归还到死车道不会污染新池（旧实现重置全局 Semaphore 会把在飞租约的许可弄丢）。
 * - **建连失败要退避**：每个车道独立指数退避（0.5 s → 8 s），退避期内直接抛上次的错；
 *   连续失败 3 次后 [acquire] 直接失败，不对着掉线的服务器无限重试。
 *
 * 线程模型：所有 JSch 调用都是阻塞 I/O，一律在 [StorageDispatchers.io] 上执行；
 * 并发上限用协程 [Semaphore] 控制，调用方挂起而不是阻塞线程。
 */
internal class SftpChannelPool(
    private val config: SftpConfig,
    private val verifier: HostKeyVerifier,
    val timing: TimingSocketFactory = TimingSocketFactory(config.connectTimeoutMs.toInt()),
    /** 后台扩容 / 缩容用的作用域（单测可注入，便于断言"没有后台泄漏"）。 */
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + StorageDispatchers.io),
) : Closeable {

    private val hostKeys = JschHostKeyRepository(config.host, config.port, verifier)

    private val jsch = JSch().apply { setHostKeyRepository(hostKeys) }

    /** 现役车道；由 [stateLock] 保护（只在改列表时短暂持锁，建连不在锁内）。 */
    private val lanes = ArrayList<Lane>()
    private val stateLock = Any()

    /** 上一次发通道位给哪条车道（round-robin 的游标）。 */
    private var lastLaneIndex = -1

    /**
     * 目标会话数（懒加载的**上限**，不是「立刻建这么多」）。
     * 初值 1：**池子建出来时一条会话都不建**；第一个读请求建 1 条，
     * 之后按 [planForSize]（小文件 2 条 / 大文件开满）与并发压力增长。
     */
    @Volatile
    private var targetSessions: Int = 1

    @Volatile
    private var closed = false

    /** 「有通道归还 / 有新车道」的唤醒信号（CONFLATED：只唤醒，不传数据）。 */
    private val slotSignal = Channel<Unit>(Channel.CONFLATED)

    /** 连续建连失败次数（[MAX_CONSECUTIVE_FAILURES] 之后直接失败，别无限重试）。 */
    private val connectFailures = AtomicInteger(0)

    private var rampJob: Job? = null
    private var trimJob: Job? = null
    private val trimScheduled = AtomicBoolean(false)

    /**
     * **元数据专用 SSH 会话**（2026-11-18 起与数据会话物理隔离）。
     *
     * 原因：JSch 每个 Session 只有一个 reader 线程，一条通道不消费就会把整条会话上的
     * 所有通道一起堵死（head-of-line blocking）。数据读与 list/stat 分开后，
     * 播放卡住也不会让目录打不开，反之亦然。
     */
    private val metaSessionRef = AtomicReference<Session?>(null)
    private val metaSessionLock = Any()

    /** 元数据临时通道的并发上限（同一个会话上也别超过服务器 MaxSessions）。 */
    private val metaPermits = Semaphore(config.metadataChannels)

    init {
        config.privateKey?.let { key ->
            jsch.addIdentity(key.name, key.privateKey, key.publicKey, key.passphrase?.toByteArray())
        }
    }

    /** 最近一次主机密钥校验结论（异常翻译时取指纹用）。 */
    val lastHostKeyCheck: HostKeyCheck? get() = hostKeys.lastCheck.get()

    /** 当前是否已有活着的会话（诊断/单测用）。 */
    val hasLiveSession: Boolean get() = synchronized(stateLock) { lanes.any { it.connected } }

    /** 当前空闲通道数（单测用）。 */
    val idleChannels: Int get() = synchronized(stateLock) { lanes.sumOf { it.idleCount } }

    /** 现役会话条数（诊断/单测用）。 */
    internal val sessionCount: Int get() = synchronized(stateLock) { lanes.size }

    /** 当前目标会话数（诊断/单测用：小文件 2、大文件开满）。 */
    internal val targetSessionCount: Int get() = targetSessions

    /** 池的即时快照（诊断/单测用）。 */
    internal fun stats(): SftpPoolStats = synchronized(stateLock) {
        SftpPoolStats(
            sessions = lanes.size,
            targetSessions = targetSessions,
            capacity = config.channelCapacity,
            leasedChannels = lanes.sumOf { it.leased },
            idleChannels = lanes.sumOf { it.idleCount },
            connectFailures = connectFailures.get(),
        )
    }

    /**
     * 借一个通道执行 [block]，结束后归还。
     *
     * [block] 内部发生任何异常都会把这条通道标记为「脏」并丢弃（不还池），
     * 避免把状态未知的通道交给下一个使用者。
     */
    suspend fun <T> use(block: (ChannelSftp) -> T): T {
        val lease = acquire()
        var healthy = false
        try {
            val result = block(lease.channel)
            healthy = true
            return result
        } finally {
            lease.release(discard = !healthy)
        }
    }

    /**
     * 借一个通道并**持有**它（给 [SftpRangeStream] 用：一条流占一条通道直到读完/关闭）。
     *
     * @throws StorageException 池已关闭、建连失败，或等待超过 [SftpConfig.acquireTimeoutMs]。
     */
    suspend fun acquire(): ChannelLease {
        if (closed) throw StorageException.Unknown("后端已关闭：" + config.id)
        val deadlineNs = System.nanoTime() + config.acquireTimeoutMs * 1_000_000L
        var firstError: Throwable? = null
        var attempts = 0
        while (true) {
            if (closed) throw StorageException.Unknown("后端已关闭：" + config.id)
            val lane = reserveLane()
            if (lane != null) {
                try {
                    val channel = lane.openChannel()
                    return ChannelLease(lane, channel)
                } catch (e: CancellationException) {
                    lane.giveBackSlot()
                    throw e
                } catch (t: Throwable) {
                    lane.giveBackSlot()
                    if (firstError == null) firstError = t
                    attempts += 1
                    // 认证 / 主机密钥 / 权限问题重试多少次都一样：立刻交给调用方
                    if (t is StorageException.Auth || t is StorageException.AccessDenied) throw t
                    // 一条车道坏了换下一条（round-robin），但**别在死服务器上原地打转**：
                    // 三次都建不起来立刻失败（退避状态留在车道里，下次请求很快失败而不是再握手一次）
                    if (attempts >= MAX_ACQUIRE_ATTEMPTS) throw firstError
                }
            } else if (growIfNeeded()) {
                // 新车道已入列，下一轮就能借到它（它的建连由借到它的人负责）
                continue
            }
            val leftNs = deadlineNs - System.nanoTime()
            if (leftNs <= 0L) {
                throw StorageException.Timeout(
                    "等待 SFTP 通道超过 " + (config.acquireTimeoutMs / 1000L) + " 秒：" +
                        (firstError?.message ?: config.id),
                )
            }
            // 等「有通道归还 / 有新车道」，最多睡一个切片；醒来重试（不忙等）
            withTimeoutOrNull(minOf(leftNs / 1_000_000L, WAIT_SLICE_MS)) { slotSignal.receive() }
        }
    }

    /**
     * **元数据专用通道**：不占数据通道位，在同一 SSH 会话上新开一条通道，用完即断。
     *
     * 为什么需要：VLC 每个 Range 请求前都会 stat；播放中的数据流会把通道位全部占住，
     * stat 等 15 s 超时 → 回环代理 504 → VLC 拿不到 MP4 尾部 moov（2026-11-15 真机 #demo 实证）。
     * 元数据不能被数据流饿死。
     */
    suspend fun <T> useMetadata(block: (ChannelSftp) -> T): T = withContext(StorageDispatchers.io) {
        metaPermits.acquire()
        try {
            val channel = openEphemeralChannel()
            try {
                block(channel)
            } finally {
                runCatching { channel.disconnect() }
            }
        } finally {
            metaPermits.release()
        }
    }

    /**
     * **按文件大小定会话目标**（懒加载的"最多开几条"）。
     *
     * - 小文件（< [SftpConfig.largeFileBytes]）/ 大小未知 → [SftpConfig.smallFileSessions]（2 条）；
     * - 大文件 → [SftpConfig.maxSessions]（App 默认 20 条）。
     *
     * **只设目标、不建连**：缩略图/抽帧也会 stat 出大文件，但它们只该占 1 条连接
     * （2026-11-18 用户拍板「目录+缩略图占 1 条连接、视频播放占 20 条」）。
     * 真正的扩建由 [ensureSessionRamp] 触发（调用方确认过"这是播放"）。
     */
    fun planForSize(size: Long?) {
        if (closed) return
        setTargetSessions(config.sessionsForSize(size))
    }

    /**
     * 按当前目标**并行扩建**会话（幂等：已经在扩就不重复发）。
     *
     * 触发条件由后端决定：同一个文件出现**多路并发读**（分段缓存预读 20 段）才是播放；
     * 缩略图被限到 2 路并发，所以永远只在第 1 条会话上跑。
     */
    fun ensureSessionRamp() {
        synchronized(stateLock) {
            if (closed) return
            if (sessionCount >= targetSessions) return
            if (rampJob?.isActive == true) return
            rampJob = background.launch { rampTo() }
        }
    }

    /**
     * 显式预热 [count] 条会话（并行建连，最多 [SftpConfig.maxSessions] 条）。
     *
     * @return 实际建成的条数（部分失败也算成功，失败的车道会被摘掉）。
     */
    suspend fun warmUp(count: Int = targetSessions): Int {
        val wanted = count.coerceIn(0, config.maxSessions)
        val missing = (wanted - sessionCount).coerceAtLeast(0)
        if (missing == 0) return 0
        return SftpRamp.warmUp(missing, RAMP_PARALLEL) { addAndWarmLane() }
    }

    /**
     * 作废**所有** SSH 会话（硬超时后由后端调用）：下一个请求会自动重连。
     *
     * 2026-11-17 真机实证：不作废会让卡死的线程拖死后续所有请求。
     * 2026-11-20 多连接版：许可池**不再整体重置**（那会把在飞租约的许可弄丢），
     * 而是把整条车道（含它的 Semaphore）丢掉，旧租约归还到死车道不影响新池。
     */
    fun invalidateCurrentSession() {
        if (closed) return
        val victims = synchronized(stateLock) {
            val copy = lanes.toList()
            lanes.clear()
            lastLaneIndex = -1
            copy
        }
        victims.forEach { it.shutdown() }
        metaSessionRef.getAndSet(null)?.let { runCatching { it.disconnect() } }
        rampJob?.cancel()
        connectFailures.set(0)
        slotSignal.trySend(Unit)
        AppLog.i(
            TAG,
            "作废 SFTP 会话池：" + victims.size + " 条会话（下一次请求重连）：" +
                redactEndpoint(config.host, config.port),
        )
    }

    override fun close() {
        val victims = synchronized(stateLock) {
            if (closed) return
            closed = true
            val copy = lanes.toList()
            lanes.clear()
            copy
        }
        rampJob?.cancel()
        trimJob?.cancel()
        victims.forEach { it.shutdown() }
        metaSessionRef.getAndSet(null)?.let { runCatching { it.disconnect() } }
        slotSignal.trySend(Unit)
    }

    // ------------------------------------------------------------------ 内部：借还

    /** 挑一条还有通道位的车道（round-robin：把并发摊到多条 TCP 连接上）。 */
    private fun reserveLane(): Lane? = synchronized(stateLock) {
        val count = lanes.size
        if (count == 0) return null
        val start = ((lastLaneIndex + 1) % count + count) % count
        for (step in 0 until count) {
            val index = (start + step) % count
            val lane = lanes[index]
            if (lane.dead) continue
            if (lane.tryReserve()) {
                lastLaneIndex = index
                return lane
            }
        }
        null
    }

    /**
     * 所有车道都满了：目标还没到就**加一条**车道（懒加载）。
     *
     * 只加占位，建连由下一个借到它的请求（或 [rampTo] 的预热）完成，
     * 所以这里不会阻塞调用者。
     */
    private fun growIfNeeded(): Boolean = synchronized(stateLock) {
        if (closed) return false
        if (lanes.size >= config.maxSessions) return false
        if (lanes.size >= targetSessions) return false
        if (connectFailures.get() >= MAX_CONSECUTIVE_FAILURES) return false
        lanes += Lane()
        slotSignal.trySend(Unit)
        true
    }

    /** 缩容到 [target]：只回收「没有借出通道」的车道（正在读的不动）。 */
    private fun trimTo(target: Int) {
        rampJob?.cancel()
        val victims = synchronized(stateLock) {
            if (lanes.size <= target) return
            val surplus = (lanes.size - target).coerceAtLeast(0)
            val removable = lanes.filter { it.leased == 0 }.take(surplus)
            lanes.removeAll(removable)
            removable
        }
        victims.forEach { it.shutdown() }
        if (victims.isNotEmpty()) {
            AppLog.i(TAG, "SFTP 会话缩容：" + victims.size + " 条（目标 " + target + " 条）")
            slotSignal.trySend(Unit)
        }
    }

    /** 播放结束（没有在借通道）一段时间后，把多余的会话收回去，别让手机挂着 20 条空闲 SSH。 */
    private fun maybeTrimIdleLanes() {
        if (closed) return
        val keep = minOf(config.smallFileSessions, config.maxSessions)
        synchronized(stateLock) { if (lanes.size <= keep) return }
        if (!trimScheduled.compareAndSet(false, true)) return
        trimJob = background.launch {
            try {
                delay(IDLE_TRIM_DELAY_MS)
                if (stats().leasedChannels == 0) trimTo(keep)
            } finally {
                trimScheduled.set(false)
            }
        }
    }

    // ------------------------------------------------------------------ 内部：扩容

    /** 改目标：调大只记数（等播放信号再扩建），调小立刻缩容到目标。 */
    private fun setTargetSessions(target: Int) {
        val clamped = target.coerceIn(1, config.maxSessions)
        val changed = synchronized(stateLock) {
            if (clamped == targetSessions) return
            targetSessions = clamped
            true
        }
        if (changed && clamped <= sessionCount) trimTo(clamped)
    }

    /**
     * 并行扩建到 [target] 条会话：一批 [RAMP_PARALLEL] 条同时握手，批间隔 [RAMP_INTERVAL_MS]。
     *
     * 为什么分批：20 条一起发会把手机上行 / 服务器的 sshd 打满；4 条一批既避开「串行 18 秒」，
     * 也避免自己制造一次建连风暴（同预读风暴的教训）。
     */
    private suspend fun rampTo() {
        try {
            while (!closed) {
                // 每批现读目标：播放中目标可能被下一个文件改小（或改大），这里跟着走
                val need = (targetSessions - sessionCount).coerceAtMost(RAMP_PARALLEL)
                if (need <= 0) return
                val made = SftpRamp.warmUp(need, RAMP_PARALLEL) { addAndWarmLane() }
                if (made <= 0) return
                delay(RAMP_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(
                TAG,
                "并行扩建 SFTP 会话中断：" + redactEndpoint(config.host, config.port) + " → " + t.javaClass.simpleName,
            )
        }
    }

    /** 加一条车道并**当场建连**（失败就摘掉，别留一条连不上的空车道占着目标名额）。 */
    private suspend fun addAndWarmLane(): Boolean {
        val lane = synchronized(stateLock) {
            if (closed || lanes.size >= config.maxSessions || lanes.size >= targetSessions) return false
            Lane().also { lanes += it }
        }
        if (lane.warm()) return true
        synchronized(stateLock) { lanes.remove(lane) }
        lane.shutdown()
        return false
    }

    // ------------------------------------------------------------------ 内部：会话

    /** 元数据会话复用：与数据会话同款逻辑，但走独立 Session。 */
    private fun ensureMetaSession(): Session {
        val existing = metaSessionRef.get()
        if (existing != null && existing.isConnected) return existing
        synchronized(metaSessionLock) {
            val again = metaSessionRef.get()
            if (again != null && again.isConnected) return again
            if (again != null) runCatching { again.disconnect() }
            val created = connectSession()
            metaSessionRef.set(created)
            return created
        }
    }

    /** 开一条不经过车道/通道位的临时通道（用于元数据）。 */
    private fun openEphemeralChannel(): ChannelSftp {
        val session = ensureMetaSession()
        return try {
            val channel = session.openChannel(SFTP_CHANNEL) as ChannelSftp
            channel.connect(config.connectTimeoutMs.toInt())
            channel
        } catch (e: JSchException) {
            metaSessionRef.compareAndSet(session, null)
            runCatching { session.disconnect() }
            throw mapJschFailure(e, config, lastHostKeyCheck, "打开 SFTP 元数据通道")
        }
    }

    /** 建会话：密码 / 私钥认证 + 严格主机密钥校验（真正的判定在 [JschHostKeyRepository]）。 */
    private fun connectSession(): Session {
        val session = try {
            jsch.getSession(config.username, config.host, config.port)
        } catch (e: JSchException) {
            throw mapJschFailure(e, config, lastHostKeyCheck, "创建会话")
        }
        // 用 byte[] 重载：setPassword(String) 在 JSch 2.28 已标记废弃
        config.password?.takeIf { it.isNotEmpty() }?.let { session.setPassword(it.toByteArray(Charsets.UTF_8)) }
        // 永远用 yes：放不放行完全由我们的 HostKeyRepository 决定（TOFU / STRICT / ACCEPT_ANY），
        // 这样「主机密钥变更」一定会走到 JSchChangedHostKeyException，不会被 JSch 自己吞掉
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setSocketFactory(timing)
        session.setTimeout(config.readTimeoutMs.toInt())
        try {
            session.connect(config.connectTimeoutMs.toInt())
        } catch (e: JSchException) {
            runCatching { session.disconnect() }
            val mapped = mapJschFailure(e, config, lastHostKeyCheck, "建立 SSH 会话")
            if (mapped is StorageException.Auth) {
                // 2026-11-18 日志脱敏：认证文案里带 账号@主机:端口，账号与主机都要打码
                AppLog.w(
                    TAG,
                    "SFTP 认证/主机密钥问题：" + redactEndpoint(config.host, config.port) +
                        " → " + redactUserInText(redactHostInText(mapped.message.orEmpty(), config.host)),
                )
            }
            throw mapped
        }
        return session
    }

    /** 指数退避：0.5 s → 1 s → 2 s → … → 8 s（有上限，不无限重试）。 */
    private fun backoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 5)
        return (RETRY_BASE_MS shl shift).coerceAtMost(RETRY_MAX_MS)
    }

    /**
     * 一条车道 = 一条 SSH 会话 + 它的通道位。
     *
     * 借出 = 拿一个 [permits] 许可 + 从 [idle] 取一条可用通道（没有就当场开一条）。
     * 归还 = 放回 [idle]（健康）或断开（脏），并释放许可。
     */
    private inner class Lane : SftpChannelSlot {

        private val permits = Semaphore(config.effectiveChannelsPerSession)

        /** 空闲通道（后进先出：让刚用过的通道继续服役，减少重开句柄）。 */
        private val idle = ArrayDeque<ChannelSftp>()
        private val idleLock = Any()

        private val sessionLock = Any()
        private var session: Session? = null
        private var failureCount = 0
        private var nextRetryAtMs = 0L
        private var lastFailure: StorageException? = null

        @Volatile
        var dead: Boolean = false
            private set

        /** 已借出的通道数（诊断 / 缩容判定）。 */
        val leased: Int get() = config.effectiveChannelsPerSession - permits.availablePermits

        val idleCount: Int get() = synchronized(idleLock) { idle.size }

        val connected: Boolean get() = synchronized(sessionLock) { session?.isConnected == true }

        fun tryReserve(): Boolean = !dead && permits.tryAcquire()

        fun giveBackSlot() {
            permits.release()
        }

        override fun recycle(channel: ChannelSftp, discard: Boolean) {
            val healthy = !discard && !dead && !closed && channel.isConnected
            if (healthy) {
                synchronized(idleLock) { idle.addLast(channel) }
            } else {
                runCatching { channel.disconnect() }
            }
            permits.release()
            slotSignal.trySend(Unit)
            maybeTrimIdleLanes()
        }

        /** 借出一条通道（阻塞 I/O，跑在 [StorageDispatchers.io] 上）。 */
        suspend fun openChannel(): ChannelSftp = withContext(StorageDispatchers.io) { openChannelBlocking() }

        private fun openChannelBlocking(): ChannelSftp {
            if (dead) throw StorageException.Network("SFTP 会话已作废，请重试：" + config.id)
            takeIdle()?.let { return it }
            val live = ensureSession()
            return try {
                val channel = live.openChannel(SFTP_CHANNEL) as ChannelSftp
                channel.connect(config.connectTimeoutMs.toInt())
                channel
            } catch (e: JSchException) {
                invalidateSession()
                throw mapJschFailure(e, config, lastHostKeyCheck, "打开 SFTP 通道")
            }
        }

        private fun takeIdle(): ChannelSftp? {
            while (true) {
                val channel = synchronized(idleLock) { idle.removeLastOrNull() } ?: return null
                if (channel.isConnected) return channel
                runCatching { channel.disconnect() }
            }
        }

        /** 建立/复用会话；失败进指数退避（退避期内直接抛上次的错）。 */
        fun ensureSession(): Session {
            synchronized(sessionLock) {
                if (dead) throw StorageException.Network("SFTP 会话已作废，请重试：" + config.id)
                session?.let { existing ->
                    if (existing.isConnected) return existing
                    runCatching { existing.disconnect() }
                    session = null
                }
                val now = System.currentTimeMillis()
                if (now < nextRetryAtMs) {
                    throw lastFailure ?: StorageException.Network(
                        "SFTP 会话正在退避重连（" + (nextRetryAtMs - now) + " ms 后再试）：" + config.id,
                    )
                }
                return try {
                    val created = connectSession()
                    session = created
                    failureCount = 0
                    nextRetryAtMs = 0L
                    lastFailure = null
                    connectFailures.set(0)
                    created
                } catch (t: Throwable) {
                    failureCount += 1
                    nextRetryAtMs = now + backoffMs(failureCount)
                    lastFailure = t as? StorageException
                        ?: StorageException.Unknown("SFTP 建连失败：" + config.id, t)
                    connectFailures.incrementAndGet()
                    throw t
                }
            }
        }

        /** 会话可疑（开通道失败）：丢掉它，下一次调用重建。 */
        fun invalidateSession() {
            synchronized(sessionLock) {
                val old = session
                session = null
                runCatching { old?.disconnect() }
            }
        }

        /** 预热：把 SSH 握手（公网约 0.9 s）提前吃掉，别等真正要读的时候再付。 */
        suspend fun warm(): Boolean = withContext(StorageDispatchers.io) {
            if (dead) return@withContext false
            try {
                ensureSession()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                false
            }
        }

        /** 作废整条会话：借出去的通道由持有者丢弃（[recycle] 里看 [dead]）。 */
        fun shutdown() {
            dead = true
            val channels = synchronized(idleLock) {
                val copy = idle.toList()
                idle.clear()
                copy
            }
            channels.forEach { runCatching { it.disconnect() } }
            invalidateSession()
        }
    }

    private companion object {
        const val TAG = "storage-sftp"
        const val SFTP_CHANNEL = "sftp"

        /** 等通道位的唤醒切片：醒来就重试，最长 200 ms 一次（不是忙等，也不至于迟钝）。 */
        const val WAIT_SLICE_MS: Long = 200L

        /** 连续建连失败多少次就不再扩建新车道（指数退避兜住其余的）。 */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** 单次 acquire 连续失败多少次就把错误交给调用方（别在死服务器上原地打转）。 */
        const val MAX_ACQUIRE_ATTEMPTS = 3

        /** 退避基数 / 上限。 */
        const val RETRY_BASE_MS: Long = 500L
        const val RETRY_MAX_MS: Long = 8_000L

        /** 后台扩容：每批几条同时握手、批间隔。 */
        const val RAMP_PARALLEL: Int = 4
        const val RAMP_INTERVAL_MS: Long = 120L

        /** 空闲回收：播放结束（无在借通道）这么久之后收回多余会话。 */
        const val IDLE_TRIM_DELAY_MS: Long = 60_000L
    }
}

/** 池的即时快照（诊断 / 单测断言用）。 */
internal data class SftpPoolStats(
    val sessions: Int,
    val targetSessions: Int,
    val capacity: Int,
    val leasedChannels: Int,
    val idleChannels: Int,
    val connectFailures: Int,
)

/**
 * 一条通道的宿主（= 某条会话的通道位）。
 *
 * 租约归还时由它决定复用还是丢弃；这样 [ChannelLease] 不必知道会话/车道的细节。
 */
internal interface SftpChannelSlot {

    /**
     * 归还一条通道。
     *
     * @param discard true = 通道状态可疑（操作抛过异常），直接丢弃不复用。
     */
    fun recycle(channel: ChannelSftp, discard: Boolean)
}

/**
 * 一条通道的租约（[SftpChannelPool.acquire] 的返回值）。
 *
 * [release] 幂等：重复调用只生效一次，避免流关闭路径上重复归还导致许可数错乱。
 */
internal class ChannelLease(
    private val slot: SftpChannelSlot,
    val channel: ChannelSftp,
) {

    private var released = false

    /** 是否已归还（单测断言「能复用」时用）。 */
    val isReleased: Boolean get() = released

    /**
     * 归还通道。
     *
     * @param discard true = 通道状态可疑（操作抛过异常），直接丢弃不复用。
     */
    fun release(discard: Boolean) {
        if (released) return
        released = true
        slot.recycle(channel, discard)
    }
}
