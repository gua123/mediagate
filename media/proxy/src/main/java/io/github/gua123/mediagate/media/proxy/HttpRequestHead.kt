package io.github.gua123.mediagate.media.proxy

import java.util.Locale

/**
 * 解析后的 HTTP 请求头（代理只认请求行 + 少量头，够 GET/HEAD 用）。
 *
 * 纯数据 + 纯解析函数：读字节流的部分在 [LoopbackHttpProxy] 里，这里只处理行文本，
 * 便于 JVM 单测直接构造畸形请求。
 *
 * @property method 方法原文（未大写化，保留给日志）。
 * @property target 请求目标（通常是 `/m/...`，可能带查询串）。
 * @property version 形如 `HTTP/1.1`。
 * @property headers 头字段；key 已转小写，重复字段保留第一个。
 */
data class HttpRequestHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
) {

    /** 取头字段（大小写不敏感）；不存在返回 null。 */
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    companion object {

        /** 头字段最大行数（防御畸形/恶意请求）。 */
        const val MAX_HEAD_LINES = 64

        /**
         * 由「请求行 + 头行」解析；请求行不合法（缺字段 / 版本不是 HTTP/）返回 null。
         *
         * 没有冒号的头行按 RFC 属于畸形，这里直接忽略而不是整体失败（宽容解析）。
         */
        fun parseLines(lines: List<String>): HttpRequestHead? {
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            if (requestLine.isEmpty()) return null
            val parts = requestLine.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            val (method, target, version) = parts
            if (!version.startsWith("HTTP/", ignoreCase = true)) return null
            if (target.isEmpty()) return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                if (name.isEmpty()) continue
                val value = line.substring(colon + 1).trim()
                // 重复字段保留第一个：Range / Host 出现多次时以首个为准，避免歧义
                headers.putIfAbsent(name, value)
            }
            return HttpRequestHead(method = method, target = target, version = version, headers = headers)
        }
    }
}
