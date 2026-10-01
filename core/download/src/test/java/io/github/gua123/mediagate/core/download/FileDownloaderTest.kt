package io.github.gua123.mediagate.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [FileDownloader] 的 JVM 单测（**R20** 应用内下载：进度 / 断点续传 / 校验 / 取消）。
 *
 * 全部用**假 HTTP**（[FakeTransport]）加**真临时目录**：续传、服务端不支持 Range、校验失败重下
 * 这些分支不需要真机也不需要网络。
 */
class FileDownloaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val payload = ByteArray(4096) { index -> (index % 251).toByte() }

    private fun target(): File = File(temporary.root, "mediagate.apk")

    private fun partOf(file: File): File = FileDownloader.partFileOf(file)

    @Test
    fun `首次下载写满后改名删除临时文件`() = runTest {
        val file = target()
        val progress = mutableListOf<DownloadProgress>()

        FileDownloader(FakeTransport(payload)).download(
            url = "https://example.test/a.apk",
            target = file,
            expectedBytes = payload.size.toLong(),
            onProgress = { progress += it },
        )

        assertArrayEquals(payload, file.readBytes())
        assertFalse("成功后不该留下 .part", partOf(file).exists())
        assertEquals(payload.size.toLong(), progress.last().receivedBytes)
        assertEquals(100, progress.last().percent)
        assertFalse("首次下载不是续传", progress.first().isResuming)
    }

    @Test
    fun `已有 part 时按 Range 续传并保留前缀`() = runTest {
        val file = target()
        val half = payload.size / 2
        partOf(file).writeBytes(payload.copyOfRange(0, half))
        val transport = FakeTransport(payload)
        val progress = mutableListOf<DownloadProgress>()

        FileDownloader(transport).download(
            url = "https://example.test/a.apk",
            target = file,
            expectedBytes = payload.size.toLong(),
            onProgress = { progress += it },
        )

        assertEquals("必须发 Range 头", "bytes=" + half + "-", transport.requests.first().rangeHeader)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(half.toLong(), progress.first().resumedFrom)
        assertTrue(progress.first().isResuming)
    }

    @Test
    fun `服务端不支持 Range 时从头重下而不是拼接`() = runTest {
        val file = target()
        val half = payload.size / 2
        partOf(file).writeBytes(payload.copyOfRange(0, half))

        FileDownloader(FakeTransport(payload, supportsRange = false)).download(
            url = "https://example.test/a.apk",
            target = file,
            expectedBytes = payload.size.toLong(),
        )

        assertArrayEquals("回 200 时必须整份重写", payload, file.readBytes())
    }

    @Test
    fun `字节数不符时报下载不完整并删掉 part`() = runTest {
        val file = target()
        val error = runCatching {
            FileDownloader(FakeTransport(payload)).download(
                url = "https://example.test/a.apk",
                target = file,
                expectedBytes = payload.size + 10L,
            )
        }.exceptionOrNull()

        assertTrue("应抛 DownloadException：$error", error is DownloadException)
        assertEquals(DownloadError.SIZE_MISMATCH, (error as DownloadException).kind)
        assertFalse("坏文件必须删掉，下次从头下", partOf(file).exists())
        assertFalse(file.exists())
    }

    @Test
    fun `校验和不符时报错并删掉 part`() = runTest {
        val file = target()
        val error = runCatching {
            FileDownloader(FakeTransport(payload)).download(
                url = "https://example.test/a.apk",
                target = file,
                expectedBytes = payload.size.toLong(),
                expectedSha256 = "deadbeef",
            )
        }.exceptionOrNull()

        assertEquals(DownloadError.CHECKSUM_MISMATCH, (error as DownloadException).kind)
        assertFalse(partOf(file).exists())
    }

    @Test
    fun `校验和正确时安装文件可用`() = runTest {
        val file = target()
        val digest = FileDownloader.sha256Of(File(temporary.root, "probe.bin").apply { writeBytes(payload) })
        assertTrue(digest != null && digest.length == 64)

        FileDownloader(FakeTransport(payload)).download(
            url = "https://example.test/a.apk",
            target = file,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = digest,
        )

        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `HTTP 错误码与网络异常分类清晰`() = runTest {
        val http = runCatching {
            FileDownloader(FakeTransport(payload, statusCode = 404)).download("u", target())
        }.exceptionOrNull()
        assertEquals(DownloadError.HTTP, (http as DownloadException).kind)

        val network = runCatching {
            FileDownloader(FakeTransport(payload, failWith = IOException("connect reset"))).download("u", target())
        }.exceptionOrNull()
        assertEquals(DownloadError.NETWORK, (network as DownloadException).kind)
    }

    @Test
    fun `取消时保留 part 便于下次续传`() = runTest {
        val file = target()
        val error = runCatching {
            FileDownloader(CancelAfterBytesTransport(payload, cancelAfter = 1024)).download(
                url = "https://example.test/a.apk",
                target = file,
                expectedBytes = payload.size.toLong(),
            )
        }.exceptionOrNull()

        assertTrue("取消必须是 CancellationException：$error", error is CancellationException)
        assertTrue("下到一半的内容要保留", partOf(file).isFile)
        assertEquals(1024L, partOf(file).length())
        assertFalse("最终文件不能出现", file.exists())
    }

    @Test
    fun `Content-Range 解析与 Range 头生成`() {
        assertEquals(100L, HttpUrlConnectionTransport.parseContentRangeStart("bytes 100-999/1000"))
        assertNull(HttpUrlConnectionTransport.parseContentRangeStart(null))
        assertNull(HttpUrlConnectionTransport.parseContentRangeStart("bytes */1000"))
        assertNull(HttpUrlConnectionTransport.parseContentRangeStart("items 1-2/3"))
        assertNull(HttpRequest("u").rangeHeader)
        assertNull("0 起点不发 Range 头", HttpRequest("u", rangeStart = 0L).rangeHeader)
        assertEquals("bytes=1024-", HttpRequest("u", rangeStart = 1024L).rangeHeader)
    }

    @Test
    fun `下载错误都有中文文案`() {
        DownloadError.entries.forEach { error ->
            assertTrue("缺中文文案：" + error.name, error.zhText.any { it.code in 0x4E00..0x9FFF })
        }
        assertEquals("校验和不符", DownloadError.CHECKSUM_MISMATCH.zhText)
        assertTrue(DownloadException(DownloadError.NETWORK).message!!.contains("网络中断"))
        assertTrue(DownloadException(DownloadError.HTTP, "HTTP 500").message!!.contains("HTTP 500"))
    }
}

/** 假 HTTP：按请求头决定回 200 还是 206（[supportsRange] = false 时模拟"忽略 Range 的服务器"）。 */
private class FakeTransport(
    private val payload: ByteArray,
    private val statusCode: Int = 200,
    private val chunkSize: Int = 512,
    private val supportsRange: Boolean = true,
    private val failWith: IOException? = null,
) : HttpTransport {

    val requests = mutableListOf<HttpRequest>()

    override suspend fun open(request: HttpRequest): HttpStream {
        requests += request
        failWith?.let { throw it }
        val start = request.rangeStart ?: 0L
        return when {
            start > 0L && supportsRange ->
                BytesStream(206, start, payload.copyOfRange(start.toInt(), payload.size), chunkSize)
            start > 0L -> BytesStream(200, null, payload, chunkSize)
            else -> BytesStream(statusCode, null, payload, chunkSize)
        }
    }
}

/** 读若干字节后抛 [CancellationException]，模拟"用户点了取消 / 协程被取消"。 */
private class CancelAfterBytesTransport(
    private val payload: ByteArray,
    private val cancelAfter: Int,
) : HttpTransport {

    override suspend fun open(request: HttpRequest): HttpStream = object : HttpStream {
        private var offset = 0
        override val code: Int = 200
        override val contentLength: Long = payload.size.toLong()
        override val contentRangeStart: Long? = null
        override val etag: String? = null

        override fun read(buffer: ByteArray): Int {
            if (offset >= cancelAfter) throw CancellationException("用户取消")
            val read = minOf(buffer.size, cancelAfter - offset)
            System.arraycopy(payload, offset, buffer, 0, read)
            offset += read
            return read
        }

        override fun close() = Unit
    }
}

/** 内存响应流：按 [chunkSize] 分块吐 [body]。 */
private class BytesStream(
    override val code: Int,
    override val contentRangeStart: Long?,
    private val body: ByteArray,
    private val chunkSize: Int,
) : HttpStream {

    private var offset = 0

    override val contentLength: Long = body.size.toLong()
    override val etag: String? = null

    override fun read(buffer: ByteArray): Int {
        if (offset >= body.size) return -1
        val read = minOf(chunkSize, body.size - offset, buffer.size)
        System.arraycopy(body, offset, buffer, 0, read)
        offset += read
        return read
    }

    override fun close() = Unit
}
