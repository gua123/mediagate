package io.github.gua123.mediagate.feature.connections

/**
 * 协议特有选项（**R8**）—— 存进 `connection.options` 列。
 *
 * 格式刻意选成最简单的 `key=value;key=value`（纯文本、可读、无依赖）：
 * 只有两三个键，不值得为它引 JSON 解析器，也避免 JVM 单测里碰 Android 的 `org.json`。
 * 认不出的键一律忽略（向前兼容），写回时只写已知键。
 *
 * @param allowInsecureHttp WebDAV 是否允许明文 `http://`（局域网自用默认允许；
 *   只走公网时关掉，避免凭据走明文——对应 `WebDavConfig.allowInsecureHttp`）。
 * @param connectTimeoutMs 单次连接超时；null = 用后端默认（plan 4.2：5 s）。
 */
data class ConnectionOptions(
    val allowInsecureHttp: Boolean = true,
    val connectTimeoutMs: Long? = null,
) {

    /** 序列化（写库用）。 */
    fun format(): String = buildString {
        append(KEY_ALLOW_INSECURE_HTTP).append('=').append(allowInsecureHttp)
        connectTimeoutMs?.let { append(';').append(KEY_CONNECT_TIMEOUT_MS).append('=').append(it) }
    }

    companion object {
        const val KEY_ALLOW_INSECURE_HTTP = "allowInsecureHttp"
        const val KEY_CONNECT_TIMEOUT_MS = "connectTimeoutMs"

        /** 默认选项。 */
        val Default = ConnectionOptions()

        /**
         * 解析 `connection.options`；null / 空串 / 乱写都退回默认值，**绝不抛异常**
         * （一条坏记录不该让整个连接列表打不开）。
         */
        fun parse(raw: String?): ConnectionOptions {
            if (raw.isNullOrBlank()) return Default
            var allowInsecure = true
            var timeout: Long? = null
            raw.split(';').forEach { segment ->
                val key = segment.substringBefore('=', "").trim()
                val value = segment.substringAfter('=', "").trim()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                when (key) {
                    KEY_ALLOW_INSECURE_HTTP -> allowInsecure = value.equals("true", ignoreCase = true)
                    KEY_CONNECT_TIMEOUT_MS -> timeout = value.toLongOrNull()?.takeIf { it > 0L }
                }
            }
            return ConnectionOptions(allowInsecureHttp = allowInsecure, connectTimeoutMs = timeout)
        }
    }
}
