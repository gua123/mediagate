package io.github.gua123.mediagate.data.storage.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SocketFactory
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把外部传入的路径规范成「树内相对路径」（**R2** 口径与 `:data:storage-local` 一致）。
 *
 * 规则：反斜杠转正斜杠、去掉首尾与重复的 /、去掉 . 段；根目录得到空串。
 * 出现 .. 段直接抛 [StorageException.AccessDenied]：不允许越出 [SftpConfig.basePath]（安全边界）。
 */
internal fun normalizeSftpPath(path: String): String {
    val segments = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) {
        throw StorageException.AccessDenied("路径不允许越出根目录：" + path)
    }
    return segments.joinToString("/")
}

/** 拼接树内相对路径（[base] 为空表示根）。 */
internal fun joinSftpPath(base: String, name: String): String = if (base.isEmpty()) name else base + "/" + name

/** 把 [SftpConfig.basePath] 归一化成 FTP 风格的绝对根路径：以 / 开头、无尾斜杠（根就是 /）。 */
internal fun absoluteSftpRoot(basePath: String): String {
    val relative = normalizeSftpPath(basePath)
    return if (relative.isEmpty()) "/" else "/" + relative
}

/**
 * 树内相对路径 → 远端绝对路径（**R2**）。
 *
 * 一律用绝对路径发指令（不依赖账号当前工作目录），断线重连后行为完全一致。
 */
internal fun absoluteSftpPath(rootPath: String, relative: String): String {
    val normalized = normalizeSftpPath(relative)
    if (normalized.isEmpty()) return rootPath
    return if (rootPath == "/") "/" + normalized else rootPath + "/" + normalized
}

/** 取路径末段作为展示名（根路径回退成 /）。 */
internal fun sftpNameOf(relative: String): String =
    if (relative.isEmpty()) "/" else relative.substringAfterLast('/')

/** 目录优先、再按名称（大小写不敏感）排序：与 FileStorageBackend 完全同口径（R2 列表稳定性）。 */
internal val SFTP_DIRECTORY_FIRST: Comparator<RemoteEntry> =
    compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** 按 [page] 截取分页；page 为 null 或 limit <= 0 表示不限制（plan 4.10）。 */
internal fun paginateSftp(entries: List<RemoteEntry>, page: Page?): List<RemoteEntry> {
    if (page == null) return entries
    val from = page.offset.coerceAtMost(entries.size)
    val rest = entries.subList(from, entries.size)
    return if (page.limit <= 0) rest.toList() else rest.take(page.limit)
}

/**
 * 计算一段读请求的实际可读长度（0 表示起点已在末尾之后）。
 *
 * @param total 数据总长度（-1 未知）。
 * @param offset 起始偏移。
 * @param length 请求长度（-1 表示读到末尾）。
 */
internal fun effectiveSftpLength(total: Long, offset: Long, length: Long): Long {
    if (length < 0) return if (total < 0) -1 else (total - offset).coerceAtLeast(0)
    if (total < 0) return length
    return minOf(length, (total - offset).coerceAtLeast(0))
}

/** JSch 的 SFTP 状态字 → 统一异常分类（R2 / R14）。 */
internal fun mapSftpFailure(e: SftpException, path: String): StorageException {
    val message = e.message.orEmpty()
    return when {
        e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> StorageException.NotFound("SFTP 路径不存在：" + path, e)
        e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> StorageException.AccessDenied("SFTP 无访问权限：" + path, e)
        e.id == ChannelSftp.SSH_FX_NO_CONNECTION || e.id == ChannelSftp.SSH_FX_CONNECTION_LOST ->
            StorageException.Network("SFTP 连接已断开：" + path, e)
        e.id == ChannelSftp.SSH_FX_OP_UNSUPPORTED ->
            StorageException.NotSupported("服务器不支持该 SFTP 操作：" + path, e)
        // 少数服务器把「父目录不存在」「只读文件系统」压成 SSH_FX_FAILURE，这里按消息再分一次
        e.id == ChannelSftp.SSH_FX_FAILURE && message.contains("no such file", true) ->
            StorageException.NotFound("SFTP 路径不存在：" + path, e)
        e.id == ChannelSftp.SSH_FX_FAILURE && message.contains("permission denied", true) ->
            StorageException.AccessDenied("SFTP 无访问权限：" + path, e)
        else -> StorageException.Unknown("SFTP 操作失败（状态码 " + e.id + "）：" + path, e)
    }
}

/**
 * 连接层异常（JSch / Socket）→ 统一异常分类（**R8** 三段测试的错误分类口径）。
 *
 * 分类顺序有讲究：主机密钥 → 认证 → 网络（DNS / 拒绝 / 超时 / 不可达）。
 * 返回的消息是**中文**且刻意避开会让 `:core:network` 的 `ConnectivityError.fromMessage` 误判的词：
 * 「主机密钥变更」用「证书不受信任」而不是「拒绝连接」，这样会被归到 TLS_FAILED 而不是 CONNECTION_REFUSED。
 *
 * @param hostKeyCheck 本次连接最近一次主机密钥校验结论（可能为 null）。
 */
internal fun mapJschFailure(
    e: Throwable,
    config: SftpConfig,
    hostKeyCheck: HostKeyCheck?,
    context: String,
): StorageException {
    val rejected = hostKeyCheck as? HostKeyCheck.Rejected
    return when {
        e is JSchChangedHostKeyException || rejected?.reason == HostKeyRejectReason.CHANGED ->
            StorageException.Auth(
                rejected?.message ?: ("SFTP 主机密钥已变更，证书不受信任（安全连接已中止）：" + config.host + ":" + config.port),
                e,
            )
        e is JSchUnknownHostKeyException || rejected?.reason == HostKeyRejectReason.UNKNOWN_HOST ->
            StorageException.Auth(
                rejected?.message ?: ("SFTP 主机密钥未知，证书不受信任（安全连接已中止）：" + config.host + ":" + config.port),
                e,
            )
        isAuthenticationFailure(e) ->
            StorageException.Auth("SFTP 认证失败（账号或密码错误）：" + config.username + "@" + config.host + ":" + config.port, e)
        else -> {
            val cause = deepCause(e)
            when {
                cause is UnknownHostException ->
                    StorageException.Network("SFTP DNS 解析失败（" + config.host + "）", e)
                cause is ConnectException ->
                    StorageException.Network("SFTP 连接被拒绝（" + config.host + ":" + config.port + "）", e)
                cause is NoRouteToHostException ->
                    StorageException.Network("SFTP 网络不可达（" + config.host + ":" + config.port + "）", e)
                cause is SocketTimeoutException ->
                    StorageException.Network("SFTP 连接或读取超时（" + config.connectTimeoutMs + " ms）", e)
                cause is SocketException ->
                    StorageException.Network("SFTP 网络错误：" + ErrorText.of(cause, "详情见诊断日志"), e)
                e is JSchException ->
                    StorageException.Network("SFTP 连接失败（" + context + "）：" + ErrorText.of(e, "详情见诊断日志"), e)
                else -> StorageException.Unknown("SFTP 操作失败（" + context + "）：" + describeThrowable(e), e)
            }
        }
    }
}

/** 把任意 I/O 异常翻译成统一异常（读流中途断线等场景）。 */
internal fun mapSftpIoFailure(e: Throwable, path: String, hostKeyCheck: HostKeyCheck?): StorageException = when (e) {
    is StorageException -> e
    is SftpException -> mapSftpFailure(e, path)
    else -> {
        val cause = deepCause(e)
        when (cause) {
            is UnknownHostException -> StorageException.Network("SFTP DNS 解析失败：" + path, e)
            is ConnectException -> StorageException.Network("SFTP 连接被拒绝：" + path, e)
            is SocketTimeoutException -> StorageException.Network("SFTP 读取超时：" + path, e)
            else -> {
                val rejected = hostKeyCheck as? HostKeyCheck.Rejected
                if (rejected != null) {
                    StorageException.Auth(rejected.message, e)
                } else {
                    StorageException.Network("SFTP 传输中断：" + path + "（" + describeThrowable(e) + "）", e)
                }
            }
        }
    }
}

/** 认证失败判定（JSch 的措辞在版本间有差异，这里同时看异常类型与消息）。 */
internal fun isAuthenticationFailure(e: Throwable): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth++ < MAX_CAUSE_DEPTH) {
        val name = current.javaClass.simpleName
        if (name.contains("AuthCancel") || name.contains("PartialAuth")) return true
        val message = current.message.orEmpty()
        if (message.contains("Auth fail", true) || message.contains("USERAUTH fail", true) ||
            message.contains("auth cancel", true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/** 取最内层 cause（DNS / 连接类异常都被 JSch 包了好几层）。 */
internal fun deepCause(t: Throwable): Throwable {
    var current = t
    var depth = 0
    while (depth++ < MAX_CAUSE_DEPTH) {
        val next = current.cause ?: return current
        if (next === current) return current
        current = next
    }
    return current
}

/**
 * 异常摘要（**不含凭据**，可进日志/报告）。
 *
 * R16：一律经 [ErrorText] 转成中文一句话——底层库的 message 是英文，直接拼进 StorageException 就会
 * 在浏览页/连接页露出「SFTP 网络错误：java.net.SocketException Connection reset」这类夹生句。
 * 认不出的英文退回「详情见诊断日志」，原始异常仍在 AppLog 里。
 */
internal fun describeThrowable(t: Throwable): String = ErrorText.of(t, "详情见诊断日志")

private const val MAX_CAUSE_DEPTH = 16

/**
 * 带计时的 Socket 工厂（plan 4.5 三段计时的前两段）。
 *
 * JSch 自己不做 DNS / TCP 分段计时，但它允许注入 [SocketFactory]：
 * 这里在真正建连前**先自己解析域名**（单独计时），再对解析出的地址逐个 TCP 连接（单独计时），
 * 于是 probe 能给出「DNS x ms / TCP y ms / banner+认证+列目录 z ms」三段，
 * 而不是一句笼统的「连接失败」——「改错端口能复现对应错误」靠的就是这个（R8）。
 *
 * 实例内部累加：一个 pool 一次 probe 只连一次，累加值就等于那一次的值；
 * 多次连接（多地址重试）时累加值反映真实总耗时。
 */
internal class TimingSocketFactory(private val connectTimeoutMs: Int) : SocketFactory {

    @Volatile
    var dnsMs: Long = 0L
        private set

    @Volatile
    var connectMs: Long = 0L
        private set

    /** 最近一次连接实际用到的地址（诊断用）。 */
    @Volatile
    var lastAddress: String? = null
        private set

    /** 清零（probe 前调用，保证拿到的是本次的耗时）。 */
    fun reset() {
        dnsMs = 0L
        connectMs = 0L
        lastAddress = null
    }

    override fun createSocket(host: String, port: Int): Socket {
        val dnsStarted = System.nanoTime()
        val addresses = try {
            InetAddress.getAllByName(host)
        } finally {
            dnsMs += elapsedMs(dnsStarted)
        }
        if (addresses.isEmpty()) throw UnknownHostException(host)
        var lastError: IOException? = null
        for (address in addresses) {
            val connectStarted = System.nanoTime()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                socket.tcpNoDelay = true
                lastAddress = address.hostAddress
                return socket
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
            } finally {
                connectMs += elapsedMs(connectStarted)
            }
        }
        throw lastError ?: UnknownHostException(host)
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
}
