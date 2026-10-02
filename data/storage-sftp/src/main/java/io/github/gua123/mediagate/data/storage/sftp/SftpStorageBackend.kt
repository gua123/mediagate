package io.github.gua123.mediagate.data.storage.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.model.Caps
import io.github.gua123.mediagate.core.model.ProbeReport
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.Page
import io.github.gua123.mediagate.data.storage.api.RangeStream
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import java.io.IOException
import java.io.InputStream

/**
 * SFTP 存储后端（**R2** 四协议之一 / **R4** 拖拽随机读 / **R7** 多地址 / **R8** 连通性测试 / **R14** 字幕写回）。
 *
 * 基于 JSch（mwiede 分支 2.28.7，plan 第 2 章选型）：
 * - 列目录：`ChannelSftp.ls`（SFTP readdir），目录优先 + 名称排序 + 分页，口径与
 *   `:data:storage-local` 的 FileStorageBackend 完全一致；
 * - 随机读：`SSH_FXP_READ + offset`（真按偏移读，见 [SftpRangeStream]），拖拽 seek 不重下；
 * - 写回：SFTP PUT（R14 字幕写回视频同目录；父目录不存在 → NotFound、无权限 → AccessDenied）；
 * - 会话：**单会话复用 + 通道池**（[SftpChannelPool]，plan 4.1），断线自动重连且不丢 seek；
 * - 主机密钥：**TOFU + 变更告警**（plan 4.9，[TofuHostKeyVerifier]），绝不静默接受变更；
 * - 连通性：probe() 三段计时（DNS / TCP / banner + 认证 + 列目录），**不抛异常**。
 *
 * 所有 JSch 调用都是阻塞 I/O，一律包在 `Dispatchers.IO` 里（接口要求不阻塞调用方线程）。
 *
 * @param config 连接配置。
 * @param verifier 主机密钥校验器；默认按 [SftpConfig.hostKeyPolicy] 建一个内存版 TOFU 校验器
 *   （上层要跨进程记住指纹时传自己的 [TofuHostKeyVerifier] + [KnownHostsStore] 即可）。
 */
class SftpStorageBackend(
    val config: SftpConfig,
    val verifier: HostKeyVerifier = TofuHostKeyVerifier(
        store = InMemoryKnownHostsStore(),
        policy = config.hostKeyPolicy,
    ),
) : StorageBackend {

    private val pool = SftpChannelPool(config, verifier)

    /**
     * 文件属性缓存（**2026-10-03 性能修复**）。
     *
     * 起因：用户实测公网 SFTP ≈0.9 MB/s、WebDAV ≈0.1 MB/s，而公网**每次请求延迟约 600 ms**
     * （连接测试里"握手"那段就是它）。而 [openRead] 每次都要先 stat 一次拿大小——
     * 分段缓存按 1 MB 块取数 ⇒ **每 1 MB 白付一个 RTT**，在 600 ms 延迟下直接砍掉约四成吞吐。
     *
     * 做法与分段缓存的大小缓存同理：会话内文件几乎不变，按绝对路径缓存 128 条。
     */
    private val attrsCache = object : LinkedHashMap<String, SftpATTRS>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SftpATTRS>?): Boolean = size > 128
    }

    /** 取属性：先查缓存，未命中才走一次网络。 */
    private suspend fun attrsOf(abs: String, display: String): SftpATTRS {
        synchronized(attrsCache) { attrsCache[abs] }?.let { return it }
        val attrs = pool.use { channel -> statOrThrow(channel, abs, display) }
        synchronized(attrsCache) { attrsCache[abs] = attrs }
        return attrs
    }

    /** 后端唯一标识（R2 缓存 key）：`sftp://用户名@主机:端口/根路径`。 */
    override val id: String = config.id

    /**
     * 能力声明（plan 4.1 表格 SFTP 行）。
     *
     * SFTP 天生支持任意偏移随机读（`SSH_FXP_READ` 自带 offset），所以 `randomAccess = true`；
     * `rangeHeader` / `resumeByRest` 是 HTTP / FTP 的概念，对 SFTP 无意义，恒 false；
     * 并发上限 = 通道池大小（单会话下的折中，避免把服务器打爆）。
     */
    override val caps: Caps = Caps(
        randomAccess = true,
        rangeHeader = false,
        resumeByRest = false,
        maxParallelReads = config.maxChannels,
        // SFTP 通常可写；真实权限靠 write() 的 AccessDenied 反馈（R14 由上层落本地缓存）
        writable = true,
    )

    @Volatile
    private var closed = false

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ensureOpen()
        val base = normalizeSftpPath(dir)
        val absDir = absoluteSftpPath(config.rootPath, base)
        val entries = pool.use { channel ->
            // 先 stat：既能立刻分辨「不存在 / 不是目录 / 无权限」，又避免 ls 对文件返回单条
            val attrs = statOrThrow(channel, absDir, dir)
            if (!attrs.isDir) throw StorageException.NotSupported("不是目录：" + dir)
            readDirectory(channel, absDir, base)
        }
        paginateSftp(entries.sortedWith(SFTP_DIRECTORY_FIRST), page)
    }

    override suspend fun stat(path: String): RemoteEntry = withContext(Dispatchers.IO) {
        ensureOpen()
        val relative = normalizeSftpPath(path)
        val abs = absoluteSftpPath(config.rootPath, relative)
        pool.use { channel ->
            val attrs = statOrThrow(channel, abs, path)
            toEntry(relative, attrs)
        }
    }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream = withContext(Dispatchers.IO) {
        ensureOpen()
        if (offset < 0L) throw StorageException.Unknown("offset 不能为负：" + offset)
        val relative = normalizeSftpPath(path)
        if (relative.isEmpty()) throw StorageException.NotSupported("目录不能读取：" + path)
        val abs = absoluteSftpPath(config.rootPath, relative)
        // 走缓存：同一文件连续取多块时不再每块都 stat 一次（高延迟链路上这是纯亏的 RTT）
        val attrs = attrsOf(abs, path)
        if (attrs.isDir) throw StorageException.NotSupported("目录不能读取：" + path)
        if (length == 0L) return@withContext SftpRangeStream(pool, abs, offset, 0L, path)
        // 越界（offset 已在末尾之后）不报错：length 会被夹成 0，read 直接返回 -1（与本地后端同语义）
        SftpRangeStream(pool, abs, offset, effectiveSftpLength(attrs.size, offset, length), path)
    }

    override suspend fun write(path: String, data: InputStream): Unit = withContext(Dispatchers.IO) {
        ensureOpen()
        val relative = normalizeSftpPath(path)
        if (relative.isEmpty()) throw StorageException.AccessDenied("不能写入目录：" + path)
        val abs = absoluteSftpPath(config.rootPath, relative)
        pool.use { channel ->
            val output = try {
                // SFTP OPEN(WRITE|CREATE|TRUNC)：父目录不存在或无权限会在这一步直接报错（R14 语义）
                channel.put(abs)
            } catch (e: SftpException) {
                throw mapSftpFailure(e, path)
            } catch (e: IOException) {
                throw mapSftpIoFailure(e, path, pool.lastHostKeyCheck)
            }
            var failure: Throwable? = null
            try {
                // 调用方负责关闭 data（StorageBackend.write 的约定），这里只读不关
                data.copyTo(output)
            } catch (t: Throwable) {
                failure = t
            }
            try {
                output.close()
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
            failure?.let { throw mapWriteFailure(it, path) }
        }
    }

    /**
     * 连通性测试（**R8**，plan 4.5：DNS 解析 / TCP 握手 / 协议握手三段计时）。
     *
     * 第三段 = SSH banner + 密钥交换 + 认证 + 列一次目录（`ls` 根路径，plan 4.5 的口径）。
     * **本方法不抛异常**（取消除外），全部结论放 [ProbeReport]：
     * - 主机密钥变更/未知 → 「证书不受信任」，界面红色告警（plan 4.9）；
     * - 认证失败 → 「认证失败（账号或密码错误）」；
     * - 端口错/超时/DNS 错 → 「连接被拒绝 / 连接或读取超时 / DNS 解析失败」。
     *
     * 用**临时会话**探测，不干扰正在服役的通道池（也保证三段耗时是这一次的）。
     */
    override suspend fun probe(): ProbeReport = withContext(Dispatchers.IO) {
        val timing = TimingSocketFactory(config.connectTimeoutMs.toInt())
        val probePool = SftpChannelPool(config, verifier, timing)
        val started = System.nanoTime()
        try {
            probePool.use { channel -> channel.ls(config.rootPath) }
            val total = elapsedMs(started)
            report(ok = true, message = null, timing = timing, totalMs = total)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val total = elapsedMs(started)
            val message = probeMessage(t)
            AppLog.w(TAG, "probe 失败：" + config.host + ":" + config.port + " → " + message)
            report(ok = false, message = message, timing = timing, totalMs = total, failure = t)
        } finally {
            runCatching { probePool.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pool.close()
    }

    // ------------------------------------------------------------------ 内部

    /** 需要「文件总长度」时的 stat（openRead 用）。 */
    private fun statOrThrow(channel: ChannelSftp, abs: String, displayPath: String): SftpATTRS = try {
        channel.stat(abs)
    } catch (e: SftpException) {
        throw mapSftpFailure(e, displayPath)
    } catch (e: IOException) {
        throw mapSftpIoFailure(e, displayPath, pool.lastHostKeyCheck)
    }

    /** 读一层目录：过滤 `.` / `..`，符号链接尽量解析成目标类型（目录才进得去）。 */
    private fun readDirectory(channel: ChannelSftp, absDir: String, base: String): List<RemoteEntry> {
        val raw = try {
            channel.ls(absDir)
        } catch (e: SftpException) {
            throw mapSftpFailure(e, base.ifEmpty { "/" })
        } catch (e: IOException) {
            throw mapSftpIoFailure(e, base.ifEmpty { "/" }, pool.lastHostKeyCheck)
        }
        val result = ArrayList<RemoteEntry>(raw.size)
        for (entry in raw) {
            val name = entry.filename ?: continue
            if (name == "." || name == "..") continue
            var attrs = entry.attrs
            if (attrs != null && attrs.isLink) {
                // 符号链接按目标判定（指向目录的链接要能点进去）；解析失败就退回链接自身属性
                attrs = runCatching { channel.stat(joinRemotePath(absDir, name)) }.getOrDefault(attrs)
            }
            if (attrs == null) continue
            result.add(toEntry(joinSftpPath(base, name), attrs))
        }
        return result
    }

    /** SFTP 属性 → 统一目录项（目录 size 记 -1；mtime 由秒转毫秒）。 */
    private fun toEntry(relative: String, attrs: SftpATTRS): RemoteEntry {
        val isDirectory = attrs.isDir
        return RemoteEntry(
            name = sftpNameOf(relative),
            path = relative,
            isDirectory = isDirectory,
            size = if (isDirectory) -1L else attrs.size,
            // SFTP 的 mtime 是秒（Unix 时间戳），统一 API 用毫秒
            mtime = attrs.mTime.toLong() * 1000L,
            // SFTP 协议没有版本号/ETag；缓存失效靠 (path, size, mtime)（plan 4.2 / 第 7 章）
            etag = null,
            mimeType = null,
        )
    }

    /** 拼远端绝对路径（[dir] 已归一化，可能带尾斜杠）。 */
    private fun joinRemotePath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else dir + "/" + name

    /** 写失败分类：握手/传输类异常走统一翻译，SftpException 走状态字翻译（R14 的 NotFound/AccessDenied）。 */
    private fun mapWriteFailure(t: Throwable, path: String): StorageException = when (t) {
        is StorageException -> t
        is SftpException -> mapSftpFailure(t, path)
        is IOException -> mapSftpIoFailure(t, path, pool.lastHostKeyCheck)
        else -> StorageException.Unknown("SFTP 写入失败：" + path, t)
    }

    /** 组装 probe 报告：三段相加 ≈ 总耗时（plan 4.5）。 */
    private fun report(
        ok: Boolean,
        message: String?,
        timing: TimingSocketFactory,
        totalMs: Long,
        failure: Throwable? = null,
    ): ProbeReport {
        val dnsFailure = failure != null && deepCause(failure) is java.net.UnknownHostException
        if (dnsFailure) {
            // DNS 都没过：整段耗时都算 DNS，三段相加仍等于总耗时
            return ProbeReport(ok = ok, dnsMs = maxOf(timing.dnsMs, totalMs), connectMs = 0L, handshakeMs = 0L, message = message)
        }
        val dns = timing.dnsMs
        val connect = timing.connectMs
        val handshake = (totalMs - dns - connect).coerceAtLeast(0L)
        return ProbeReport(ok = ok, dnsMs = dns, connectMs = connect, handshakeMs = handshake, message = message)
    }

    /** probe 失败原因：优先用已翻译好的中文消息（[mapJschFailure] / [mapSftpFailure] 产出）。 */
    private fun probeMessage(t: Throwable): String = when (t) {
        is StorageException -> t.message ?: t.javaClass.simpleName
        is SftpException -> mapSftpFailure(t, config.rootPath).message ?: describeThrowable(t)
        else -> mapJschFailure(t, config, pool.lastHostKeyCheck, "probe").message ?: describeThrowable(t)
    }

    private fun ensureOpen() {
        if (closed) throw StorageException.Unknown("后端已关闭：" + id)
    }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        const val TAG = "storage-sftp"
    }
}
