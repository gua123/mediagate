package io.github.gua123.mediagate.feature.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.download.HttpRequest
import io.github.gua123.mediagate.core.download.HttpStream
import io.github.gua123.mediagate.core.download.HttpTransport
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * [UpdateManifest] 与 [UpdateChecker] 的 JVM 单测（**R20**）。
 *
 * 覆盖：清单解析（含转义中文、缺字段、坏 SHA）、版本比较（只认 versionCode）、
 * 检查更新的五类失败（缺 token / 无权限 / 连不上 GitHub / HTTP 错误 / 清单坏）。
 */
class UpdateCheckerTest {

    private val json = """
        {
          "versionCode": 2,
          "versionName": "0.1.1",
          "apkUrl": "https://api.github.com/repos/gua123/mediagate-releases/releases/assets/1",
          "sizeBytes": 74186113,
          "sha256": "f6b330dea2b1217e322dbd4291d319f9ae84d0f98298320a548da093225661f2",
          "notes": "SFTP/FTP 接线修复；\n明文 http 放开"
        }
    """.trimIndent()

    @Test
    fun `解析清单并识别新版本`() {
        val manifest = UpdateManifest.parse(json)
        assertTrue("应解析成功", manifest != null)
        assertEquals(2L, manifest!!.versionCode)
        assertEquals("0.1.1", manifest.versionName)
        assertEquals(74186113L, manifest.sizeBytes)
        assertEquals(64, manifest.sha256.length)
        assertTrue("换行转义要还原：${manifest.notes}", manifest.notes.contains("\n"))
        assertTrue(manifest.isNewerThan(1L))
        assertTrue(!manifest.isNewerThan(2L))
        assertTrue(!manifest.isNewerThan(3L))
    }

    @Test
    fun `缺字段或坏 SHA 的清单一律判为不合法`() {
        assertNull(UpdateManifest.parse(null))
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("不是 JSON"))
        assertNull("缺 versionCode", UpdateManifest.parse(json.replace("\"versionCode\": 2,", "")))
        assertNull("缺 apkUrl", UpdateManifest.parse(json.replace(Regex("\"apkUrl\": \"[^\"]*\","), "")))
        assertNull("sha256 长度不对", UpdateManifest.parse(json.replace(Regex("\"sha256\": \"[0-9a-f]+\""), "\"sha256\": \"abc\"")))
        assertNull("版本号非正数", UpdateManifest.parse(json.replace("\"versionCode\": 2", "\"versionCode\": 0")))
    }

    @Test
    fun `有新版时返回 Available 并带上 token`() = runTest {
        val transport = FakeTransport(200, json)
        val source = UpdateSource()
        val token = "github_pat_test"

        val result = UpdateChecker(transport, io = kotlinx.coroutines.Dispatchers.Unconfined)
            .check(source, currentVersionCode = 1L, token = token)

        assertTrue("应是 Available：$result", result is UpdateCheckResult.Available)
        assertEquals(2L, (result as UpdateCheckResult.Available).manifest.versionCode)
        assertEquals("Bearer $token", transport.lastRequest!!.headers["Authorization"])
        assertEquals("application/vnd.github.raw+json", transport.lastRequest!!.headers["Accept"])
    }

    @Test
    fun `同版本或更旧时返回已是最新`() = runTest {
        val checker = UpdateChecker(FakeTransport(200, json), io = kotlinx.coroutines.Dispatchers.Unconfined)
        val result = checker.check(UpdateSource(), currentVersionCode = 2L, token = "t")
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `没配 token 时明确要求先配置`() = runTest {
        val checker = UpdateChecker(FakeTransport(200, json), io = kotlinx.coroutines.Dispatchers.Unconfined)
        val result = checker.check(UpdateSource(), currentVersionCode = 1L, token = "  ")
        assertEquals(UpdateFailure.NEEDS_TOKEN, (result as UpdateCheckResult.Failed).kind)
        assertTrue(result.display.contains("token"))
    }

    @Test
    fun `私有仓库无权限（404）与网络不通分别给不同提示`() = runTest {
        val io = kotlinx.coroutines.Dispatchers.Unconfined
        val unauthorized = UpdateChecker(FakeTransport(404, ""), io).check(UpdateSource(), 1L, "bad")
        assertEquals(UpdateFailure.UNAUTHORIZED, (unauthorized as UpdateCheckResult.Failed).kind)

        val offline = UpdateChecker(FakeTransport(200, json, failWith = IOException("UnknownHostException: api.github.com")), io)
            .check(UpdateSource(), 1L, "t")
        assertEquals(UpdateFailure.GITHUB_UNREACHABLE, (offline as UpdateCheckResult.Failed).kind)
        assertTrue("必须点明需要能访问 GitHub：${offline.display}", offline.display.contains("GitHub"))
    }

    @Test
    fun `服务器 500 与坏清单各有分类`() = runTest {
        val io = kotlinx.coroutines.Dispatchers.Unconfined
        val http = UpdateChecker(FakeTransport(500, ""), io).check(UpdateSource(), 1L, "t")
        assertEquals(UpdateFailure.HTTP, (http as UpdateCheckResult.Failed).kind)

        val bad = UpdateChecker(FakeTransport(200, "{}"), io).check(UpdateSource(), 1L, "t")
        assertEquals(UpdateFailure.BAD_MANIFEST, (bad as UpdateCheckResult.Failed).kind)
    }

    @Test
    fun `公开源可以不要求 token`() = runTest {
        val result = UpdateChecker(FakeTransport(200, json), kotlinx.coroutines.Dispatchers.Unconfined)
            .check(UpdateSource(requiresToken = false), 1L, token = null)
        assertTrue(result is UpdateCheckResult.Available)
    }
}

/** 假 HTTP：返回固定状态码与正文（可以模拟抛网络异常）。 */
private class FakeTransport(
    private val code: Int,
    private val body: String,
    private val failWith: IOException? = null,
) : HttpTransport {

    var lastRequest: HttpRequest? = null

    override suspend fun open(request: HttpRequest): HttpStream {
        lastRequest = request
        failWith?.let { throw it }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bytes)
        return object : HttpStream {
            override val code: Int = this@FakeTransport.code
            override val contentLength: Long = bytes.size.toLong()
            override val contentRangeStart: Long? = null
            override val etag: String? = null
            override fun read(buffer: ByteArray): Int = input.read(buffer)
            override fun close() = Unit
        }
    }
}
