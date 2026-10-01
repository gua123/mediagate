package io.github.gua123.mediagate.feature.update

import io.github.gua123.mediagate.core.download.HttpRequest
import io.github.gua123.mediagate.core.download.HttpStream
import io.github.gua123.mediagate.core.download.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * [UpdateManifest] 与 [UpdateChecker] 的 JVM 单测（**R20**，公开仓库口径）。
 *
 * 覆盖：清单解析（含转义中文、缺字段、坏 SHA）、版本比较（只认 versionCode）、
 * 检查更新的四类失败（连不上 GitHub / 清单不存在 / HTTP 错误 / 清单不合法），
 * 以及"公开源不带任何凭据"这条口径。
 */
class UpdateCheckerTest {

    private val json = """
        {
          "versionCode": 2,
          "versionName": "0.1.1",
          "apkUrl": "https://github.com/gua123/mediagate/releases/download/v0.1.1/mediagate-0.1.1.apk",
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
    fun `公开源默认指向 raw 清单且不带任何凭据`() {
        val source = UpdateSource()
        assertTrue(
            "默认清单要指向公开仓库的 raw 地址：${source.manifestUrl}",
            source.manifestUrl.startsWith("https://raw.githubusercontent.com/gua123/mediagate/"),
        )
        assertTrue(source.manifestUrl.endsWith("update.json"))
        val request = source.manifestRequest()
        assertTrue("公开源不应带 Authorization：${request.headers}", request.headers.keys.none { it.equals("Authorization", true) })
        assertTrue(UpdateSource.RELEASES_PAGE.contains("/releases"))
    }

    @Test
    fun `备用清单地址是同一份文件的另一条 raw 路径`() {
        val source = UpdateSource()

        assertTrue(
            "备用地址也要是 raw：${source.mirrorManifestUrl}",
            source.mirrorManifestUrl.orEmpty().startsWith("https://raw.githubusercontent.com/gua123/mediagate/"),
        )
        assertTrue(source.mirrorManifestUrl.orEmpty().endsWith("/update.json"))
        assertTrue(
            "两条地址必须不同（否则缓存键相同，读了也白读）",
            source.mirrorManifestUrl != source.manifestUrl,
        )
        assertTrue(
            "refs/heads 形式才是另一条缓存键：${source.mirrorManifestUrl}",
            source.mirrorManifestUrl.orEmpty().contains("/refs/heads/"),
        )
    }

    @Test
    fun `主清单说已是最新时再读备用清单`() = runTest {
        val stale = json.replace("\"versionCode\": 2", "\"versionCode\": 1")
        val transport = FakeTransport(200, stale)
        // 主地址给旧清单（CDN 缓存），备用地址按 URL 给新清单
        transport.bodyByUrl = { url ->
            if (url.contains("/refs/heads/")) json else stale
        }

        val result = UpdateChecker(transport, io = Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)

        assertTrue("备用清单里有新版就该报出来：$result", result is UpdateCheckResult.Available)
        assertEquals(2, transport.requested.size)
        assertTrue(
            "第二次问的必须是备用地址：${transport.requested}",
            transport.requested[1].contains("/refs/heads/"),
        )
    }

    @Test
    fun `主清单就有新版时不再读备用清单`() = runTest {
        val transport = FakeTransport(200, json)

        val result = UpdateChecker(transport, io = Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("已经知道有新版，别多打一次网络", 1, transport.requested.size)
    }

    @Test
    fun `主清单连不上时备用清单能顶上`() = runTest {
        val transport = FakeTransport(200, json)
        transport.failByUrl = { url -> if (url.contains("/refs/heads/")) null else IOException("UnknownHostException") }

        val result = UpdateChecker(transport, io = Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)

        assertTrue("主地址挂了、备用地址有货，就该报更新：$result", result is UpdateCheckResult.Available)
    }

    @Test
    fun `两条都读不到时报第一条的失败原因`() = runTest {
        val transport = FakeTransport(500, "")
        transport.bodyByUrl = { "" }

        val result = UpdateChecker(transport, io = Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)

        assertEquals(UpdateFailure.HTTP, (result as UpdateCheckResult.Failed).kind)
        assertEquals(2, transport.requested.size)
    }

    @Test
    fun `有新版时返回 Available 并原样透传清单`() = runTest {
        val transport = FakeTransport(200, json)
        val result = UpdateChecker(transport, io = Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)

        assertTrue("应是 Available：$result", result is UpdateCheckResult.Available)
        val manifest = (result as UpdateCheckResult.Available).manifest
        assertEquals(2L, manifest.versionCode)
        assertEquals("0.1.1", manifest.versionName)
        assertEquals(74186113L, manifest.sizeBytes)
        assertEquals(transport.lastRequest!!.url, UpdateSource.DEFAULT_MANIFEST_URL)
    }

    @Test
    fun `同版本或更旧时返回已是最新`() = runTest {
        val checker = UpdateChecker(FakeTransport(200, json), Dispatchers.Unconfined)
        assertTrue(checker.check(UpdateSource(), currentVersionCode = 2L) is UpdateCheckResult.UpToDate)
        assertTrue(checker.check(UpdateSource(), currentVersionCode = 9L) is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `清单不存在与连不上 GitHub 分别给不同提示`() = runTest {
        val missing = UpdateChecker(FakeTransport(404, ""), Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)
        assertEquals(UpdateFailure.MISSING, (missing as UpdateCheckResult.Failed).kind)

        val offline = UpdateChecker(
            FakeTransport(200, json, failWith = IOException("UnknownHostException: raw.githubusercontent.com")),
            Dispatchers.Unconfined,
        ).check(UpdateSource(), currentVersionCode = 1L)
        assertEquals(UpdateFailure.GITHUB_UNREACHABLE, (offline as UpdateCheckResult.Failed).kind)
        assertTrue("必须点明需要能访问 GitHub：${offline.display}", offline.display.contains("GitHub"))
    }

    @Test
    fun `服务器错误与坏清单一各有分类`() = runTest {
        val http = UpdateChecker(FakeTransport(500, ""), Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)
        assertEquals(UpdateFailure.HTTP, (http as UpdateCheckResult.Failed).kind)
        assertTrue(http.display.contains("500"))

        val bad = UpdateChecker(FakeTransport(200, "{}"), Dispatchers.Unconfined)
            .check(UpdateSource(), currentVersionCode = 1L)
        assertEquals(UpdateFailure.BAD_MANIFEST, (bad as UpdateCheckResult.Failed).kind)
    }
}

/** 假 HTTP：返回固定状态码与正文（可以模拟抛网络异常；也能按 URL 分别给答案）。 */
private class FakeTransport(
    private val code: Int,
    private val body: String,
    private val failWith: IOException? = null,
) : HttpTransport {

    var lastRequest: HttpRequest? = null

    /** 每次请求的 URL（按顺序），用于验证"读了几条、读的是哪条"。 */
    val requested = mutableListOf<String>()

    /** 按 URL 给不同正文（模拟"两条缓存键一个新一个旧"）。 */
    var bodyByUrl: ((String) -> String)? = null

    /** 按 URL 决定是否抛网络异常。 */
    var failByUrl: ((String) -> IOException?)? = null

    override suspend fun open(request: HttpRequest): HttpStream {
        lastRequest = request
        requested += request.url
        failByUrl?.invoke(request.url)?.let { throw it }
        failWith?.let { throw it }
        val bytes = (bodyByUrl?.invoke(request.url) ?: body).toByteArray(Charsets.UTF_8)
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
