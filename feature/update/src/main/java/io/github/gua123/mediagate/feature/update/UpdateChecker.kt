package io.github.gua123.mediagate.feature.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.download.HttpRequest
import io.github.gua123.mediagate.core.download.HttpStream
import io.github.gua123.mediagate.core.download.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 更新源（**R20**）——默认指向**私有**仓库 gua123/mediagate-releases 的 `update.json`。
 *
 * 私有仓库为什么走"内容 API + 只读 token"而不是 releases 直链：
 * - release 资产直链对私有仓库要带 token 走 API 资产端点（`Accept: application/octet-stream`）；
 * - 清单文件用内容 API 的 raw 端点最省事，且可以只给 Contents: Read 的最小权限。
 *
 * @param manifestUrl 清单地址。
 * @param manifestHeaders 拉清单的额外请求头。
 * @param apkHeaders 下载 APK 的额外请求头（私有仓库要 `Accept: application/octet-stream`）。
 * @param requiresToken 是否必须配只读 token（私有仓库为 true；将来换公开源可以设 false）。
 */
data class UpdateSource(
    val manifestUrl: String = DEFAULT_MANIFEST_URL,
    val manifestHeaders: Map<String, String> = GITHUB_JSON_HEADERS,
    val apkHeaders: Map<String, String> = GITHUB_ASSET_HEADERS,
    val requiresToken: Boolean = true,
) {

    /** 带上只读 token 的清单请求；[token] 为空时不加 Authorization。 */
    fun manifestRequest(token: String?): HttpRequest =
        HttpRequest(manifestUrl, headers = withAuth(manifestHeaders, token))

    /** 带上只读 token 的 APK 请求头（Range 由下载器按续传位置补）。 */
    fun apkHeadersWith(token: String?): Map<String, String> = withAuth(apkHeaders, token)

    private fun withAuth(base: Map<String, String>, token: String?): Map<String, String> =
        if (token.isNullOrBlank()) base else base + ("Authorization" to "Bearer " + token.trim())

    companion object {

        /** 默认清单地址（私有仓库 + 只读 token）。 */
        const val DEFAULT_MANIFEST_URL: String =
            "https://api.github.com/repos/gua123/mediagate-releases/contents/update.json"

        /** GitHub 内容 API 的 raw 响应。 */
        val GITHUB_JSON_HEADERS: Map<String, String> = mapOf(
            "Accept" to "application/vnd.github.raw+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )

        /** GitHub 资产 API 的二进制响应（私有仓库下载 APK 必须带）。 */
        val GITHUB_ASSET_HEADERS: Map<String, String> = mapOf(
            "Accept" to "application/octet-stream",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
    }
}

/** 检查更新的失败分类（中文，界面直接显示）。 */
enum class UpdateFailure(val zhText: String) {

    /** 没配只读 token（私有仓库必需）。 */
    NEEDS_TOKEN("还没有配置 GitHub 只读 token：请在设置页填入"),

    /** token 无效 / 权限不足 / 仓库或文件不存在（私有仓库无权限时 GitHub 回 404）。 */
    UNAUTHORIZED("token 无效或权限不足（需要该仓库的 Contents: Read）"),

    /** 连不上 GitHub。 */
    GITHUB_UNREACHABLE("连不上 GitHub：检查更新需要能访问 GitHub（可能需要代理）"),

    /** 其它 HTTP 错误。 */
    HTTP("服务器返回错误"),

    /** 清单不是合法 JSON 或缺字段。 */
    BAD_MANIFEST("更新清单格式不对（可能发版脚本没生成好）"),
}

/** 检查更新的结果。 */
sealed interface UpdateCheckResult {

    /** 已是最新。 */
    data class UpToDate(val currentVersionCode: Long) : UpdateCheckResult

    /** 有新版本。 */
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult

    /** 检查失败（中文原因 + 可选细节）。 */
    data class Failed(val kind: UpdateFailure, val detail: String? = null) : UpdateCheckResult {

        /** 可直接展示的一句话。 */
        val display: String get() = if (detail.isNullOrBlank()) kind.zhText else kind.zhText + "（" + detail + "）"
    }
}

/**
 * 检查更新（**R20**）：拉 `update.json` → 解析 → 比 versionCode。
 *
 * 不做任何 UI 与落盘；失败一律给中文分类——"连不上 GitHub"是最常见的一种，
 * 必须与"token 不对"分开说，否则用户会去改 token 而其实只是没代理。
 *
 * @param transport HTTP 传输（真机是 [io.github.gua123.mediagate.core.download.HttpUrlConnectionTransport]，
 *   单测灌假实现）。
 * @param io 调度器。
 */
class UpdateChecker(
    private val transport: HttpTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun check(
        source: UpdateSource,
        currentVersionCode: Long,
        token: String?,
    ): UpdateCheckResult = withContext(io) {
        if (source.requiresToken && token.isNullOrBlank()) {
            return@withContext UpdateCheckResult.Failed(UpdateFailure.NEEDS_TOKEN)
        }
        val body = try {
            transport.open(source.manifestRequest(token)).use { stream -> readBody(stream) }
        } catch (e: UnauthorizedException) {
            AppLog.w(TAG, "检查更新失败：没权限（HTTP " + e.code + "）")
            return@withContext UpdateCheckResult.Failed(UpdateFailure.UNAUTHORIZED, "HTTP " + e.code)
        } catch (e: HttpCodeException) {
            AppLog.w(TAG, "检查更新失败：HTTP " + e.code)
            return@withContext UpdateCheckResult.Failed(UpdateFailure.HTTP, "HTTP " + e.code)
        } catch (e: IOException) {
            AppLog.w(TAG, "检查更新失败（网络）：" + e.javaClass.simpleName)
            return@withContext UpdateCheckResult.Failed(UpdateFailure.GITHUB_UNREACHABLE, ErrorText.of(e, "网络不可达"))
        }
        if (body == null) {
            return@withContext UpdateCheckResult.Failed(UpdateFailure.HTTP, "清单太大或读不出来")
        }
        val manifest = UpdateManifest.parse(body)
            ?: return@withContext UpdateCheckResult.Failed(UpdateFailure.BAD_MANIFEST)
        if (manifest.isNewerThan(currentVersionCode)) {
            UpdateCheckResult.Available(manifest)
        } else {
            UpdateCheckResult.UpToDate(currentVersionCode)
        }
    }

    /** 读完整响应体；非 2xx 归类成中文失败原因；超过上限返回 null。 */
    private fun readBody(stream: HttpStream): String? {
        when (stream.code) {
            200 -> Unit
            // 私有仓库无权限时 GitHub 对内容 API 回 404（不透露存在性），所以三个码一起当"没权限"
            401, 403, 404 -> throw UnauthorizedException(stream.code)
            else -> throw HttpCodeException(stream.code)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.size() + read > MAX_MANIFEST_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** 内部信号：没权限（转成 [UpdateFailure.UNAUTHORIZED]）。 */
    private class UnauthorizedException(val code: Int) : IOException("unauthorized " + code)

    /** 内部信号：其它 HTTP 错误。 */
    private class HttpCodeException(val code: Int) : IOException("http " + code)

    private companion object {
        const val TAG = "update-checker"

        /** 清单体积上限（正常几 KB；超过说明拿到的不是清单）。 */
        const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
