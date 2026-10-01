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
 * 更新源（**R20**）——指向**公开**仓库里的静态清单。
 *
 * 为什么是公开仓库 + raw 清单：
 * - 源码仓库本身就是公开的，更新清单与 APK 跟着一起公开最省事，**App 端不需要任何凭据**；
 * - 私有仓库那套（只读 token + Keystore 加密存储 + 权限失败分类）只在"必须保密"时才值得，
 *   2026-10-02 与用户确认后按公开方案定稿；
 * - 清单走 raw.githubusercontent.com、APK 走 Release 资产：两者都匿名可取、不吃 GitHub API 限额
 *   （API 匿名限额 60 次/小时/IP，共享出口很容易用尽）。
 *
 * @param manifestUrl 清单地址（raw）。
 * @param apkHeaders 下载 APK 的额外请求头（公开资产直连即可，保留字段便于将来换源）。
 */
data class UpdateSource(
    val manifestUrl: String = DEFAULT_MANIFEST_URL,
    val apkHeaders: Map<String, String> = mapOf("Accept" to "application/octet-stream"),
) {

    /** 清单请求（公开源不带任何凭据）。 */
    fun manifestRequest(): HttpRequest = HttpRequest(manifestUrl)

    companion object {

        /** 默认清单地址（公开仓库 main 分支里的 update.json）。 */
        const val DEFAULT_MANIFEST_URL: String =
            "https://raw.githubusercontent.com/gua123/mediagate/main/update.json"

        /** 发布页（设置页里给用户的"手动下载"出口）。 */
        const val RELEASES_PAGE: String = "https://github.com/gua123/mediagate/releases"
    }
}

/** 检查更新的失败分类（中文，界面直接显示）。 */
enum class UpdateFailure(val zhText: String) {

    /** 连不上 GitHub。 */
    GITHUB_UNREACHABLE("连不上 GitHub：检查更新需要能访问 GitHub（可能需要代理）"),

    /** GitHub 上没有这份清单（还没发过版 / 分支名不对）。 */
    MISSING("还没找到更新清单（仓库里还没有 update.json）"),

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
 * 检查更新（**R20**）：拉 update.json → 解析 → 比 versionCode。
 *
 * 不做任何 UI 与落盘；失败一律给中文分类——"连不上 GitHub"是最常见的一种，
 * 必须与"清单不存在 / 格式不对"分开说，否则用户会去查网络而其实只是还没发版。
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
    ): UpdateCheckResult = withContext(io) {
        val body = try {
            transport.open(source.manifestRequest()).use { stream -> readBody(stream) }
        } catch (e: MissingException) {
            AppLog.w(TAG, "检查更新：GitHub 上没有 update.json")
            return@withContext UpdateCheckResult.Failed(UpdateFailure.MISSING)
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
            404 -> throw MissingException()
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

    /** 内部信号：清单不存在（转成 [UpdateFailure.MISSING]）。 */
    private class MissingException : IOException("update.json not found")

    /** 内部信号：其它 HTTP 错误。 */
    private class HttpCodeException(val code: Int) : IOException("http " + code)

    private companion object {
        const val TAG = "update-checker"

        /** 清单体积上限（正常几 KB；超过说明拿到的不是清单）。 */
        const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
