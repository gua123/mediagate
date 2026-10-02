package io.github.gua123.mediagate.data.storage.webdav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebDAV 存储后端（R2 四协议之一 / R4 拖拽随机读 / R7 多地址 / R8 连通性测试 / R14 字幕写回）。
 *
 * 用 OkHttp 自研 PROPFIND + Range + PUT（plan 第 2 章选型），不引第三方 WebDAV 库：
 * - 列目录 / stat：PROPFIND（Depth: 1 / 0），命名空间无关地解析 207 multistatus；
 * - 随机读：GET + `Range`，每次 seek 一个新请求（[WebDavRangeStream]，plan 4.1）；
 * - 写回：PUT（R14 字幕写回视频同目录，父目录必须已存在）；
 * - 连通性：probe() 三段计时（DNS / TCP / 协议握手），**不抛异常**，失败信息放 [ProbeReport.message]。
 *
 * 同步 OkHttp API 一律包在 `Dispatchers.IO` 里（接口要求不阻塞调用方线程）。
 *
 * 能力探测：[caps] 的 `randomAccess`/`rangeHeader` 初始按「支持」乐观给出，第一次 GET 之后
 * 依据响应（206 / Accept-Ranges / 服务器忽略 Range 回 200）在**实例上缓存**并降级，上层下次读 [caps]
 * 就能看到 false，从而走 plan 4.1 的分段缓存降级链。
 *
 * @param config 连接配置（地址 / 凭据 / 根路径 / 超时 / 附加头）。
 */
class WebDavStorageBackend(val config: WebDavConfig) : StorageBackend {

    private val client: OkHttpClient = defaultWebDavClient(config)

    /**
     * 后端唯一标识（R2 缓存 key）：`webdav:<请求基址>`。
     *
     * 同一个物理位置不管怎么填（尾斜杠、rootPath 写进 baseUrl 还是单独给）都得到同一个 id，
     * 这样 TS 索引与缩略图缓存不会因为配置写法不同而各存一份（plan 第 7 章）。
     */
    override val id: String = ID_PREFIX + config.requestBaseUrl

    /** `null` = 还没探测过（乐观认为支持），false = 服务器不支持 Range（已降级）。 */
    private val rangeSupport = AtomicReference<Boolean?>(null)

    @Volatile
    private var closed = false

    override val caps: Caps
        get() {
            val range = rangeSupport.get() != false
            return Caps(
                randomAccess = range,
                rangeHeader = range,
                resumeByRest = false,
                maxParallelReads = MAX_PARALLEL_READS,
                // WebDAV 理论上可写；真实权限靠 write() 的 AccessDenied 反馈（R14 由上层落本地缓存）
                writable = true,
            )
        }

    override suspend fun list(dir: String, page: Page?): List<RemoteEntry> = withContext(Dispatchers.IO) {
        ensureOpen()
        val base = normalizeWebDavPath(dir)
        val entries = propfind(base, depth = DEPTH_CHILDREN).map { it.toRemoteEntry() }
        // Depth:1 的响应里一定含目录自身；它若不是 collection，说明调用方把文件当目录列了
        val self = entries.firstOrNull { it.path == base }
        if (self != null && !self.isDirectory) throw StorageException.NotSupported("不是目录：$dir")
        val children = entries
            .filter { it.path.isNotEmpty() && it.path != base }
            .sortedWith(DIRECTORY_FIRST)
        paginateWebDav(children, page)
    }

    override suspend fun stat(path: String): RemoteEntry = withContext(Dispatchers.IO) {
        ensureOpen()
        val target = normalizeWebDavPath(path)
        val dav = propfind(target, depth = DEPTH_SELF).firstOrNull()
            ?: throw StorageException.NotFound("路径不存在：$path")
        // 路径与名字以调用方给的为准（服务器回的 href 可能带大小写/编码差异），属性取服务器的
        RemoteEntry(
            name = if (target.isEmpty()) rootName() else target.substringAfterLast('/'),
            path = target,
            isDirectory = dav.isDirectory,
            size = dav.size,
            mtime = dav.mtime,
            etag = dav.etag,
            mimeType = dav.mimeType,
        )
    }

    override suspend fun openRead(path: String, offset: Long, length: Long): RangeStream = withContext(Dispatchers.IO) {
        ensureOpen()
        if (offset < 0L) throw StorageException.Unknown("offset 不能为负：$offset")
        if (path.endsWith("/")) throw StorageException.NotSupported("目录不能读取：$path")
        val target = normalizeWebDavPath(path)
        if (target.isEmpty()) throw StorageException.NotSupported("目录不能读取：$path")
        if (length == 0L) return@withContext EmptyRangeStream()
        // 立刻发第一个 Range 请求：404 / 不是文件 在这里就报出来，且 length 立刻准确
        WebDavRangeStream.open(this@WebDavStorageBackend, target, offset, length)
    }

    override suspend fun write(path: String, data: InputStream): Unit = withContext(Dispatchers.IO) {
        ensureOpen()
        val target = normalizeWebDavPath(path)
        if (target.isEmpty() || path.endsWith("/")) {
            throw StorageException.AccessDenied("不能写入目录：$path")
        }
        // R14 的字幕文件很小（几百 KB），整块读入是为了给出准确的 Content-Length：
        // 分块 PUT 会被一部分 WebDAV 服务器直接 411 拒掉
        val bytes = data.readBytes()
        val url = buildFileUrl(config.requestBaseUrl, target)
        val request = newRequest(url).put(bytes.toRequestBody(PUT_MEDIA_TYPE)).build()
        val response = try {
            execute(request)
        } catch (e: IOException) {
            throw mapIoFailure(e, "PUT $url")
        }
        response.use {
            when (it.code) {
                in 200..299 -> Unit
                401 -> throw StorageException.Auth("401 账号或密码错误：PUT $url")
                403, 423 -> throw StorageException.AccessDenied("403 无写权限（或资源被锁）：$path")
                404, 409 -> throw StorageException.NotFound("父目录不存在（HTTP ${it.code}）：$path")
                405, 501 -> throw StorageException.NotSupported("服务器不允许 PUT（HTTP ${it.code}）：$path")
                507 -> throw StorageException.Unknown("远端存储空间不足：$path")
                else -> throw statusException(it.code, "PUT $url")
            }
        }
    }

    /**
     * 连通性测试（R8，plan 4.5）：DNS 解析 / TCP 连接 / PROPFIND 握手三段计时。
     *
     * 期望 207（multistatus）或 200；401 = 账号密码错、403 = 无权限、404 = 路径不对。
     * **本方法不抛异常**（取消除外），全部结论放 [ProbeReport]。
     */
    override suspend fun probe(): ProbeReport = withContext(Dispatchers.IO) {
        val listener = DavTimingListener()
        val probeClient = client.newBuilder().eventListener(listener).build()
        val url = buildFileUrl(config.requestBaseUrl, "", collection = true)
        val started = System.nanoTime()
        try {
            val request = newRequest(url)
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", DEPTH_SELF.toString())
                .build()
            // 走我们自己的重定向跟随（不能让 OkHttp 把 PROPFIND 降级成 GET）；
            // 失败时把"收到了什么"一起带出来（真机排查用，见 probeFailureMessage 的注释）。
            execute(request, probeClient).use { response ->
                val total = elapsedMs(started)
                updateRangeSupport(response, sentRange = false)
                val snippet = if (response.isSuccessful) {
                    null
                } else {
                    runCatching { response.peekBody(PROBE_SNIPPET_BYTES).string() }.getOrNull()
                }
                val report = buildReport(
                    code = response.code,
                    listener = listener,
                    totalMs = total,
                    httpMessage = response.message,
                    url = request.url.toString(),
                    bodySnippet = snippet,
                )
                if (!report.ok) AppLog.w(TAG, "probe 失败：${config.requestBaseUrl} → ${report.message}")
                report
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val total = elapsedMs(started)
            val dns = listener.dnsMs
            val connect = listener.connectMs
            val handshake = (total - dns - connect).coerceAtLeast(0L)
            val message = when (t) {
                is UnknownHostException -> "DNS 解析失败（${config.host}）"
                is SocketTimeoutException, is java.io.InterruptedIOException ->
                    "连接或读取超时（${config.connectTimeoutMs} ms / ${config.readTimeoutMs} ms）"
                // R16：界面只给中文；异常的原始英文留在 AppLog 的 probe 异常日志里。
                // 刻意不再统一加「网络不可达：」前缀——明文被系统拦、认证失败都不是"不可达"，
                // 加错前缀会把用户引去查网线（2026-10-02 真机截图就是这么误导的）。
                is IOException -> ErrorText.of(t, "网络请求失败")
                else -> ErrorText.of(t, "请求失败")
            }
            AppLog.w(TAG, "probe 异常：${config.requestBaseUrl} → $message", t)
            // DNS 失败时监听器收不到 dnsEnd：把整段耗时算进 DNS，保证三段相加 ≈ 总耗时
            val dnsFailure = t is UnknownHostException
            ProbeReport(
                ok = false,
                dnsMs = if (dnsFailure) (if (dns > 0L) dns else total) else dns,
                connectMs = connect,
                handshakeMs = if (dnsFailure) 0L else handshake,
                message = message,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 客户端是我们自己建的，关掉时把它的线程池与连接池一起收掉
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * 发起一次 GET + Range，返回**尚未对齐**的响应窗口；返回 null 表示 416（偏移已在文件末尾之后）。
     *
     * 供 [WebDavRangeStream] 调用（内部方法，不属公开 API）；调用方负责在 Dispatchers.IO 上执行。
     */
    internal fun getRange(path: String, absOffset: Long, spanLength: Long): WebDavWindow? {
        val url = buildFileUrl(config.requestBaseUrl, path)
        val range = buildRangeHeader(absOffset, spanLength)
        val request = newRequest(url).header("Range", range).get().build()
        val response = try {
            execute(request)
        } catch (e: IOException) {
            throw mapIoFailure(e, "GET $url")
        }
        try {
            val code = response.code
            if (code == 416) {
                response.close()
                return null
            }
            if (code == 207) throw StorageException.NotSupported("目标是目录，不是文件：$path")
            if (code !in 200..299) throw statusException(code, "GET $url")
            val contentType = response.header("Content-Type").orEmpty()
            if (code == 200 && contentType.startsWith("text/html", ignoreCase = true)) {
                // 对集合发 GET 时多数服务器回 200 + HTML 目录页；用 Content-Type 做基本判断
                throw StorageException.NotSupported("目标是目录（服务器返回 HTML 目录页）：$path")
            }
            updateRangeSupport(response, sentRange = true)
            val contentLength = response.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            val contentRange = parseContentRange(response.header("Content-Range"))
            val startAbs: Long
            val total: Long
            val capacity: Long
            when {
                code == 206 && contentRange != null -> {
                    startAbs = contentRange.start
                    total = contentRange.total
                    capacity = contentRange.end - contentRange.start + 1L
                }
                // 206 但没给 Content-Range：只能认为响应体从请求的偏移开始
                code == 206 -> {
                    startAbs = absOffset
                    total = -1L
                    capacity = contentLength
                }
                // 200：服务器忽略了 Range，回的是整份内容，本地跳过 absOffset（caps 已降级）
                else -> {
                    startAbs = 0L
                    total = contentLength
                    capacity = contentLength
                }
            }
            return WebDavWindow(response, response.body.byteStream(), startAbs, total, capacity)
        } catch (t: Throwable) {
            response.close()
            throw t
        }
    }

    /** PROPFIND（Depth = 1 列目录 / 0 查单条）；调用方负责在 Dispatchers.IO 上执行。 */
    private fun propfind(path: String, depth: Int): List<DavEntry> {
        // 列集合时补尾斜杠：Apache 之类会对不带尾斜杠的集合回 301，多一趟往返
        val url = buildFileUrl(config.requestBaseUrl, path, collection = depth > 0)
        val request = newRequest(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", depth.toString())
            .build()
        val response = try {
            execute(request)
        } catch (e: IOException) {
            throw mapIoFailure(e, "PROPFIND $url")
        }
        response.use {
            if (it.code !in 200..299) throw statusException(it.code, "PROPFIND $url")
            updateRangeSupport(it, sentRange = false)
            val bytes = it.body.bytes()
            return try {
                parseMultistatus(bytes)
            } catch (e: StorageException) {
                throw e
            } catch (t: Throwable) {
                throw StorageException.Unknown("PROPFIND 响应解析失败：$url", t)
            }
        }
    }

    /** 统一构造请求：附加自定义头 + Basic 认证 + 禁 gzip（Range 偏移必须按原始字节算）。 */
    private fun newRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept-Encoding", ACCEPT_ENCODING_IDENTITY)
        for ((name, value) in config.extraHeaders) builder.header(name, value)
        if (config.useBasicAuth) {
            builder.header("Authorization", Credentials.basic(config.username.orEmpty(), config.password.orEmpty()))
        }
        return builder
    }

    /**
     * 执行请求并**手动**跟随重定向。
     *
     * 不能用 OkHttp 的自动跟随：它会把重定向后的 PROPFIND 降级成 GET（目录少了尾斜杠就会 301），
     * 跨主机时还会摘掉 Authorization。这里保持原方法重发（303 按 HTTP 语义转 GET）。
     */
    private fun execute(request: Request, callClient: OkHttpClient = client): Response {
        var current = request
        var hops = 0
        while (true) {
            val response = callClient.newCall(current).execute()
            val code = response.code
            val location = if (code in REDIRECT_CODES) response.header("Location") else null
            if (location.isNullOrBlank()) return response
            if (hops++ >= MAX_REDIRECTS) {
                response.close()
                throw StorageException.Network("重定向次数过多（> $MAX_REDIRECTS）：${request.url}")
            }
            val next = current.url.resolve(location)
            response.close()
            if (next == null) throw StorageException.Unknown("无法解析重定向地址：$location")
            val keepMethod = code != 303 || current.method == "GET" || current.method == "HEAD"
            val method = if (keepMethod) current.method else "GET"
            current = current.newBuilder()
                .url(next)
                .method(method, if (keepMethod) current.body else null)
                .build()
        }
    }

    /**
     * 把「服务器支不支持 Range」缓存在实例上（plan 4.1：探测 Accept-Ranges 决定 caps.randomAccess）。
     *
     * 判据优先级：206 → 支持；发了 Range 却回 200 → 不支持（已降级）；再看 Accept-Ranges 头。
     */
    private fun updateRangeSupport(response: Response, sentRange: Boolean) {
        val code = response.code
        val accept = response.header("Accept-Ranges")?.trim()?.lowercase(Locale.US)
        val known = when {
            code == 206 -> true
            sentRange && code == 200 -> false
            accept == null -> null
            accept.contains("bytes") -> true
            accept.contains("none") -> false
            else -> null
        } ?: return
        val previous = rangeSupport.getAndSet(known)
        if (known.not() && previous != false) {
            AppLog.w(TAG, "服务器忽略 Range（Accept-Ranges=${accept ?: "-"}），降级为顺序读：${config.requestBaseUrl}")
        }
    }

    /** PROPFIND 响应里的一条 → 统一目录项（href → 树内路径；名字取末段）。 */
    private fun DavEntry.toRemoteEntry(): RemoteEntry {
        val path = hrefToPath(href, config.basePath)
        return RemoteEntry(
            name = path.substringAfterLast('/'),
            path = path,
            isDirectory = isDirectory,
            size = size,
            mtime = mtime,
            etag = etag,
            mimeType = mimeType,
        )
    }

    /** 根目录自身的展示名：取请求基址最后一段，取不到就用 `/`。 */
    private fun rootName(): String {
        val fromBase = config.basePath.trimEnd('/').substringAfterLast('/')
        return fromBase.ifEmpty { "/" }
    }

    private fun ensureOpen() {
        if (closed) throw StorageException.Unknown("后端已关闭：$id")
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    /** 按状态码给出 probe 结论（plan 4.5：207 或 200 算通，401 = 账号密码错）。 */
    private fun buildReport(
        code: Int,
        listener: DavTimingListener,
        totalMs: Long,
        httpMessage: String,
        url: String = "",
        bodySnippet: String? = null,
    ): ProbeReport {
        val dns = listener.dnsMs
        val connect = listener.connectMs
        val handshake = (totalMs - dns - connect).coerceAtLeast(0L)
        val message = when (code) {
            207, 200 -> null
            401 -> "401 账号或密码错误"
            403 -> "403 无访问权限（账号对该目录无读权限）"
            404 -> "404 路径不存在（检查根路径是否写对）"
            405, 501 -> "$code 服务器不支持 PROPFIND（可能不是 WebDAV 服务）"
            // 其他状态（3xx/5xx/4xx 边角）把「请求地址 + 响应片段」带上：用户与我都能一眼定位
            else -> probeFailureMessage(code, httpMessage, url, bodySnippet)
        }
        return ProbeReport(ok = message == null, dnsMs = dns, connectMs = connect, handshakeMs = handshake, message = message)
    }

    /**
     * OkHttp 事件监听器：只干一件事——把 DNS 与 TCP 两段的耗时累加起来（plan 4.5 的三段计时）。
     *
     * 第三段「协议握手」由调用方用「整段耗时 - 前两段」得到，这样三段相加 ≈ 总耗时，
     * 连接被复用（没有 dns/connect 事件）时也能退化成「全部算握手」。
     */
    private class DavTimingListener : EventListener() {

        var dnsMs: Long = 0L
            private set

        var connectMs: Long = 0L
            private set

        private var dnsStart: Long = -1L
        private var connectStart: Long = -1L

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = System.nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (dnsStart >= 0L) {
                dnsMs += (System.nanoTime() - dnsStart) / 1_000_000L
                dnsStart = -1L
            }
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart = System.nanoTime()
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            finishConnect()
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            finishConnect()
        }

        private fun finishConnect() {
            if (connectStart >= 0L) {
                connectMs += (System.nanoTime() - connectStart) / 1_000_000L
                connectStart = -1L
            }
        }
    }

    companion object {
        private const val TAG = "storage-webdav"
        private const val ID_PREFIX = "webdav:"

        /** 探针失败时最多回看多少字节的响应体（塞进提示里给用户看，见 [probeFailureMessage]）。 */
        private const val PROBE_SNIPPET_BYTES = 512L

        /** Depth: 1 = 列子项；Depth: 0 = 只看自己。 */
        private const val DEPTH_CHILDREN = 1
        private const val DEPTH_SELF = 0

        /**
         * 建议并行读上限（plan 4.1 的 Caps.maxParallelReads）。
         *
         * HTTP 没有会话约束，但媒体场景（播放 + 抽帧 + 缩略图）同时开太多 Range 请求会互相抢带宽，
         * 4 是局域网下的折中；OkHttp 侧的 maxRequestsPerHost 给到 8 留出余量。
         */
        private const val MAX_PARALLEL_READS = 4
    }
}

/** PROPFIND / PUT 的 Content-Type（okhttp 的 Kotlin 扩展）。 */
private val XML_MEDIA_TYPE = XML_CONTENT_TYPE.toMediaType()

/** PUT 的 Content-Type。 */
private val PUT_MEDIA_TYPE = PUT_CONTENT_TYPE.toMediaType()

/**
 * 建默认 OkHttp 客户端（plan 4.2：连接 5 s / 读取 15 s，可配）。
 *
 * 这里显式关掉自动重定向（[WebDavStorageBackend.execute] 自己跟），其余保持 OkHttp 默认。
 */
internal fun defaultWebDavClient(config: WebDavConfig): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
    .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(true)
    .dispatcher(
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 8
        },
    )
    .build()
