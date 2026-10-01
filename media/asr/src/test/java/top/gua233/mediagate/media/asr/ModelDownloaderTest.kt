package io.github.gua123.mediagate.media.asr

import io.github.gua123.mediagate.core.download.DownloadError
import io.github.gua123.mediagate.core.download.DownloadException
import io.github.gua123.mediagate.core.download.DownloadProgress
import io.github.gua123.mediagate.core.download.HttpRequest
import io.github.gua123.mediagate.core.download.HttpStream
import io.github.gua123.mediagate.core.download.HttpTransport
import io.github.gua123.mediagate.core.download.HttpUrlConnectionTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

/**
 * 模型下载与管理的 JVM 单测（**M7-B / R14**）。
 *
 * 全部用**假 HTTP**（[FakeTransport]）+ **真临时目录**（[FileModelStore] + TemporaryFolder）：
 * 断点续传、取消、校验和失败重下这些分支不需要真机也不需要网络。
 */
class ModelDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val payload = ByteArray(4_096) { index -> (index % 251).toByte() }

    private fun model(size: Long = payload.size.toLong(), sha256: String? = null) = WhisperModel(
        id = "test",
        label = "测试模型",
        fileName = "ggml-test.bin",
        sizeBytes = size,
        url = "https://example.invalid/ggml-test.bin",
        sha256 = sha256,
    )

    private fun store() = FileModelStore(folder.newFolder("models"))

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun download_writesFileAndRemovesPartFile() = runTest {
        val store = store()
        val transport = FakeTransport(payload)
        val name = ModelDownloader(store, transport).download(model())

        assertEquals("ggml-test.bin", name)
        assertTrue(store.exists("ggml-test.bin"))
        assertFalse(store.exists("ggml-test.bin.part"))
        assertEquals(payload.toList(), store.openRead("ggml-test.bin").readBytes().toList())
        assertNull(transport.requests.single().rangeStart)
    }

    @Test
    fun download_resumesFromExistingPartFile() = runTest {
        val store = store()
        val half = payload.size / 2
        store.openWrite("ggml-test.bin.part", append = false).use { it.write(payload, 0, half) }
        val transport = FakeTransport(payload)

        val progress = mutableListOf<DownloadProgress>()
        ModelDownloader(store, transport).download(model()) { progress += it }

        assertEquals(half.toLong(), transport.requests.single().rangeStart)
        assertEquals(payload.toList(), store.openRead("ggml-test.bin").readBytes().toList())
        assertTrue(progress.first().isResuming)
        assertEquals(payload.size.toLong(), progress.last().receivedBytes)
        assertEquals(100, progress.last().percent)
    }

    @Test
    fun download_restartsWhenServerIgnoresRange() = runTest {
        val store = store()
        val half = payload.size / 2
        store.openWrite("ggml-test.bin.part", append = false).use { it.write(payload, 0, half) }
        val transport = FakeTransport(payload, supportRange = false)

        ModelDownloader(store, transport).download(model())

        // 服务端回 200 → 从头写，不能出现「旧的一半 + 新的一份」拼起来
        assertEquals(payload.size.toLong(), store.size("ggml-test.bin"))
        assertEquals(payload.toList(), store.openRead("ggml-test.bin").readBytes().toList())
    }

    @Test
    fun download_failsWithSizeMismatchAndDeletesPart() = runTest {
        val store = store()
        val error = runCatching {
            ModelDownloader(store, FakeTransport(payload)).download(model(size = payload.size + 10L))
        }.exceptionOrNull()

        assertTrue(error is DownloadException)
        assertEquals(DownloadError.SIZE_MISMATCH, (error as DownloadException).kind)
        assertFalse(store.exists("ggml-test.bin.part"))
        assertFalse(store.exists("ggml-test.bin"))
    }

    @Test
    fun download_failsWithChecksumMismatchAndDeletesPart() = runTest {
        val store = store()
        val error = runCatching {
            ModelDownloader(store, FakeTransport(payload)).download(model(sha256 = "deadbeef"))
        }.exceptionOrNull()

        assertEquals(DownloadError.CHECKSUM_MISMATCH, (error as DownloadException).kind)
        assertFalse(store.exists("ggml-test.bin.part"))
    }

    @Test
    fun download_acceptsMatchingChecksum() = runTest {
        val store = store()
        val name = ModelDownloader(store, FakeTransport(payload))
            .download(model(sha256 = sha256Of(payload)))
        assertEquals("ggml-test.bin", name)
        assertTrue(store.exists("ggml-test.bin"))
    }

    @Test
    fun download_reportsHttpError() = runTest {
        val store = store()
        val error = runCatching {
            ModelDownloader(store, FakeTransport(payload, codeOverride = 404)).download(model())
        }.exceptionOrNull()
        assertEquals(DownloadError.HTTP, (error as DownloadException).kind)
    }

    @Test
    fun download_reportsNetworkError() = runTest {
        val store = store()
        val error = runCatching {
            ModelDownloader(store, FakeTransport(payload, failWith = IOException("connect reset"))).download(model())
        }.exceptionOrNull()
        assertEquals(DownloadError.NETWORK, (error as DownloadException).kind)
    }

    @Test
    fun download_keepsPartFileWhenCancelled() = runTest {
        val store = store()
        val transport = FakeTransport(payload, chunkSize = 512)
        val error = runCatching {
            val downloader = ModelDownloader(store, transport)
            downloader.download(model()) { progress ->
                // 收到第二块就取消：模拟用户在下载中点「取消」
                if (progress.receivedBytes > 512L) throw kotlinx.coroutines.CancellationException("用户取消")
            }
        }.exceptionOrNull()

        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertTrue(store.exists("ggml-test.bin.part"))
        assertFalse(store.exists("ggml-test.bin"))
        // 保留的半截文件正好可以下次续传
        assertTrue(store.size("ggml-test.bin.part") > 0L)
    }

    @Test
    fun download_discardsOversizedPartFile() = runTest {
        val store = store()
        store.openWrite("ggml-test.bin.part", append = false).use { it.write(ByteArray(8_192)) }
        val transport = FakeTransport(payload)

        ModelDownloader(store, transport).download(model())

        // 比官方大小还大的 .part 一定是坏的：删掉重下，不发 Range
        assertNull(transport.requests.single().rangeStart)
        assertEquals(payload.toList(), store.openRead("ggml-test.bin").readBytes().toList())
    }

    @Test
    fun download_progressIsMonotonic() = runTest {
        val store = store()
        val progress = mutableListOf<DownloadProgress>()
        ModelDownloader(store, FakeTransport(payload, chunkSize = 256)).download(model()) { progress += it }
        assertTrue(progress.size > 2)
        progress.zipWithNext { a, b -> assertTrue(b.receivedBytes >= a.receivedBytes) }
        assertEquals(payload.size.toLong(), progress.last().totalBytes)
    }

    @Test
    fun partNameOf_appendsPartSuffix() {
        val downloader = ModelDownloader(store(), FakeTransport(payload))
        assertEquals("ggml-small.bin.part", downloader.partNameOf(WhisperModel.SMALL))
    }

    @Test
    fun parseContentRangeStart_readsOnlyValidHeaders() {
        assertEquals(100L, HttpUrlConnectionTransport.parseContentRangeStart("bytes 100-999/1000"))
        assertNull(HttpUrlConnectionTransport.parseContentRangeStart(null))
        assertNull(HttpUrlConnectionTransport.parseContentRangeStart("bytes */1000"))
        assertNull(HttpUrlConnectionTransport.parseContentRangeStart("items 1-2/3"))
    }

    @Test
    fun rangeHeader_isBuiltOnlyForPositiveStart() {
        assertNull(HttpRequest("u").rangeHeader)
        assertNull(HttpRequest("u", rangeStart = 0L).rangeHeader)
        assertEquals("bytes=1024-", HttpRequest("u", rangeStart = 1024L).rangeHeader)
    }

    @Test
    fun downloadErrorMessagesAreChinese() {
        assertTrue(DownloadError.NETWORK.zhText.isNotEmpty())
        assertEquals("校验和不符", DownloadError.CHECKSUM_MISMATCH.zhText)
    }

    /** 假 HTTP：把一段内存字节当成远端文件，可选是否支持 Range。 */
    private class FakeTransport(
        private val payload: ByteArray,
        private val supportRange: Boolean = true,
        private val codeOverride: Int? = null,
        private val failWith: IOException? = null,
        private val chunkSize: Int = 1024,
    ) : HttpTransport {

        val requests = mutableListOf<HttpRequest>()

        override suspend fun open(request: HttpRequest): HttpStream {
            requests += request
            failWith?.let { throw it }
            val requested = request.rangeStart ?: 0L
            val start = if (supportRange) requested else 0L
            val code = codeOverride ?: if (supportRange && requested > 0L) 206 else 200
            val body = payload.copyOfRange(start.toInt().coerceAtMost(payload.size), payload.size)
            return object : HttpStream {
                override val code: Int = code
                override val contentLength: Long = body.size.toLong()
                override val contentRangeStart: Long? = if (code == 206) start else null
                override val etag: String? = null
                private var position = 0

                override fun read(buffer: ByteArray): Int {
                    if (position >= body.size) return -1
                    val count = minOf(buffer.size, body.size - position, chunkSize)
                    System.arraycopy(body, position, buffer, 0, count)
                    position += count
                    return count
                }

                override fun close() = Unit
            }
        }
    }
}

/** [ModelManager] 与 [WhisperModel] 的单测（R14 的查询 / 体检 / 导入）。 */
class ModelManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bytes = ByteArray(1_024) { index -> index.toByte() }

    private val model = WhisperModel(
        id = "test",
        label = "测试",
        fileName = "ggml-test.bin",
        sizeBytes = bytes.size.toLong(),
        url = "https://example.invalid/ggml-test.bin",
    )

    private fun storeWithFile() = FileModelStore(folder.newFolder("m")).also { store ->
        store.openWrite(model.fileName, append = false).use { it.write(bytes) }
    }

    @Test
    fun isInstalled_andInstalledIds_reflectRealFiles() {
        val empty = ModelManager(FileModelStore(folder.newFolder("empty")))
        assertFalse(empty.isInstalled(WhisperModel.SMALL))
        assertTrue(empty.installed().isEmpty())
        assertNull(empty.fileOf(WhisperModel.SMALL))

        val store = storeWithFile()
        val manager = ModelManager(store)
        assertTrue(manager.isInstalled(model))
        assertNotNull(manager.fileOf(model))
        // installed() 只列官方三个档位；自定义测试模型不算在内
        assertTrue(manager.installed().isEmpty())
    }

    @Test
    fun verify_reportsMissingSizeAndOk() {
        val manager = ModelManager(storeWithFile())
        assertEquals(ModelVerifyResult.OK, manager.verify(model))
        assertEquals(ModelVerifyResult.MISSING, manager.verify(WhisperModel.SMALL))
        assertEquals(ModelVerifyResult.SIZE_MISMATCH, manager.verify(model.copy(sizeBytes = 2_048L)))
        assertTrue(ModelVerifyResult.OK.usable)
        assertFalse(ModelVerifyResult.MISSING.usable)
    }

    @Test
    fun verify_checksChecksumWhenGiven() {
        val manager = ModelManager(storeWithFile())
        assertEquals(
            ModelVerifyResult.CHECKSUM_MISMATCH,
            manager.verify(model.withChecksum("0000")),
        )
    }

    @Test
    fun delete_removesModelAndPartFile() {
        val store = storeWithFile()
        store.openWrite(model.fileName + WhisperModel.PART_SUFFIX, append = false).use { it.write(ByteArray(4)) }
        val manager = ModelManager(store)
        assertTrue(manager.delete(model))
        assertFalse(store.exists(model.fileName))
        assertFalse(store.exists(model.fileName + WhisperModel.PART_SUFFIX))
        assertFalse(manager.delete(model))
    }

    @Test
    fun importFrom_rejectsWrongSize() {
        val source = folder.newFile("wrong.bin").apply { writeBytes(ByteArray(10)) }
        val result = ModelManager(storeWithFile()).importFrom(source, model)
        assertTrue(result is ModelImportResult.Rejected)
        assertTrue((result as ModelImportResult.Rejected).reason.contains("大小不符"))
    }

    @Test
    fun importFrom_rejectsMissingSource() {
        val result = ModelManager(storeWithFile()).importFrom(java.io.File(folder.root, "nope.bin"), model)
        assertTrue(result is ModelImportResult.Rejected)
    }

    @Test
    fun importFrom_copiesValidFile() {
        val source = folder.newFile("ok.bin").apply { writeBytes(bytes) }
        val store = FileModelStore(folder.newFolder("target"))
        val result = ModelManager(store).importFrom(source, model)
        assertTrue(result is ModelImportResult.Imported)
        assertEquals(bytes.toList(), store.openRead(model.fileName).readBytes().toList())
    }

    @Test
    fun usedBytes_sumsDirectory() {
        val manager = ModelManager(storeWithFile())
        assertEquals(bytes.size.toLong(), manager.usedBytes())
    }

    @Test
    fun download_withoutDownloaderFails() = runTest {
        val error = runCatching { ModelManager(storeWithFile()).download(model) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}

/** [WhisperModel] 档位定义的纯单测。 */
class WhisperModelTest {

    @Test
    fun defaultIsSmall() {
        assertEquals(WhisperModel.SMALL, WhisperModel.DEFAULT)
        assertEquals("small", WhisperModel.DEFAULT.id)
        assertEquals(WhisperModel.SMALL, WhisperModel.of(null))
        assertEquals(WhisperModel.SMALL, WhisperModel.of("不存在"))
        assertEquals(WhisperModel.TINY, WhisperModel.of("tiny"))
    }

    @Test
    fun allLevelsAreOrderedAndSized() {
        assertEquals(listOf("tiny", "base", "small"), WhisperModel.ALL.map { it.id })
        assertTrue(WhisperModel.TINY.sizeBytes < WhisperModel.BASE.sizeBytes)
        assertTrue(WhisperModel.BASE.sizeBytes < WhisperModel.SMALL.sizeBytes)
        assertTrue(WhisperModel.ALL.all { it.fileName.startsWith("ggml-") && it.url.startsWith("https://") })
        assertEquals(74L, WhisperModel.TINY.sizeMb)
    }

    @Test
    fun downloadUrl_appliesMirrorPrefix() {
        val model = WhisperModel.SMALL
        assertEquals("https://gh-proxy.com/" + model.url, model.downloadUrl(WhisperModel.DEFAULT_MIRROR))
        assertEquals(model.url, model.downloadUrl(null))
        assertEquals(model.url, model.downloadUrl(""))
    }

    @Test
    fun withChecksum_normalizesAndFlags() {
        assertFalse(WhisperModel.SMALL.hasChecksum)
        val checked = WhisperModel.SMALL.withChecksum("ABCDEF")
        assertTrue(checked.hasChecksum)
        assertEquals("abcdef", checked.sha256)
    }

    @Test
    fun ofFileName_findsKnownModel() {
        assertEquals(WhisperModel.BASE, WhisperModel.ofFileName("ggml-base.bin"))
        assertNull(WhisperModel.ofFileName("ggml-unknown.bin"))
    }
}
