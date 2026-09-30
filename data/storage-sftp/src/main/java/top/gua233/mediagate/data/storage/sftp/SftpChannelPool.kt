package io.github.gua123.mediagate.data.storage.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * SFTP 会话与通道池（**R2** / **R4** / plan 4.1「单会话复用 + 通道池，断线重连不丢 seek」）。
 *
 * 为什么必须池化：
 * - SSH 连接很贵（TCP + KEX + 认证），每次读都重连不可接受 → **整条后端共用一个 [Session]**；
 * - 但 SFTP 子通道是**严格请求/应答**的，一个 [ChannelSftp] 同一时刻只能跑一个操作，
 *   「播放 + 抽帧 + 缩略图」并行就会互相阻塞 → **开一条通道池**（大小 = [SftpConfig.maxChannels]）；
 * - 连接断掉（切网 / 服务器重启 / 空闲被踢）时，下一次取通道会自动重连，
 *   而按偏移读的语义由 [SftpRangeStream] 保证：它记住的是**文件绝对偏移**，不是「流里读了多少」。
 *
 * 线程模型：所有 JSch 调用都是阻塞 I/O，一律在 [Dispatchers.IO] 上执行；
 * 并发上限用协程 [Semaphore] 控制，调用方挂起而不是阻塞线程。
 */
internal class SftpChannelPool(
    private val config: SftpConfig,
    private val verifier: HostKeyVerifier,
    val timing: TimingSocketFactory = TimingSocketFactory(config.connectTimeoutMs.toInt()),
) : Closeable {

    private val permits = Semaphore(config.maxChannels)

    /** 空闲通道（后用先取，尽量让旧通道继续服役，减少握手）。 */
    private val idle = ArrayDeque<ChannelSftp>()
    private val idleLock = Any()

    private val sessionRef = AtomicReference<Session?>(null)
    private val sessionLock = Any()

    private val hostKeys = JschHostKeyRepository(config.host, config.port, verifier)

    private val jsch = JSch().apply { setHostKeyRepository(hostKeys) }

    @Volatile
    private var closed = false

    init {
        config.privateKey?.let { key ->
            jsch.addIdentity(key.name, key.privateKey, key.publicKey, key.passphrase?.toByteArray())
        }
    }

    /** 最近一次主机密钥校验结论（异常翻译时取指纹用）。 */
    val lastHostKeyCheck: HostKeyCheck? get() = hostKeys.lastCheck.get()

    /** 当前是否已有活着的会话（诊断/单测用）。 */
    val hasLiveSession: Boolean get() = sessionRef.get()?.isConnected == true

    /** 当前空闲通道数（单测用）。 */
    val idleChannels: Int get() = synchronized(idleLock) { idle.size }

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
     * @throws StorageException 池已关闭或建连失败。
     */
    suspend fun acquire(): ChannelLease {
        if (closed) throw StorageException.Unknown("后端已关闭：" + config.id)
        permits.acquire()
        var handedOff = false
        try {
            val channel = withContext(Dispatchers.IO) { obtainChannel() }
            handedOff = true
            return ChannelLease(this, channel)
        } finally {
            if (!handedOff) permits.release()
        }
    }

    /** 归还通道：健康的放回池子，脏的（或池已关）直接断开。 */
    internal fun recycle(channel: ChannelSftp, discard: Boolean) {
        val healthy = !discard && !closed && channel.isConnected
        if (healthy) {
            synchronized(idleLock) { idle.addLast(channel) }
        } else {
            runCatching { channel.disconnect() }
        }
        permits.release()
    }

    override fun close() {
        if (closed) return
        closed = true
        val channels = synchronized(idleLock) {
            val copy = idle.toList()
            idle.clear()
            copy
        }
        channels.forEach { runCatching { it.disconnect() } }
        sessionRef.getAndSet(null)?.let { runCatching { it.disconnect() } }
    }

    // ------------------------------------------------------------------ 内部

    /** 取一条可用通道：优先复用空闲的，其次复用会话新开一条，最后（重）建会话。 */
    private fun obtainChannel(): ChannelSftp {
        while (true) {
            val pooled = synchronized(idleLock) { idle.removeLastOrNull() } ?: break
            if (pooled.isConnected) return pooled
            runCatching { pooled.disconnect() }
        }
        val session = ensureSession()
        return try {
            val channel = session.openChannel(SFTP_CHANNEL) as ChannelSftp
            channel.connect(config.connectTimeoutMs.toInt())
            channel
        } catch (e: JSchException) {
            invalidateSession(session)
            throw mapJschFailure(e, config, lastHostKeyCheck, "打开 SFTP 通道")
        }
    }

    /** 会话复用：活着就直接用，死了（或第一次）才重建。 */
    private fun ensureSession(): Session {
        val existing = sessionRef.get()
        if (existing != null && existing.isConnected) return existing
        synchronized(sessionLock) {
            val again = sessionRef.get()
            if (again != null && again.isConnected) return again
            if (again != null) runCatching { again.disconnect() }
            val created = connectSession()
            sessionRef.set(created)
            return created
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
                AppLog.w(TAG, "SFTP 认证/主机密钥问题：" + config.host + ":" + config.port + " → " + mapped.message)
            }
            throw mapped
        }
        return session
    }

    /** 会话出问题时把它丢掉：下一个 [acquire] 会重连（断线重连的入口）。 */
    private fun invalidateSession(session: Session) {
        if (sessionRef.compareAndSet(session, null)) {
            runCatching { session.disconnect() }
        }
    }

    private companion object {
        const val TAG = "storage-sftp"
        const val SFTP_CHANNEL = "sftp"
    }
}

/**
 * 一条通道的租约（[SftpChannelPool.acquire] 的返回值）。
 *
 * [release] 幂等：重复调用只生效一次，避免流关闭路径上重复归还导致许可数错乱。
 */
internal class ChannelLease(
    private val pool: SftpChannelPool,
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
        pool.recycle(channel, discard)
    }
}
