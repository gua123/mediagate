package io.github.gua123.mediagate.data.storage.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.nio.charset.Charset
import java.time.Duration

/**
 * 能取回**原始 LIST / MLSD 行**的 FTP 客户端（**R2**：MLSD→LIST 两条路径都走自己的解析器）。
 *
 * commons-net 的 `listFiles()` / `mlistDir()` 会用它自带的方言解析器，拿不到原始文本；
 * 而我们需要「MLSD 不支持就退回自己的 LIST 解析」这条明确的降级链，所以这里开一个小口子：
 * 子类调用受保护的 `_openDataConnection_` 自己建数据连接、自己收行。
 *
 * FTPS 的 `_openDataConnection_` 被 [FTPSClient] 重写过（数据连接要 TLS 保护），
 * 所以明文与 FTPS 各有一个实现，共享 [fetchListLines] 的读行逻辑。
 */
internal interface RawListSource {

    /** 建一条 LIST/MLSD 数据连接；null 表示服务器拒绝了命令或数据连接建不起来（看 [FTPClient.getReplyCode]）。 */
    fun openListDataConnection(command: String, args: String): Socket?

    /** LIST 的参数（自动补全路径，commons-net 的 `getListArguments`）。 */
    fun listArgumentsFor(path: String): String

    /** 收尾：读 226/426 应答，返回控制连接是否还能继续用。 */
    fun finishListTransfer(): Boolean
}

/** 明文 FTP 客户端（自己实现原始 LIST 能力）。 */
internal class MediateFtpClient : FTPClient(), RawListSource {

    override fun openListDataConnection(command: String, args: String): Socket? =
        _openDataConnection_(command, args)

    override fun listArgumentsFor(path: String): String = getListArguments(path)

    override fun finishListTransfer(): Boolean = completePendingCommand()
}

/**
 * FTPS 客户端（显式 / 隐式；数据连接由 [FTPSClient] 负责 TLS）。
 *
 * [implicitMode] 自己留一份：commons-net 把 implicit 存在私有字段里没有公开读取口，
 * 而「EXPLICIT 必须非隐式」是配置正确性的关键断言（plan 4.9），不能只靠类型判断。
 */
internal class MediateFtpsClient(protocol: String, val implicitMode: Boolean) :
    FTPSClient(protocol, implicitMode),
    RawListSource {

    override fun openListDataConnection(command: String, args: String): Socket? =
        _openDataConnection_(command, args)

    override fun listArgumentsFor(path: String): String = getListArguments(path)

    override fun finishListTransfer(): Boolean = completePendingCommand()
}

/**
 * 按 [FtpConfig.ftpsMode] 造客户端（**R2** / plan 4.9）。
 *
 * 抽成独立函数是为了可测：单测直接断言「EXPLICIT → FTPSClient 且非隐式、IMPLICIT → 隐式、
 * NONE → 明文」，不需要真跑一次 TLS 握手（真机证书校验另列清单）。
 */
internal fun newFtpClient(config: FtpConfig): FTPClient = when (config.ftpsMode) {
    FtpsMode.NONE -> MediateFtpClient()
    FtpsMode.EXPLICIT -> MediateFtpsClient("TLS", false)
    FtpsMode.IMPLICIT -> MediateFtpsClient("TLS", true)
}

/**
 * 取原始 LIST / MLSD 行。
 *
 * @return 行列表；**null 表示命令被拒或数据连接没建起来**（此时 [FTPClient.getReplyCode] 是拒绝码）。
 */
internal fun RawListSource.fetchListLines(command: String, path: String, charset: Charset): List<String>? {
    val args = listArgumentsFor(path)
    val socket = openListDataConnection(command, args) ?: return null
    val lines = socket.use { data ->
        data.getInputStream().bufferedReader(charset).use { reader -> reader.readLines() }
    }
    // 数据连接读完即 EOF，服务端随后发 226；收掉它控制连接才能继续用
    finishListTransfer()
    return lines
}

/**
 * FTP 控制连接池（**R2** / **R4** / plan 4.1 FTP 行）。
 *
 * FTP 的麻烦在于：**一次数据传输期间控制连接被独占**（PASV + RETR 的应答都在它上面），
 * 所以「播放 + 抽帧 + 缩略图」并行必须有多条控制连接；[FtpConfig.maxConnections] 就是池子大小，
 * 同时也是 [io.github.gua123.mediagate.core.model.Caps.maxParallelReads]。
 *
 * 断线（切网 / 服务端超时踢人）时，下一次取连接会自动重连；传输中途出错的控制连接一律**丢弃**
 * 而不是还池，避免把状态未知的连接交给下一个使用者。
 *
 * 所有 commons-net 调用都是阻塞 I/O，一律在 [Dispatchers.IO] 上执行。
 */
internal class FtpClientPool(private val config: FtpConfig) : Closeable {

    private val permits = Semaphore(config.maxConnections)

    private val idle = ArrayDeque<FTPClient>()
    private val idleLock = Any()

    @Volatile
    private var closed = false

    /** 当前空闲连接数（单测/诊断用）。 */
    val idleClients: Int get() = synchronized(idleLock) { idle.size }

    /** 借一条连接执行 [block]，结束后归还（抛异常则丢弃）。 */
    suspend fun <T> use(block: (FTPClient) -> T): T {
        val lease = acquire()
        var healthy = false
        try {
            val result = block(lease.client)
            healthy = true
            return result
        } finally {
            lease.release(discard = !healthy)
        }
    }

    /** 借一条连接并**持有**（给 [FtpRangeStream] 用：一次传输占一条连接）。 */
    suspend fun acquire(): FtpClientLease {
        if (closed) throw StorageException.Unknown("后端已关闭：" + config.id)
        permits.acquire()
        var handedOff = false
        try {
            val client = withContext(Dispatchers.IO) { obtainClient() }
            handedOff = true
            return FtpClientLease(this, client)
        } finally {
            if (!handedOff) permits.release()
        }
    }

    /** 归还连接：健康的放回池子，脏的（或池已关）直接断开。 */
    internal fun recycle(client: FTPClient, discard: Boolean) {
        val healthy = !discard && !closed && client.isConnected
        if (healthy) {
            synchronized(idleLock) { idle.addLast(client) }
        } else {
            runCatching { client.disconnect() }
        }
        permits.release()
    }

    override fun close() {
        if (closed) return
        closed = true
        val clients = synchronized(idleLock) {
            val copy = idle.toList()
            idle.clear()
            copy
        }
        clients.forEach { runCatching { it.disconnect() } }
    }

    // ------------------------------------------------------------------ 内部

    private fun obtainClient(): FTPClient {
        while (true) {
            val pooled = synchronized(idleLock) { idle.removeLastOrNull() } ?: break
            if (pooled.isConnected) return pooled
            runCatching { pooled.disconnect() }
        }
        return connectClient()
    }

    /** 建一条控制连接：220 banner → 登录 → 二进制 → PASV（plan 4.1「PASV 优先」）。 */
    private fun connectClient(): FTPClient {
        val client = newFtpClient(config)
        try {
            client.setConnectTimeout(config.connectTimeoutMs.toInt())
            client.setDefaultTimeout(config.readTimeoutMs.toInt())
            client.setDataTimeout(Duration.ofMillis(config.readTimeoutMs))
            client.setControlEncoding(CONTROL_ENCODING)
            client.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw StorageException.Network(
                    "FTP 服务未就绪（" + client.replyCode + " " + client.replyString.trim() + "）：" +
                        config.host + ":" + config.port,
                )
            }
            if (!client.login(config.username, config.password.orEmpty())) {
                throw classifyReply(client, config, "登录 " + config.username + "@" + config.host + ":" + config.port)
            }
            client.setFileType(FTPClient.BINARY_FILE_TYPE)
            if (config.passive) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            if (client is FTPSClient && config.ftpsMode.secure) {
                // 数据连接也要加密：PBSZ 0（TLS 下不分块）+ PROT P（私有）
                client.execPBSZ(0)
                client.execPROT("P")
            }
            return client
        } catch (e: StorageException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            throw classifyIoFailure(e, config)
        }
    }

    internal companion object {
        const val CONTROL_ENCODING: String = "UTF-8"
    }
}

/** 一条控制连接的租约（[FtpClientPool.acquire] 的返回值），[release] 幂等。 */
internal class FtpClientLease(
    private val pool: FtpClientPool,
    val client: FTPClient,
) {

    private var released = false

    val isReleased: Boolean get() = released

    /**
     * 归还连接。
     *
     * @param discard true = 连接状态可疑（传输被中断 / 操作抛过异常），直接丢弃不复用。
     */
    fun release(discard: Boolean) {
        if (released) return
        released = true
        pool.recycle(client, discard)
    }
}

/** 连接层异常 → 统一分类（**R8**；消息是中文且刻意避开会让 ConnectivityError 误判的词）。 */
internal fun classifyIoFailure(e: IOException, config: FtpConfig): StorageException {
    val cause = generateSequence(e as Throwable) { it.cause }.take(8)
        .firstOrNull { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException }
    return when (cause) {
        is java.net.UnknownHostException -> StorageException.Network("FTP DNS 解析失败（" + config.host + "）", e)
        is java.net.ConnectException -> StorageException.Network("FTP 连接被拒绝（" + config.host + ":" + config.port + "）", e)
        is java.net.NoRouteToHostException -> StorageException.Network("FTP 网络不可达（" + config.host + ":" + config.port + "）", e)
        is java.net.SocketTimeoutException ->
            StorageException.Network("FTP 连接或读取超时（" + config.connectTimeoutMs + " ms）", e)
        else -> {
            val text = e.message.orEmpty()
            if (text.contains("refused", true)) {
                StorageException.Network("FTP 连接被拒绝（" + config.host + ":" + config.port + "）", e)
            } else if (text.contains("timed out", true)) {
                StorageException.Network("FTP 连接或读取超时（" + config.connectTimeoutMs + " ms）", e)
            } else {
                StorageException.Network("FTP 网络失败：" + e.javaClass.simpleName + " " + text, e)
            }
        }
    }
}

/**
 * FTP 应答码 → 统一分类（**R8** 错误分类 / **R14** 写权限降级）。
 *
 * 分类口径：
 * - 530 / 532 → 认证失败（账号密码错）；
 * - 550 / 553 → 按应答文本再分「不存在」与「无权限」；
 * - 552 / 452 → 空间或资源不足，归 Unknown（不是权限问题）；
 * - 425 / 426 / 421 → 数据连接或控制连接问题，归网络。
 */
internal fun classifyReply(client: FTPClient, config: FtpConfig, context: String): StorageException {
    val code = client.replyCode
    val text = client.replyString.trim()
    AppLog.w(TAG_FTP, "FTP 应答 " + code + " " + text + "（" + context + "）")
    return when {
        code == 530 || code == 532 ->
            StorageException.Auth("FTP 认证失败（" + code + " 账号或密码错误）：" + config.username + "@" + config.host)
        code == 550 || code == 553 -> {
            val lower = text.lowercase()
            when {
                lower.contains("no such") || lower.contains("not found") || lower.contains("does not exist") ||
                    lower.contains("不存在") ->
                    StorageException.NotFound("FTP 路径不存在（" + code + "）：" + context)
                else -> StorageException.AccessDenied("FTP 无访问权限（" + code + "）：" + context)
            }
        }
        code == 421 || code == 425 || code == 426 ->
            StorageException.Network("FTP 数据连接不可用（" + code + "）：" + context)
        code == 500 || code == 502 || code == 504 ->
            StorageException.NotSupported("FTP 服务器不支持该命令（" + code + "）：" + context)
        else -> StorageException.Unknown("FTP 操作失败（" + code + " " + text + "）：" + context)
    }
}

internal const val TAG_FTP = "storage-ftp"
