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
 * @param mirrorManifestUrl 备用清单地址（**同一份文件的另一条路径**，见下）；null = 不读备用。
 * @param apkHeaders 下载 APK 的额外请求头（公开资产直连即可，保留字段便于将来换源）。
 */
data class UpdateSource(
    val manifestUrl: String = DEFAULT_MANIFEST_URL,
    val mirrorManifestUrl: String? = DEFAULT_MIRROR_MANIFEST_URL,
    /**
     * **国内可达的镜像清单**（2026-10-03 用户反馈「网络正常，但软件内连不上 GitHub」后新增）。
     *
     * jsDelivr 直接托管 GitHub 仓库里的文件，国内通常可达（不需要代理）；
     * 代价是**有缓存**（分支引用大约半天级别），所以它排在 raw 后面：
     * raw 通就用最新的，raw 不通才用它——两边都读时按 versionCode 取高者，不会"用旧的盖新的"。
     */
    val cdnManifestUrl: String? = DEFAULT_CDN_MANIFEST_URL,
    val apkHeaders: Map<String, String> = mapOf("Accept" to "application/octet-stream"),
) {

    /** 清单请求（公开源不带任何凭据）。 */
    fun manifestRequest(): HttpRequest = HttpRequest(manifestUrl)

    /** 备用清单请求。 */
    fun mirrorManifestRequest(): HttpRequest? = mirrorManifestUrl?.let { HttpRequest(it) }

    /** 镜像（CDN）清单请求。 */
    fun cdnManifestRequest(): HttpRequest? = cdnManifestUrl?.let { HttpRequest(it) }

    companion object {

        /** 默认清单地址（公开仓库 main 分支里的 update.json）。 */
        const val DEFAULT_MANIFEST_URL: String =
            "https://raw.githubusercontent.com/gua123/mediagate/main/update.json"

        /**
         * 备用清单地址 = **同一份文件的另一条 raw 路径**。
         *
         * 为什么需要它（2026-10-03 实测）：raw.githubusercontent.com 由 CDN 提供，
         * 响应头是 `cache-control: max-age=300` —— 发新版本后，**老清单最多还会被端上 5 分钟以上**
         * （实测发 0.1.3 后 6 分钟仍返回 0.1.2；加 `?ts=` 查询参数或 `Cache-Control: no-cache`
         * 请求头都**不能**绕过，缓存按路径命中）。
         * 而 `refs/heads/main` 形式的路径是**另一条缓存键**（实测同一时刻它是 MISS、拿到的就是新版本）。
         * 所以：两个地址都读、取 versionCode 更高的那份——发布后至少有一条是新的。
         */
        const val DEFAULT_MIRROR_MANIFEST_URL: String =
            "https://raw.githubusercontent.com/gua123/mediagate/refs/heads/main/update.json"

        /**
         * 国内可达的镜像清单地址（jsDelivr 托管的同一份 update.json）。
         *
         * 为什么需要（2026-10-03 真机）：用户「网络为正常，但软件内网络无法连通到 github」——
         * raw.githubusercontent.com 在国内经常不可达，而他的代理/VPN 未必覆盖这个域名。
         * jsDelivr 走的是通用 CDN，通常不需要代理即可读到清单。
         */
        const val DEFAULT_CDN_MANIFEST_URL: String =
            "https://cdn.jsdelivr.net/gh/gua123/mediagate@main/update.json"

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
 * **两个清单地址按需读**（[UpdateSource.mirrorManifestUrl]，理由见那里的说明）：
 * 先读主地址；只有当它"说没有新版本"时才读备用地址（两条缓存键，发布后至少一条是新的）；
 * 任一条报出更新就立刻返回，省掉多余请求；主地址彻底打不开时，备用地址也能顶上。
 *
 * @param transport HTTP 传输（真机是 [io.github.gua123.mediagate.core.download.HttpUrlConnectionTransport]，
 *   单测灌假实现）。
 * @param io 调度器。
 */
class UpdateChecker(
    private val transport: HttpTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 取 URL 的主机名（外加上路径里最后一段，方便区分两条 raw 路径）。 */
    private fun hostOf(url: String): String {
        val host = url.substringAfter("://").substringBefore("/")
        val tail = url.substringAfterLast("/")
        return if (url.contains("refs/heads")) host + "/refs" else host + "/" + tail
    }

    suspend fun check(
        source: UpdateSource,
        currentVersionCode: Long,
    ): UpdateCheckResult = withContext(io) {
        val requests = listOfNotNull(
            source.manifestRequest(),
            source.mirrorManifestRequest(),
            source.cdnManifestRequest(),
        ).distinctBy { it.url }
        var newest: UpdateManifest? = null
        var failure: UpdateCheckResult.Failed? = null
        // 每个地址试过之后记一行（成功也说、失败说原因）——故障时这一行就是"证据"
        val attempts = mutableListOf<String>()

        for (request in requests) {
            when (val result = fetch(request)) {
                is Fetch.Ok -> {
                    attempts += "✓ " + hostOf(request.url) + " → " + result.manifest.versionName
                    if (newest == null || result.manifest.versionCode > newest.versionCode) {
                        newest = result.manifest
                    }
                    // 已经确认有新版本就不必再问后面的地址
                    if (result.manifest.isNewerThan(currentVersionCode)) {
                        return@withContext UpdateCheckResult.Available(result.manifest)
                    }
                }

                is Fetch.Err -> {
                    attempts += "✗ " + hostOf(request.url) + " → " + result.failure.kind.zhText
                    if (failure == null) failure = result.failure
                }
            }
        }

        val manifest = newest
        when {
            manifest != null && manifest.isNewerThan(currentVersionCode) ->
                UpdateCheckResult.Available(manifest)

            manifest != null -> UpdateCheckResult.UpToDate(currentVersionCode)

            // 一条都没读成：报第一条（主地址）的失败原因，并把"每个地址的结果"一起带上——
            // 用户一看就知道是"全部不通"还是"只有 raw 不通、镜像也不通"（2026-10-03 用户反馈后加的）
            else -> {
                // 原有细节（例如 HTTP 500）**不能丢**，把"逐地址结果"接在后面
                val base = failure ?: UpdateCheckResult.Failed(UpdateFailure.BAD_MANIFEST)
                val attemptsText = attempts.joinToString("；")
                base.copy(
                    detail = listOfNotNull(base.detail?.takeIf { it.isNotBlank() }, attemptsText.ifBlank { null })
                        .joinToString("；")
                        .ifBlank { null },
                )
            }
        }
    }

    /** 取一条清单：成功给出解析结果，失败给出中文分类（内部信号）。 */
    private suspend fun fetch(request: HttpRequest): Fetch {
        val body = try {
            transport.open(request).use { stream -> readBody(stream) }
        } catch (e: MissingException) {
            AppLog.w(TAG, "检查更新：这条地址上没有 update.json")
            return Fetch.Err(UpdateCheckResult.Failed(UpdateFailure.MISSING))
        } catch (e: HttpCodeException) {
            AppLog.w(TAG, "检查更新失败：HTTP " + e.code)
            return Fetch.Err(UpdateCheckResult.Failed(UpdateFailure.HTTP, "HTTP " + e.code))
        } catch (e: IOException) {
            AppLog.w(TAG, "检查更新失败（网络）：" + e.javaClass.simpleName)
            return Fetch.Err(
                UpdateCheckResult.Failed(UpdateFailure.GITHUB_UNREACHABLE, ErrorText.of(e, "网络不可达")),
            )
        }
        if (body == null) {
            return Fetch.Err(UpdateCheckResult.Failed(UpdateFailure.HTTP, "清单太大或读不出来"))
        }
        val manifest = UpdateManifest.parse(body)
            ?: return Fetch.Err(UpdateCheckResult.Failed(UpdateFailure.BAD_MANIFEST))
        return Fetch.Ok(manifest)
    }

    /** 一条清单的抓取结果。 */
    private sealed interface Fetch {

        data class Ok(val manifest: UpdateManifest) : Fetch

        data class Err(val failure: UpdateCheckResult.Failed) : Fetch
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
