package io.github.gua123.mediagate.data.storage.ftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * FTP 存储后端（**R2** 四协议之一 / **R4** 拖拽随机读 / **R7** 多地址 / **R8** 连通性测试 / **R14** 字幕写回）。
 *
 * 基于 Apache Commons Net（plan 第 2 章选型）：
 * - 列目录：**优先 MLSD**（RFC 3659，字段最全），服务器不支持就**退回 LIST** 并用自研解析器
 *   兼容 Unix `ls -l` 与 DOS 两种方言（plan 4.1「MLSD→LIST」）；排序分页与
 *   `:data:storage-local` 的 FileStorageBackend 完全同口径；
 * - 随机读：**REST + RETR**（每次定位重新发 REST），并**探测 REST 能力**：
 *   服务器不支持就把 [caps] 的 `randomAccess`/`resumeByRest` 降级为 false，
 *   上层据此走 plan 4.1 的分段缓存降级链；
 * - 写回：**STOR**（R14 字幕写回视频同目录；父目录不存在 → NotFound、无权限 → AccessDenied）；
 * - 连接：控制连接池（[FtpClientPool]，传输期间控制连接被独占），断线自动重连；
 * - 连通性：probe() 三段计时（DNS / TCP + 220 banner / 登录 + PASV），**不抛异常**；
 * - FTPS：明文 / 显式（AUTH TLS）/ 隐式三种模式（[FtpsMode]，plan 4.9）。
 *
 * 所有 commons-net 调用都是阻塞 I/O，一律包在 `Dispatchers.IO` 里。
 *
 * @param config 连接配置。
 */
class FtpStorageBackend(val config: FtpConfig) : StorageBackend {

    private val pool = FtpClientPool(config)

    /** 后端唯一标识（R2 缓存 key）。 */
    override val id: String = config.id

    /** REST 能力（[Capability.UNKNOWN] 时乐观认为支持；探测到不支持就降级，plan 4.1 降级链）。 */
    private val restSupport = AtomicReference(Capability.UNKNOWN)

    /** MLSD 能力（未知时先试；服务器明确不支持就固定走 LIST，plan 4.1「MLSD→LIST」）。 */
    private val mlsdSupport = AtomicReference(Capability.UNKNOWN)

    @Volatile
    private var closed = false

    /**
     * 能力声明（plan 4.1 表格 FTP 行 / plan 4.1 降级链）。
     *
     * `randomAccess`/`resumeByRest` 跟着 REST 能力走：探测到不支持就变 false，
     * 上层下次读 [caps] 就能看到并改走分段缓存。
     */
    override val caps: Caps
        get() {
            val rest = restSupport.get() != Capability.UNSUPPORTED
            return Caps(
                randomAccess = rest,
                rangeHeader = false,
                resumeByRest = rest,
                maxParallelReads = config.maxConnections,
                // FTP 通常可写；真实权限靠 write() 的 AccessDenied 反馈（R14 由上层落本地缓存）
                writable = true,
            )
        }

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ensureOpen()
        val base = normalizeFtpPath(dir)
        val abs = absoluteFtpPath(config.rootPath, base)
        val entries = pool.use { client -> listEntries(client, abs, base) }
        paginateFtp(entries.sortedWith(FTP_DIRECTORY_FIRST), page)
    }

    /**
     * 查询单个路径（FTP 没有 stat 命令：用父目录列表找同名项，这是 FTP 客户端的通行做法）。
     */
    override suspend fun stat(path: String): RemoteEntry = withContext(Dispatchers.IO) {
        ensureOpen()
        val relative = normalizeFtpPath(path)
        val abs = absoluteFtpPath(config.rootPath, relative)
        pool.use { client ->
            if (relative.isEmpty()) {
                // 根目录：能列出来就说明存在
                listEntries(client, abs, relative)
                return@use RemoteEntry(name = ftpNameOf(relative), path = relative, isDirectory = true, size = -1L)
            }
            val parent = ftpParentOf(relative)
            val parentAbs = absoluteFtpPath(config.rootPath, parent)
            val name = ftpNameOf(relative)
            listEntries(client, parentAbs, parent).firstOrNull { it.name == name }
                ?: throw StorageException.NotFound("FTP 路径不存在：" + path)
        }
    }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream = withContext(Dispatchers.IO) {
        ensureOpen()
        if (offset < 0L) throw StorageException.Unknown("offset 不能为负：" + offset)
        val relative = normalizeFtpPath(path)
        if (relative.isEmpty()) throw StorageException.NotSupported("目录不能读取：" + path)
        val abs = absoluteFtpPath(config.rootPath, relative)
        val entry = stat(path)
        if (entry.isDirectory) throw StorageException.NotSupported("目录不能读取：" + path)
        FtpRangeStream(
            pool = pool,
            config = config,
            remotePath = abs,
            start = offset,
            length = effectiveFtpLength(entry.size, offset, length),
            path = path,
            restSupported = { ensureRestSupport() },
            onRestUnsupported = { markRestUnsupported(path) },
        )
    }

    override suspend fun write(path: String, data: InputStream): Unit = withContext(Dispatchers.IO) {
        ensureOpen()
        val relative = normalizeFtpPath(path)
        if (relative.isEmpty()) throw StorageException.AccessDenied("不能写入目录：" + path)
        val abs = absoluteFtpPath(config.rootPath, relative)
        pool.use { client ->
            // FTP 的 STOR 失败应答（550/551）分不清「父目录不存在」和「无权限」，
            // 所以先显式确认父目录存在（CWD，一次往返），这样 R14 要的语义才准
            ensureParentDirectory(client, ftpParentOf(relative), path)
            val stored = try {
                // 调用方负责关闭 data（StorageBackend.write 的约定）
                client.storeFile(abs, data)
            } catch (e: IOException) {
                throw classifyIoFailure(e, config)
            }
            if (!stored) {
                throw classifyWriteFailure(client, path)
            }
        }
    }

    /**
     * 写之前确认父目录存在（**R14**：父目录不存在要给出 NotFound，而不是笼统的「写失败」）。
     *
     * @throws StorageException.NotFound 父目录不存在。
     * @throws StorageException.AccessDenied 父目录不可进入（无权限）。
     */
    private fun ensureParentDirectory(client: FTPClient, parent: String, displayPath: String) {
        val parentAbs = absoluteFtpPath(config.rootPath, parent)
        if (client.changeWorkingDirectory(parentAbs)) return
        val mapped = classifyReply(client, config, "父目录 " + parentAbs + "（写入 " + displayPath + "）")
        throw when (mapped) {
            is StorageException.Unknown -> StorageException.NotFound("FTP 父目录不存在：" + displayPath, mapped)
            else -> mapped
        }
    }

    /**
     * 连通性测试（**R8**，plan 4.5：DNS 解析 / TCP+220 banner / 登录 + PASV 三段计时）。
     *
     * 用**临时连接**探测，不干扰正在服役的连接池。**本方法不抛异常**（取消除外）：
     * - 530 → 「FTP 认证失败（530 账号或密码错误）」；
     * - 端口不通 → 「FTP 连接被拒绝」；
     * - 域名解析不了 → 「FTP DNS 解析失败」；
     * - 550 → 「FTP 路径不存在 / 无访问权限」。
     *
     * 顺带完成两项能力探测：**MLSD 是否可用**与 **REST 是否可用**（后者决定 [caps]）。
     */
    override suspend fun probe(): ProbeReport = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var dnsMs = 0L
        var connectMs = 0L
        val client = newFtpClient(config)
        try {
            try {
                val dnsStarted = System.nanoTime()
                try {
                    InetAddress.getAllByName(config.host)
                } finally {
                    // DNS 失败也要把耗时记进第一段（plan 4.5 的三段计时）
                    dnsMs = elapsedMs(dnsStarted)
                }
                client.setConnectTimeout(config.connectTimeoutMs.toInt())
                client.setDefaultTimeout(config.readTimeoutMs.toInt())
                client.setDataTimeout(java.time.Duration.ofMillis(config.readTimeoutMs))
                client.setControlEncoding(FtpClientPool.CONTROL_ENCODING)
                val connectStarted = System.nanoTime()
                try {
                    client.connect(config.host, config.port)
                } finally {
                    connectMs = elapsedMs(connectStarted)
                }
                if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                    return@withContext report(
                        ok = false,
                        message = "FTP 欢迎语异常（" + client.replyCode + " " + client.replyString.trim() + "），可能不是 FTP 服务",
                        dnsMs, connectMs, elapsedMs(started),
                    )
                }
                if (!client.login(config.username, config.password.orEmpty())) {
                    return@withContext report(
                        ok = false,
                        message = probeAuthMessage(client),
                        dnsMs, connectMs, elapsedMs(started),
                    )
                }
                client.setFileType(FTPClient.BINARY_FILE_TYPE)
                if (config.passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
                if (client is FTPSClient && config.ftpsMode.secure) {
                    client.execPBSZ(0)
                    client.execPROT("P")
                }
                // 第三段：登录完成之后的「列一次目录」——它会走 PASV，验证数据连接可用（plan 4.5「登录 + PASV」）
                probeCapabilities(client)
                try {
                    listEntries(client, config.rootPath, "")
                } catch (e: StorageException) {
                    return@withContext report(false, e.message, dnsMs, connectMs, elapsedMs(started))
                }
                val total = elapsedMs(started)
                AppLog.d(TAG_FTP, "probe 成功：" + config.host + ":" + config.port + " total=" + total + "ms")
                report(ok = true, message = null, dnsMs = dnsMs, connectMs = connectMs, totalMs = total)
            } catch (e: IOException) {
                val mapped = classifyIoFailure(e, config)
                report(false, mapped.message, dnsMs, connectMs, elapsedMs(started))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // R16：界面只给中文；原始异常留给 AppLog
            report(false, "FTP 未知错误：" + ErrorText.of(t, "详情见诊断日志"), dnsMs, connectMs, elapsedMs(started))
        } finally {
            runCatching { client.disconnect() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pool.close()
    }

    // ------------------------------------------------------------------ 列目录

    /**
     * 列一层目录：MLSD 优先，失败/不支持退回 LIST（plan 4.1）。
     *
     * @throws StorageException.NotFound 目录不存在（550）。
     */
    private fun listEntries(client: FTPClient, abs: String, base: String): List<RemoteEntry> {
        val entries = readEntries(client, abs, base)
        if (entries.isEmpty()) {
            // FTP 的 LIST/MLSD 对「目录不存在」并不统一：有的服务器回 550，有的回一个空列表。
            // 所以空结果要用 CWD 再确认一次，否则「没有这个目录」会被当成「空目录」（R8 要求错误分类准确）。
            if (!client.changeWorkingDirectory(abs)) {
                throw classifyReply(client, config, abs)
            }
        }
        return entries
    }

    /** 真正取一次目录内容（MLSD 优先，退回 LIST）。 */
    private fun readEntries(client: FTPClient, abs: String, base: String): List<RemoteEntry> {
        if (mlsdSupport.get() != Capability.UNSUPPORTED) {
            val lines = runCatching { (client as RawListSource).fetchListLines("MLSD", abs, CHARSET) }.getOrNull()
            if (lines != null) {
                mlsdSupport.compareAndSet(Capability.UNKNOWN, Capability.SUPPORTED)
                return parseMlsd(lines).map { it.toRemoteEntry(base) }
            }
            val code = client.replyCode
            if (isCommandUnsupported(code)) {
                if (mlsdSupport.compareAndSet(Capability.UNKNOWN, Capability.UNSUPPORTED)) {
                    AppLog.i(TAG_FTP, "服务器不支持 MLSD（" + code + "），改用 LIST：" + config.host)
                }
            } else {
                throw classifyReply(client, config, abs)
            }
        }
        val lines = runCatching { (client as RawListSource).fetchListLines("LIST", abs, CHARSET) }.getOrNull()
            ?: throw classifyReply(client, config, abs)
        return parseList(lines).map { it.toRemoteEntry(base) }
    }

    private fun FtpListEntry.toRemoteEntry(base: String): RemoteEntry = RemoteEntry(
        name = name,
        path = joinFtpPath(base, name),
        isDirectory = isDirectory,
        size = if (isDirectory) -1L else size,
        mtime = mtimeMs,
        // FTP 协议没有版本号/ETag；缓存失效靠 (path, size, mtime)（plan 4.2 / 第 7 章）
        etag = null,
        mimeType = null,
    )

    // ------------------------------------------------------------------ 能力探测

    /** REST 是否可用（未知时按「可用」乐观处理，被拒后由 [markRestUnsupported] 降级）。 */
    private fun ensureRestSupport(): Boolean = restSupport.get() != Capability.UNSUPPORTED

    /** 服务器拒绝 REST：降级 caps，上层改走分段缓存（plan 4.1）。 */
    private fun markRestUnsupported(path: String) {
        if (restSupport.compareAndSet(Capability.UNKNOWN, Capability.UNSUPPORTED)) {
            AppLog.w(TAG_FTP, "服务器不支持 REST，随机读降级为顺序跳过（上层应走分段缓存）：" + path)
        }
    }

    /**
     * 用一条已经登录好的控制连接探测 MLSD 与 REST 能力。
     *
     * REST 的探测办法是直接发一次 `REST 0`：350 = 支持（进入等待传输状态，偏移 0 等价于不设），
     * 500/502 = 不支持。这样不用真的传一次文件就能把 [caps] 定下来。
     */
    private fun probeCapabilities(client: FTPClient) {
        val restReply = runCatching { client.sendCommand("REST", "0") }.getOrDefault(-1)
        val supported = FTPReply.isPositiveIntermediate(restReply)
        restSupport.compareAndSet(Capability.UNKNOWN, if (supported) Capability.SUPPORTED else Capability.UNSUPPORTED)
        if (!supported) {
            AppLog.i(TAG_FTP, "服务器不支持 REST（应答 " + restReply + "），随机读将降级：" + config.host)
        }
    }

    private fun isCommandUnsupported(code: Int): Boolean = code == 500 || code == 501 || code == 502 || code == 504

    // ------------------------------------------------------------------ 错误与报告

    /**
     * 写失败的细分（**R14**）。
     *
     * 父目录此时已经确认存在，所以 550/551/553 这一类「服务器不让写」统一归
     * [StorageException.AccessDenied]（上层据此把字幕落到本地缓存并提示）；
     * 只有应答文本明确写着 not found 才归 NotFound。
     */
    private fun classifyWriteFailure(client: FTPClient, path: String): StorageException {
        val code = client.replyCode
        val text = client.replyString.lowercase()
        return when {
            text.contains("no such") || text.contains("not found") || text.contains("does not exist") ->
                StorageException.NotFound("FTP 路径不存在（" + code + "）：" + path)
            code == 550 || code == 551 || code == 553 ->
                StorageException.AccessDenied("FTP 无写权限（" + code + " " + client.replyString.trim() + "）：" + path)
            else -> classifyReply(client, config, path)
        }
    }

    private fun probeAuthMessage(client: FTPClient): String =
        if (client.replyCode == 530 || client.replyCode == 532) {
            "FTP 认证失败（" + client.replyCode + " 账号或密码错误）"
        } else {
            "FTP 认证失败（" + client.replyCode + " " + client.replyString.trim() + "）"
        }

    /** 组装三段计时报告：三段相加 = 总耗时（plan 4.5）。 */
    private fun report(ok: Boolean, message: String?, dnsMs: Long, connectMs: Long, totalMs: Long): ProbeReport {
        val dnsFailure = !ok && message != null && message.contains("DNS")
        if (dnsFailure) {
            return ProbeReport(ok = false, dnsMs = maxOf(dnsMs, totalMs), connectMs = 0L, handshakeMs = 0L, message = message)
        }
        val handshake = (totalMs - dnsMs - connectMs).coerceAtLeast(0L)
        return ProbeReport(ok = ok, dnsMs = dnsMs, connectMs = connectMs, handshakeMs = handshake, message = message)
    }

    private fun ensureOpen() {
        if (closed) throw StorageException.Unknown("后端已关闭：" + id)
    }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        /** FTP 控制连接的编码：UTF-8 才能正确处理中文文件名（R14 中文视频名 + 同名字幕）。 */
        val CHARSET: Charset = Charsets.UTF_8
    }
}

/** 服务器能力探测的三态（未知 / 支持 / 不支持）。 */
internal enum class Capability {
    UNKNOWN,
    SUPPORTED,
    UNSUPPORTED,
}
