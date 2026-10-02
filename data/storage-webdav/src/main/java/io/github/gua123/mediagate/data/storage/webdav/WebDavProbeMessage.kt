package io.github.gua123.mediagate.data.storage.webdav

/**
 * 探针失败时的一句话（**纯函数**，脱网单测）。
 *
 * **2026-10-03 真机**：用户那边测试只报「未知错误 · 查看详情」，而我在开发机上用**同样的凭据与请求**
 * 对同一地址跑 PROPFIND 得到的是 **207**——说明服务端没问题，问题在"App 到底发出去了什么/收到的是什么"。
 * 所以失败信息必须带上：**请求的 URL、响应状态行、响应体开头一小段**（Synology/Apache 的报错页往往
 * 直接写着原因，比如路径不对、认证方式不符）。
 *
 * 三条自我约束：
 * 1. **脱敏**：URL 里若带 `user:pass@` 一律抹掉（`Authorization` 头本来就不进这里）；
 * 2. **单行**：换行/制表符压成空格，免得界面里排版乱掉；
 * 3. **限长**：片段最多 [MAX_SNIPPET] 字，别把整个 HTML 报错页塞进提示。
 */
internal fun probeFailureMessage(
    code: Int,
    httpMessage: String,
    url: String,
    bodySnippet: String?,
): String {
    val head = "HTTP " + code + (if (httpMessage.isBlank()) "" else " " + httpMessage)
    val where = "（请求：" + redactUrl(url) + "）"
    val snippet = bodySnippet?.let { sanitizeSnippet(it) }.orEmpty()
    return if (snippet.isEmpty()) head + where else head + where + " 响应：" + snippet
}

/** 抹掉 URL 里的用户信息（`http://user:pass@host/` → `http://host/`）。 */
internal fun redactUrl(url: String): String {
    val at = url.indexOf('@')
    if (at < 0) return url
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    return url.substring(0, schemeEnd + 3) + url.substring(at + 1)
}

/** 响应片段 → 能塞进一行提示的样子。 */
internal fun sanitizeSnippet(raw: String, max: Int = MAX_SNIPPET): String {
    val flat = raw.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max) + "…"
}

/** 片段上限（字符）。 */
internal const val MAX_SNIPPET: Int = 160

/**
 * 失败提示里补一句「经系统代理 …」。
 *
 * **2026-10-03 真机定位**：手机测公网 WebDAV 得 `HTTP 503 Service Unavailable`，而我在开发机上
 * **直连**同一个地址拿的是 **207**、**经代理**（系统里配的 http 代理）拿的正是 **503**——
 * 也就是说请求在链路中间被代理挡了，跟 App 与服务端都没关系。
 * 把这一条写进提示，用户一眼就能想到"是不是手机开着 VPN / 设了 Wi-Fi 代理"。
 */
internal fun proxyNote(proxy: String?): String =
    if (proxy.isNullOrBlank() || proxy.equals("DIRECT", ignoreCase = true)) "" else "（经系统代理 " + proxy + "）"

/** 读系统代理（OkHttp 默认就会用它；这里只为把线索写进提示）。 */
internal fun systemProxyFor(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    val selected = java.net.ProxySelector.getDefault()?.select(uri)?.firstOrNull() ?: return null
    when (selected.type()) {
        java.net.Proxy.Type.DIRECT -> null
        else -> (selected.address() as? java.net.InetSocketAddress)?.let { it.hostString + ":" + it.port }
    }
}.getOrNull()
