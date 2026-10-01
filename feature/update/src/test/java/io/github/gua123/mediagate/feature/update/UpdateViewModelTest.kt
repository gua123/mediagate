package io.github.gua123.mediagate.feature.update

import io.github.gua123.mediagate.core.download.FileDownloader
import io.github.gua123.mediagate.core.download.HttpRequest
import io.github.gua123.mediagate.core.download.HttpStream
import io.github.gua123.mediagate.core.download.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [UpdateViewModel] 的 JVM 单测（**R20**）。
 *
 * 用假 HTTP（清单与 APK 两个 URL 分别响应）加真临时目录，把状态机跑全：
 * 检查 → 有新版 → 下载（进度）→ 签名校验 → 待安装；以及签名不一致、取消保留断点两条安全分支。
 */
class UpdateViewModelTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val payload = ByteArray(8192) { index -> (index % 97).toByte() }
    /** 直接算 SHA-256：不依赖 TemporaryFolder 先被创建（属性初始化早于 @Rule 生效）。 */
    private val sha: String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { byte -> "%02x".format(byte) }

    private val manifestJson = """
        {
          "versionCode": 2,
          "versionName": "0.1.1",
          "apkUrl": "https://github.com/gua123/mediagate/releases/download/v0.1.1/mediagate-0.1.1.apk",
          "sizeBytes": ${payload.size},
          "sha256": "$sha",
          "notes": "SFTP/FTP 接线修复"
        }
    """.trimIndent()

    @Test
    fun `检查后发现新版本`() = runTest {
        val viewModel = viewModel(signature = SignatureCheck.Match)
        viewModel.check()
        advanceUntilIdle()

        val status = viewModel.state.value.status
        assertTrue("应是 Available：$status", status is UpdateStatus.Available)
        assertEquals(2L, (status as UpdateStatus.Available).manifest.versionCode)
        assertTrue(viewModel.state.value.canDownload)
    }

    @Test
    fun `同版本时提示已是最新`() = runTest {
        val viewModel = viewModel(manifest = manifestJson.replace("\"versionCode\": 2", "\"versionCode\": 1"))
        viewModel.check()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.status is UpdateStatus.UpToDate)
    }

    @Test
    fun `下载成功且签名一致时进入待安装`() = runTest {
        val viewModel = viewModel(signature = SignatureCheck.Match)
        viewModel.check()
        advanceUntilIdle()
        viewModel.download()
        advanceUntilIdle()

        val status = viewModel.state.value.status
        assertTrue("应是 Downloaded：$status", status is UpdateStatus.Downloaded)
        val file = (status as UpdateStatus.Downloaded).file
        assertTrue("安装包要落盘", file.isFile)
        assertEquals(payload.size.toLong(), file.length())
        assertTrue("大小与校验和都过了才会到这里", viewModel.state.value.canInstall)
    }

    @Test
    fun `签名不一致时删包并如实提示`() = runTest {
        val viewModel = viewModel(signature = SignatureCheck.Mismatch("aa", "bb"))
        viewModel.check()
        advanceUntilIdle()
        viewModel.download()
        advanceUntilIdle()

        val status = viewModel.state.value.status
        assertTrue("必须判失败：$status", status is UpdateStatus.Failed)
        assertTrue((status as UpdateStatus.Failed).message.contains("签名"))
        assertFalse("可疑安装包要删掉", viewModel.state.value.canInstall)
    }

    @Test
    fun `校验和不符时下载失败且不留待安装状态`() = runTest {
        val viewModel = viewModel(manifest = manifestJson.replace(sha, "0".repeat(64)))
        viewModel.check()
        advanceUntilIdle()
        viewModel.download()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.status is UpdateStatus.Failed)
    }

    @Test
    fun `取消下载回到可重下状态并保留断点`() = runTest {
        val viewModel = viewModel(signature = SignatureCheck.Match, cancelDuringDownload = true)
        viewModel.check()
        advanceUntilIdle()
        viewModel.download()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("取消后应回到可下载：${state.status}", state.status is UpdateStatus.Available)
        assertTrue("要给用户一句中文说明：${state.notice}", state.notice.orEmpty().contains("取消"))
        assertTrue("断点文件要留着", FileDownloader.partFileOf(targetFile()).isFile)
    }

    @Test
    fun `安装时没有权限会如实提示`() = runTest {
        val viewModel = viewModel(signature = SignatureCheck.Match, installAllowed = false)
        viewModel.check()
        advanceUntilIdle()
        viewModel.download()
        advanceUntilIdle()
        viewModel.install()

        assertTrue(
            "要引导去开「安装未知应用」：${viewModel.state.value.notice}",
            viewModel.state.value.notice.orEmpty().contains("安装未知应用"),
        )
    }

    @Test
    fun `字节数格式化`() {
        assertEquals("", formatBytes(0L))
        assertTrue(formatBytes(512 * 1024).endsWith("KB"))
        assertTrue(formatBytes(74L * 1024 * 1024).endsWith("MB"))
    }

    // ------------------------------------------------------------------ 测试脚手架

    private fun targetFile(): File = File(temporary.root, "mediagate-0.1.1.apk")

    private fun viewModel(
        manifest: String = manifestJson,
        signature: SignatureCheck = SignatureCheck.Match,
        installAllowed: Boolean = true,
        cancelDuringDownload: Boolean = false,
    ): UpdateViewModel {
        val environment = FakeEnvironment(temporary.root, signature, installAllowed)
        val transport = RoutingTransport(manifest, payload, cancelDuringDownload)
        return UpdateViewModel(
            environment = environment,
            checker = UpdateChecker(transport, io = Dispatchers.Unconfined),
            downloader = FileDownloader(transport),
            io = Dispatchers.Unconfined,
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )
    }
}

/** 假宿主：版本 1，下载到临时目录，签名结论可配。 */
private class FakeEnvironment(
    private val dir: File,
    private val signature: SignatureCheck,
    private val installAllowed: Boolean,
) : UpdateEnvironment {

    override val currentVersionName: String = "0.1.0"
    override val currentVersionCode: Long = 1L

    override fun downloadTarget(manifest: UpdateManifest): File = File(dir, "mediagate-" + manifest.versionName + ".apk")

    override fun verifySignature(apk: File): SignatureCheck = signature

    override fun installApk(apk: File): Boolean = installAllowed

    override fun openReleasesPage(): Boolean = true
}

/** 按 URL 分流的假 HTTP：清单给 JSON，APK 给字节流。 */
private class RoutingTransport(
    private val manifestJson: String,
    private val payload: ByteArray,
    private val cancelDuringDownload: Boolean,
) : HttpTransport {

    override suspend fun open(request: HttpRequest): HttpStream =
        if (request.url.contains("update.json") || request.url.contains("raw.githubusercontent")) {
            bytes(manifestJson.toByteArray(Charsets.UTF_8), request)
        } else {
            bytes(payload, request, cancelAfter = if (cancelDuringDownload) payload.size / 2 else null)
        }

    private fun bytes(body: ByteArray, request: HttpRequest, cancelAfter: Int? = null): HttpStream {
        val start = if (request.rangeStart != null && request.rangeStart!! > 0L) request.rangeStart!!.toInt() else 0
        val slice = body.copyOfRange(start.coerceAtMost(body.size), body.size)
        val input = ByteArrayInputStream(slice)
        return object : HttpStream {
            private var read = 0
            override val code: Int = if (start > 0) 206 else 200
            override val contentLength: Long = slice.size.toLong()
            override val contentRangeStart: Long? = if (start > 0) start.toLong() else null
            override val etag: String? = null

            override fun read(buffer: ByteArray): Int {
                if (cancelAfter != null && read >= cancelAfter) throw CancellationException("测试取消")
                val n = input.read(buffer)
                if (n > 0) read += n
                return n
            }

            override fun close() = Unit
        }
    }
}
