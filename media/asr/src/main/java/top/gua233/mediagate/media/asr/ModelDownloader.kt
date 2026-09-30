package io.github.gua123.mediagate.media.asr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次 HTTP GET 请求（**M7-B / R14** 的 App 内下载）。
 *
 * @property url 目标地址。
 * @property rangeStart 断点续传的起点（> 0 时发 HTTP Range 头，只取这一段）。
 */
data class HttpRequest(
    val url: String,
    val rangeStart: Long? = null,
) {
    /** Range 头；不需要时 null。 */
    val rangeHeader: String? get() = rangeStart?.takeIf { it > 0L }?.let { "bytes=" + it + "-" }
}

/**
 * 一次 HTTP 响应（**只暴露下载需要的字段**）。
 *
 * @property code HTTP 状态码。
 * @property contentLength 本次响应的字节数；未知 -1。
 * @property contentRangeStart 206 时 Content-Range 里的起点；没有则 null。
 * @property etag ETag（诊断用）。
 */
interface HttpStream : Closeable {

    val code: Int
    val contentLength: Long
    val contentRangeStart: Long?
    val etag: String?

    /** 读一块；返回 -1 表示读完。 */
    fun read(buffer: ByteArray): Int
}

/**
 * HTTP 传输抽象（**下载逻辑能被 JVM 单测穷举的关键**）。
 *
 * 选型说明（为什么不用 OkHttp）：:media:asr 只需要「带 Range 的顺序 GET」，不需要连接池、
 * 拦截器、HTTP/2；引入 OkHttp 会给一个纯媒体模块拖进 okio 与另一套版本约束。
 * java.net.HttpURLConnection 是 JDK/Android 自带的，零依赖，且这里把它关在一个单方法接口
 * 后面——单测灌一个假实现（假 HTTP）就能把断点续传、取消、校验和失败重下全跑一遍。
 */
interface HttpTransport {

    /** 发起请求并返回响应流（调用方负责 close）。失败抛 [IOException]。 */
    suspend fun open(request: HttpRequest): HttpStream
}

/**
 * 真机实现：java.net.HttpURLConnection（**唯一碰网络的地方**）。
 *
 * @param connectTimeoutMs 连接超时（默认 15 s）。
 * @param readTimeoutMs 读超时（默认 30 s；大文件一段一段读，不能设太短）。
 * @param userAgent 带项目标识，便于日后从服务端日志分辨。
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpTransport {

    override suspend fun open(request: HttpRequest): HttpStream {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            request.rangeHeader?.let { setRequestProperty("Range", it) }
        }
        val responseCode = connection.responseCode
        val headerLength = connection.getHeaderFieldLong("Content-Length", -1L)
        val rangeStart = parseContentRangeStart(connection.getHeaderField("Content-Range"))
        val etagValue = connection.getHeaderField("ETag")
        val body: InputStream? = runCatching {
            if (responseCode in 200..299) connection.inputStream else connection.errorStream
        }.getOrNull()
        return object : HttpStream {
            override val code: Int = responseCode
            override val contentLength: Long = headerLength
            override val contentRangeStart: Long? = rangeStart
            override val etag: String? = etagValue

            override fun read(buffer: ByteArray): Int = body?.read(buffer) ?: -1

            override fun close() {
                runCatching { body?.close() }
                connection.disconnect()
            }
        }
    }

    companion object {

        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_USER_AGENT = "mediagate-android/0.1 (asr-model-downloader)"

        /** 解析 Content-Range 头（形如 bytes 100-999/1000）的起点（**纯函数**）。 */
        fun parseContentRangeStart(header: String?): Long? {
            val text = header?.trim().orEmpty()
            if (!text.startsWith("bytes", ignoreCase = true)) return null
            val range = text.substringAfter(' ').substringBefore('/')
            val start = range.substringBefore('-').trim()
            return start.toLongOrNull()
        }
    }
}

/** 下载失败的分类（每一种都有中文说明，界面直接显示）。 */
enum class ModelDownloadError(val zhText: String) {

    /** 网络中断 / 连不上 / 超时。 */
    NETWORK("网络中断"),

    /** 服务器返回非 200/206。 */
    HTTP("服务器返回错误"),

    /** 下载完的字节数与官方大小不符（多半是被截断）。 */
    SIZE_MISMATCH("下载不完整"),

    /** 校验和与预期不符（文件被改坏 / 中间有代理插了内容）。 */
    CHECKSUM_MISMATCH("校验和不符"),

    /** 本地写不进去（磁盘满 / 目录不可写）。 */
    STORAGE("本地写入失败"),
}

/** 下载异常（带分类，便于界面给中文原因）。 */
class ModelDownloadException(
    val kind: ModelDownloadError,
    detail: String? = null,
    cause: Throwable? = null,
) : IOException(if (detail.isNullOrBlank()) kind.zhText else kind.zhText + "：" + detail, cause)

/**
 * 下载进度（**R14：模型下载要显示进度**）。
 *
 * @property receivedBytes 已下载字节数（含断点续传前已有的部分）。
 * @property totalBytes 总字节数（官方大小，已知）。
 * @property resumedFrom 本次开始时已有的字节数（> 0 表示是续传）。
 */
data class ModelDownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long,
    val resumedFrom: Long = 0L,
) {
    /** 0..1。 */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (receivedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()

    /** 0..100。 */
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)

    /** 是否续传中。 */
    val isResuming: Boolean get() = resumedFrom > 0L
}

/**
 * App 内下载模型（**M7-B / R14 的默认获取方式**：进度 / 断点续传 / 取消 / 校验和失败重下）。
 *
 * 流程（每一步都在 JVM 单测里用假 [HttpTransport] 加真临时目录走查过）：
 * 1. 先下到「文件名 + .part」；已存在就从它的长度**续传**（发 Range 头）；
 * 2. 服务端回 206 → 接着写；回 200 → 说明它不支持 Range，**从头重下**（不拼接，避免脏数据）；
 * 3. 每读一块检查协程是否被取消：取消时**保留 .part**（下次还能续），不写坏最终文件；
 * 4. 字节数必须等于 [WhisperModel.sizeBytes]，有 [WhisperModel.sha256] 时再核一遍；
 * 5. 校验不过 → **删掉 .part 并抛错**（下次从头重下，不会拿着坏文件反复失败）；
 * 6. 都过了才改名为最终文件名。
 */
class ModelDownloader(
    private val store: ModelStore,
    private val transport: HttpTransport,
) {

    /**
     * 下载 [model]，成功返回最终文件名（用 [ModelStore.pathOf] 取绝对路径）。
     *
     * @param mirror 镜像前缀；null 表示直连。
     * @param onProgress 进度回调（在下载协程里同步调用，别做重活）。
     * @throws ModelDownloadException 分类失败。
     * @throws kotlinx.coroutines.CancellationException 用户取消（.part 保留）。
     */
    suspend fun download(
        model: WhisperModel,
        mirror: String? = null,
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): String {
        val partName = partNameOf(model)
        var received = existingPartBytes(partName, model)
        onProgress(ModelDownloadProgress(received, model.sizeBytes, received))

        if (received < model.sizeBytes) {
            val request = HttpRequest(model.downloadUrl(mirror), rangeStart = received.takeIf { it > 0L })
            val stream = try {
                transport.open(request)
            } catch (e: IOException) {
                throw ModelDownloadException(ModelDownloadError.NETWORK, e.message, e)
            }
            stream.use { response ->
                when (response.code) {
                    HttpURLConnection.HTTP_OK -> {
                        // 服务端忽略了 Range：从头写，绝不把新内容接在旧内容后面
                        received = 0L
                    }

                    HttpURLConnection.HTTP_PARTIAL -> Unit

                    else -> throw ModelDownloadException(ModelDownloadError.HTTP, "HTTP " + response.code)
                }
                writeBody(model, partName, stream, received, onProgress)
            }
        }

        verify(model, partName)
        if (!store.rename(partName, model.fileName)) {
            // 罕见：改名失败（目标被占 / 权限）。至少把内容留在 .part，报 STORAGE 让用户重试。
            throw ModelDownloadException(ModelDownloadError.STORAGE, "重命名 " + partName + " 失败")
        }
        return model.fileName
    }

    /** 已经下了多少（.part 长度）；超过官方大小说明是坏文件，删掉重来。 */
    private fun existingPartBytes(partName: String, model: WhisperModel): Long {
        if (!store.exists(partName)) return 0L
        val size = store.size(partName)
        if (size <= 0L || size > model.sizeBytes) {
            store.delete(partName)
            return 0L
        }
        return size
    }

    /** 边读边写；每块检查一次取消。 */
    private suspend fun writeBody(
        model: WhisperModel,
        partName: String,
        stream: HttpStream,
        startBytes: Long,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        var received = startBytes
        try {
            store.openWrite(partName, append = startBytes > 0L).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    received += read
                    onProgress(ModelDownloadProgress(received, model.sizeBytes, startBytes))
                }
                output.flush()
            }
        } catch (e: IOException) {
            throw ModelDownloadException(ModelDownloadError.STORAGE, e.message, e)
        }
    }

    /** 字节数加可选 SHA-256 校验；不过就删掉 .part 让下次从头下。 */
    private fun verify(model: WhisperModel, partName: String) {
        val size = store.size(partName)
        if (size != model.sizeBytes) {
            store.delete(partName)
            throw ModelDownloadException(
                ModelDownloadError.SIZE_MISMATCH,
                "实际 " + size + " 字节，预期 " + model.sizeBytes + " 字节",
            )
        }
        val expected = model.sha256?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        val actual = store.sha256(partName)
        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
            store.delete(partName)
            throw ModelDownloadException(ModelDownloadError.CHECKSUM_MISMATCH, "校验和不符，已删除损坏文件")
        }
    }

    /** 断点续传用的临时文件名。 */
    fun partNameOf(model: WhisperModel): String = model.fileName + WhisperModel.PART_SUFFIX

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
