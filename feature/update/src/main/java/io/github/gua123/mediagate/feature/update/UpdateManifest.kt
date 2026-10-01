package io.github.gua123.mediagate.feature.update

/**
 * 更新清单（**R20**）——公开或私有仓库里的 `update.json`，由 `scripts/publish-update.sh` 生成。
 *
 * 为什么不用 GitHub API 的 release 字段：API 匿名限额只有 60 次/小时/IP，共享出口很容易用尽；
 * 一个静态清单既省事又能在私有仓库里只读访问（内容 API + 只读 token）。
 *
 * @param versionCode 版本号（**比较新旧只看它**，必须单调递增）。
 * @param versionName 展示用版本名（如 0.1.1）。
 * @param apkUrl APK 地址：私有仓库要用资产 API 端点（`https://api.github.com/repos/<owner>/<repo>/releases/assets/<id>`）。
 * @param sizeBytes APK 字节数（下载后必须相等，防被链路截断）。
 * @param sha256 APK 的 SHA-256（小写十六进制；下载后核对）。
 * @param notes 更新说明（中文，直接展示；可为空）。
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String = "",
) {

    /** 相对当前版本是否算"有新版本"（只认 versionCode）。 */
    fun isNewerThan(currentVersionCode: Long): Boolean = versionCode > currentVersionCode

    companion object {

        /** 解析失败的原因（中文，可直接展示/落日志）。 */
        const val INVALID = "更新清单格式不对（缺少必需字段）"

        /**
         * 解析 `update.json`；任何必需字段缺失或明显不合法都返回 null。
         *
         * 手写解析而不是引 JSON 库：这个清单只有一个平铺对象、字段固定，
         * 而 Android 的 `org.json` 在 JVM 单测里是桩实现（不可用），为一个清单引三方库也不值。
         */
        fun parse(json: String?): UpdateManifest? {
            val text = json?.trim().orEmpty()
            if (!text.startsWith("{")) return null
            val versionCode = MiniJson.long(text, "versionCode") ?: return null
            val versionName = MiniJson.string(text, "versionName")?.takeIf { it.isNotBlank() } ?: return null
            val apkUrl = MiniJson.string(text, "apkUrl")?.takeIf { it.startsWith("http") } ?: return null
            val sizeBytes = MiniJson.long(text, "sizeBytes") ?: return null
            val sha256 = MiniJson.string(text, "sha256")?.trim()?.lowercase()?.takeIf { it.length == 64 } ?: return null
            if (versionCode <= 0L || sizeBytes <= 0L) return null
            return UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                notes = MiniJson.string(text, "notes").orEmpty(),
            )
        }
    }
}

/**
 * 极小的 JSON 取值器（**只支持平铺对象里的字符串/数字**，够 `update.json` 用）。
 *
 * 刻意写得笨一点：不引第三方、不碰 Android 的 org.json，纯 JVM 可测。
 * 认不出的结构一律返回 null（调用方按"清单不合法"处理，不猜）。
 */
internal object MiniJson {

    /** 取字符串值；\u 转义也还原（中文更新说明要能带引号与换行）。 */
    fun string(json: String, key: String): String? {
        val start = valueStart(json, key) ?: return null
        if (start >= json.length || json[start] != '"') return null
        val builder = StringBuilder()
        var index = start + 1
        while (index < json.length) {
            when (val char = json[index]) {
                '"' -> return builder.toString()
                '\\' -> {
                    val next = json.getOrNull(index + 1) ?: return null
                    when (next) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'u' -> {
                            val hex = json.substring(index + 2, (index + 6).coerceAtMost(json.length))
                            val code = hex.toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> builder.append(next)
                    }
                    index += 2
                }
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return null
    }

    /** 取数字值（整数）。 */
    fun long(json: String, key: String): Long? {
        val start = valueStart(json, key) ?: return null
        var end = start
        while (end < json.length && (json[end].isDigit() || json[end] == '-' || json[end] == '+')) end++
        if (end == start) return null
        return json.substring(start, end).toLongOrNull()
    }

    /** 找到 `"key"` 后面的第一个非空白、非冒号字符的位置。 */
    private fun valueStart(json: String, key: String): Int? {
        val needle = "\"" + key + "\""
        var from = 0
        while (true) {
            val keyIndex = json.indexOf(needle, from)
            if (keyIndex < 0) return null
            var index = keyIndex + needle.length
            while (index < json.length && json[index].isWhitespace()) index++
            if (index < json.length && json[index] == ':') {
                index++
                while (index < json.length && json[index].isWhitespace()) index++
                return index
            }
            from = keyIndex + needle.length
        }
    }
}
