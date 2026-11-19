package io.github.gua123.mediagate.core.common

/**
 * 日志脱敏（2026-11-18）：**保留可辨识性，去掉可定位性**。
 *
 * 背景：真机实测里「LibVLC 播放地址」那一行打的是
 * http://127.0.0.1:42749/m/t.c2Z0cDovL2RlbW9AMTAuMS4yLjM6MjIyMi9zaGFyZS92aWRlb3Mv…
 * 把 /m/ 之后的 token 段按 base64url 解开就是 sftp://demo@10.1.2.3:2222/ + 完整目录路径——
 * 一次日志截图就把内网 IP、用户名、目录结构全送出去（[AppLog] 的环形缓冲还会被崩溃报告整段带走）。
 *
 * 三条口径（2026-11-18 定的分级）：
 *
 * - 高：完整 URL（含 /m/<base64> 或 user@host）→ 只留 scheme + 端口 + 载荷长度（[redactProxyUrl] / [redactUrl]）；
 * - 中：完整远端路径 → 折掉上层目录，只留最后一级目录 + 文件名前 [LOG_KEEP_TAIL] 个字符（[redactPath]）；
 * - 低：只有文件名、没有主机与目录 → 原样保留（排障要靠它认文件）。
 *
 * **它不是加密，也不是完整性保护**：只负责让日志「能认人、不能定位」。
 * 纯 Kotlin + 只依赖 JDK（不碰 android.* / 不碰 base64 编解码本身），JVM 单测直接跑。
 *
 * 本文与单测里的地址、账号、路径全是示例值（10.1.2.3 / demo / share/videos/…）；
 * 真实环境值只从 `.toolchain/real-server.properties`（gitignore）读，绝不写进源码。
 */

/** 文件名默认保留的字符数：demo-sample-2024.mp4 → demo-s….mp4（够认人，认不出完整名字）。 */
const val LOG_KEEP_TAIL: Int = 6

/** 省略号（全角，与 [AppLog] 的行截断口径一致）。 */
private const val ELLIPSIS = "…"

/** 代理地址的路径前缀（与 media:proxy 的 ProxyUrls.PREFIX 同口径；这里不反向依赖它）。 */
private const val PROXY_PREFIX = "/m/"

/**
 * 路径脱敏：去掉上层目录，只留**最后一级目录**与**打码后的文件名**。
 *
 * 例子：
 * - share/videos/demo-sample.mp4 → …/videos/demo-s….mp4
 * - song.mp3 → song.mp3（低危，原样）
 * - /storage/emulated/0/videos/ → …/videos/（目录路径）
 * - "" → ""
 *
 * 为什么不整条删：排障时「哪个文件」是关键信息，所以文件名留前 [keepTail] 个字符 + 扩展名；
 * 「在哪台机器的哪个目录」才是要藏的东西，所以上层目录统一折叠成 …/（不透露层级数与名字）。
 *
 * @param path 远端路径（/ 分隔）、本地绝对路径，或 Windows 风格反斜杠路径（会被统一成 /）。
 * @param keepTail 文件名保留的字符数（不含扩展名）；≤ 0 表示文件名完全打码，只留扩展名。
 */
fun redactPath(path: String, keepTail: Int = LOG_KEEP_TAIL): String {
    val raw = path.trim()
    if (raw.isEmpty()) return ""
    val unified = raw.replace('\\', '/')
    val trailingSlash = unified.endsWith('/')
    val segments = unified.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "/"
    val name = segments.last()
    val dirs = segments.dropLast(1)
    // 结尾斜杠 = 目录路径：最后一段本身就是目录名，保留它（不打码）
    if (trailingSlash) return if (dirs.isEmpty()) name + "/" else "…/" + name + "/"
    // 低危：没有目录，只有文件名 → 原样保留（排障要靠它认出是哪一个文件）
    if (dirs.isEmpty()) return name
    return "…/" + dirs.last() + "/" + maskName(name, keepTail)
}

/**
 * 主机脱敏：留得住「内网还是公网 / 哪个域名后缀」，留不住具体主机。
 *
 * 例子：
 * - 10.1.2.3 → 10.1.*.*（IPv4：只留前两段）
 * - dav.example.com → d***.com
 * - demo → d***
 * - 127.0.0.1 → 127.0.0.1（回环地址没有可定位信息，原样保留）
 * - 2001:db8::1 → 2001:…
 */
fun redactHost(host: String): String {
    val h = host.trim().trim('[', ']')
    if (h.isEmpty()) return ""
    // 已经是打码形态（192.168.*.* / n*** / 2001:…）：原样返回。
    // 崩溃报告会把**已经脱敏过的**日志行再整段过一遍（[redactForLog]），重复施加不能越改越乱。
    if (h.contains('*')) return h
    // 回环 / 本机名：没有任何定位信息，保留反而更有助于排障
    if (h == "127.0.0.1" || h == "::1" || h.equals("localhost", ignoreCase = true)) return h
    if (h.contains(':')) return h.substringBefore(':') + ":…"
    val parts = h.split('.')
    if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) {
        return parts[0] + "." + parts[1] + ".*.*"
    }
    if (parts.size >= 2) return parts.first().take(1) + "***." + parts.last()
    return h.take(1) + "***"
}

/**
 * URL 脱敏：去掉 user:pass@（只留 ***@ 说明「原本带凭据」）、打码主机、路径走 [redactPath]，
 * 查询串与片段整段丢弃。端口保留：排障时「2222 还是 22」是有效信息，端口本身定位不到主机。
 *
 * 例子：
 * - sftp://demo@10.1.2.3:2222/share/videos/demo-sample.mp4
 *   → sftp://<凭据>@10.1.*.*:2222/…/videos/demo-s….mp4
 * - http://10.1.2.3:8080 → http://10.1.*.*:8080
 * - not-a-url/x.mp4 → …/not-a-url/x.mp4
 */
fun redactUrl(url: String): String {
    val raw = url.trim()
    if (raw.isEmpty()) return ""
    val schemeEnd = raw.indexOf("://")
    // 不是 URL（相对路径 / 纯文件名）：按路径口径处理
    if (schemeEnd <= 0) return redactPath(raw)
    val scheme = raw.substring(0, schemeEnd)
    val rest = raw.substring(schemeEnd + 3)
    val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authEnd)
    val tail = if (authEnd < rest.length) rest.substring(authEnd) else ""

    val at = authority.lastIndexOf('@')
    val hasUser = at >= 0
    val hostPort = if (hasUser) authority.substring(at + 1) else authority
    val colon = hostPort.lastIndexOf(':')
    val portText = if (colon > 0) hostPort.substring(colon + 1) else ""
    val hasPort = colon > 0 && portText.isNotEmpty() && portText.all { it.isDigit() }
    val host = if (hasPort) hostPort.substring(0, colon) else hostPort

    val sb = StringBuilder()
    sb.append(scheme).append("://")
    if (hasUser) sb.append("***@")
    sb.append(redactHost(host))
    if (hasPort) sb.append(':').append(portText)

    // 只丢查询串：这些路径里的 # 是**文件名的一部分**（如 #demo.mp4），不是 URL 片段
    val pathOnly = tail.substringBefore('?')
    if (pathOnly.isNotEmpty()) {
        val pathText = redactPath(pathOnly)
        if (pathText.isNotEmpty() && !pathText.startsWith("/")) sb.append('/')
        sb.append(pathText)
    }
    return sb.toString()
}

/**
 * 回环代理播放地址脱敏：/m/ 之后的段（base64url token 或百分号编码的伪 URI）整段替换成
 * /m/<已打码>（N 字符）——**长度留着**（「地址是不是长得离谱」在排障时有用），内容一个字都不留。
 *
 * 例子：
 * - http://127.0.0.1:42749/m/t.c2Z0cDovL2RlbW9AMTAuMS4yLjM6… → http://127.0.0.1:42749/m/<已打码>（74 字符）
 * - http://127.0.0.1:42749/m/sftp%3A%2F%2Fdemo%40… → http://127.0.0.1:42749/m/<已打码>（N 字符）
 * - https://dav.example.com/dav/a.mp4 → https://d***.com/…/dav/a.mp4（委托 [redactUrl]）
 *
 * 注意：只改日志里的**呈现**，不动 ProxyUrls 的编解码本身。
 */
fun redactProxyUrl(url: String): String {
    val raw = url.trim()
    if (raw.isEmpty()) return ""
    val idx = raw.indexOf(PROXY_PREFIX)
    if (idx < 0) return redactUrl(raw)
    val head = raw.substring(0, idx)
    val payload = raw.substring(idx + PROXY_PREFIX.length).substringBefore('?')
    val safeHead = redactUrl(head)
    if (payload.isEmpty()) return safeHead + PROXY_PREFIX
    return safeHead + PROXY_PREFIX + "***（" + payload.length + " 字符）"
}

/**
 * 文本里的主机名打码：把 [text] 中出现的 [host] 原样替换成 [redactHost] 的结果。
 *
 * 给「异常文案复用」的日志用：`StorageException` 的消息会带主机（界面要**看得到**自己填的地址），
 * 但我们往 AppLog 抄一份时不该把主机一起抄进去。已知主机名精确替换，比正则猜要稳。
 */
fun redactHostInText(text: String, host: String): String {
    val h = host.trim()
    if (h.isEmpty() || text.isEmpty()) return text
    return text.replace(h, redactHost(h))
}

/**
 * 文本里的账号名打码：把形如 `demo@host` 的 `demo@` 换成 `***@`。
 *
 * 只认「字母数字 + . _ % + -」组成的本地部分，随后紧跟 `@`；路径里的正常文字不会被误伤。
 */
fun redactUserInText(text: String): String =
    if (text.isEmpty()) text else USER_AT.replace(text, "***@")

private val USER_AT = Regex("[A-Za-z0-9._%+\\-]+@")

/** 主机 + 端口：10.1.2.3:2222 → 10.1.*.*:2222（端口 0 视作没有端口）。 */
fun redactEndpoint(host: String, port: Int): String =
    if (port > 0) redactHost(host) + ":" + port else redactHost(host)

/**
 * 自由文本脱敏（日志行 / 崩溃报告的「异常消息」「堆栈」「面包屑」）：把 [text] 里**认得出**的
 * 定位信息一次过掉。
 *
 * 为什么单独要有它：`StorageException` 的消息里带完整远端路径与主机——那是**给界面看的**
 * （用户得看见自己填的地址），一个字都不能改；但它们会被直接拼进崩溃报告 / 诊断文本，
 * 而那份文本是**要复制、分享、贴进聊天**的。所以只在「抄出去的那一层」统一过一遍，从源头不改文案。
 *
 * 处理顺序（不能换）：
 * 1. 已知主机 [host] → [redactHost]（精确替换，最可靠）；
 * 2. 带 scheme 的 URL → [redactUrl]；**已经带 `***` 的跳过**（报告里本就有已脱敏的日志行）；
 * 3. 裸的 `账号@` → `***@`（[redactUserInText]）；
 * 4. 裸露的 IPv4 → [redactHost]（例：`连接 10.1.2.3 失败`）；
 * 5. 绝对路径（≥2 段，前面是行首/空白/括号/引号/冒号等分隔）→ [redactPath]。
 *
 * **幂等**：`redactForLog(redactForLog(x)) == redactForLog(x)`（单测钉住）——崩溃报告会把已经
 * 脱敏过的日志行再整段过一遍，重复施加不能把 `…/videos/demo-s….mp4` 越改越短。
 *
 * 边界：不认识「没有 scheme 又没有 IP 的主机名 + 端口」（`demo:22` 与堆栈里的 `Foo.kt:12` 长得一样），
 * 那种情形请传 [host] 让第 1 步精确替换。
 */
fun redactForLog(text: String, host: String = ""): String {
    if (text.isEmpty()) return text
    var out = if (host.isBlank()) text else redactHostInText(text, host)
    out = URL_IN_TEXT.replace(out) { match -> if (match.value.contains("***")) match.value else redactUrl(match.value) }
    out = redactUserInText(out)
    out = IPV4_IN_TEXT.replace(out) { match -> redactHost(match.value) }
    out = PATH_IN_TEXT.replace(out) { match ->
        val path = match.groupValues[2]
        // 系统/框架路径每台设备都一样，打码没有收益，反而会把原生崩溃里的 so 位置毁掉
        if (SYSTEM_PATH_PREFIXES.any { path.startsWith(it) }) match.value else match.groupValues[1] + redactPath(path)
    }
    return out
}

/** 文本里的 URL（到空白或中文/英文包裹符号为止，避免把「）」吃进地址）。 */
private val URL_IN_TEXT = Regex("[A-Za-z][A-Za-z0-9+.\\-]*://[^\\s\"'<>（）()【】\\[\\]]+")

/** 文本里的 IPv4。 */
private val IPV4_IN_TEXT = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")

/**
 * 文本里的绝对路径：至少两段，且前面必须是分隔符（行首 / 空白 / 中英文括号 / 引号 / 冒号 / 逗号）。
 *
 * 两条克制的设计：
 * - 段内允许空格——真实文件名里有空格（`#demo clip.mp4`），不许空格就只脱敏到第一个空格，
 *   文件名的后半截会漏在文本里；
 * - 不以 `…` 作分隔符——于是 [redactPath] 自己的输出（`…/dir/file….mp4`）不会被再折叠一次。
 */
private val PATH_IN_TEXT = Regex(
    """(^|[\s(（\["'：，,、；;])((?:/[^\n/:：*"'<>|（）()\[\]，,；;]+){2,}/?)""",
    RegexOption.MULTILINE,
)

/**
 * 系统 / 框架路径前缀：原生堆栈（ApplicationExitInfo 的 trace）里全是这些，
 * 每台设备一模一样，**打码没有收益**，还会把「崩在哪个 so」这条线索毁掉。
 */
private val SYSTEM_PATH_PREFIXES = listOf(
    "/system/", "/apex/", "/vendor/", "/product/", "/proc/", "/dev/",
    "/data/app/", "/data/dalvik-cache/", "/data/misc/",
)

/**
 * 文件名打码：demo-sample-2024.mp4 → demo-s….mp4。
 *
 * 名字本来就短（≤ keep）时原样返回——再打码只会让日志失去辨识价值。
 */
private fun maskName(name: String, keep: Int): String {
    if (name.isEmpty()) return name
    val dot = name.lastIndexOf('.')
    val hasExt = dot in 1 until name.length - 1
    val base = if (hasExt) name.substring(0, dot) else name
    val ext = if (hasExt) name.substring(dot) else ""
    if (keep <= 0) return ELLIPSIS + ext
    if (base.length <= keep) return name
    return base.take(keep).trimEnd() + ELLIPSIS + ext
}
