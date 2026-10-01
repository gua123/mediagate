package io.github.gua123.mediagate.core.common

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 异常 / 底层消息 → 界面可展示的**中文**一句话（**R16**：界面简体中文单语）。
 *
 * 为什么需要它：底层库（OkHttp / JSch / Apache Commons Net / LibVLC / Media3）抛出的 message 一律是英文，
 * 直接 `t.message` 甩到界面就成了这种中英夹生句——
 * 「网络不可达：UnknownServiceException CLEARTEXT communication to 192.168.1.10 not permitted by network security policy」。
 * 用户 2026-10-02 明确要求：「app 中界面的语言直接使用中文即可，不需要中英双语」。
 *
 * 规则（顺序有意义）：
 * 1. **已经是中文**（含汉字）的消息原样返回——我们自己的中文提示比任何映射都准；
 * 2. 英文消息按关键词归类成中文（明文被系统拦 / 解析失败 / 超时 / 拒绝 / 不可达 / 证书 / 连接被中断 / 认证 / 路径）；
 * 3. 归不了类退回 [fallback]——原始异常由 [AppLog] 记在诊断日志里，界面不再堆英文。
 */
object ErrorText {

    /** 界面兜底文案：说清"去哪看"，而不是把英文堆上去。 */
    const val FALLBACK: String = "操作失败（详情见诊断日志）"

    /**
     * 异常 → 中文一句话。
     *
     * 顺序：**自己的中文**（含汉字）优先 → **异常类型**（UnknownHostException / SocketTimeoutException /
     * ConnectException / NoRouteToHostException / SSLException 这些类型本身就说明了问题，message 里
     * 常常只有主机名）→ 英文 message 关键词归类 → 兜底。
     */
    fun of(t: Throwable, fallback: String = FALLBACK): String {
        val raw = t.message?.trim().orEmpty()
        if (hasChinese(raw)) return raw
        byType(t)?.let { return it }
        return classify(raw) ?: fallback
    }

    /** 异常类型 → 中文；认不出返回 null。 */
    private fun byType(t: Throwable): String? = when (t) {
        is UnknownHostException -> "域名解析失败：检查主机名与当前网络的 DNS"
        is SocketTimeoutException -> "连接超时：地址不可达或对方没有响应"
        // NoRouteToHostException 也是 SocketException，但没有"拒绝"含义，必须排在 ConnectException 之前判
        is NoRouteToHostException -> "网络不可达：当前网络到不了这个地址（局域网 IP 在蜂窝下通常不可达）"
        is ConnectException -> "连接被拒绝：端口没开、服务没启动或被防火墙挡住"
        is SSLException -> "安全连接失败：证书不受信任或 TLS 版本不匹配"
        else -> null
    }

    /** 已有的一段消息（如后端拼好的 `ProbeReport.message`）→ 中文一句话。 */
    fun of(message: String?, fallback: String = FALLBACK): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        if (hasChinese(raw)) return raw
        return classify(raw) ?: fallback
    }

    /** 消息里有没有汉字（判断"这条提示已经是中文"）。 */
    fun hasChinese(text: String?): Boolean =
        text.orEmpty().any { it.code in CJK_START..CJK_END }

    /**
     * 英文（或中英夹生）消息 → 中文分类；认不出返回 null。
     *
     * 关键词挑的是各家库实际会写的字眼；**宁可认不出，也不要把用户引到错误的排查方向**。
     */
    fun classify(message: String?): String? {
        val text = message.orEmpty().lowercase()
        if (text.isEmpty()) return null
        return when {
            text.contains("cleartext") ->
                "系统禁止明文 http：请改用 https，或在连接页打开「允许明文 http」"
            text.contains("unknownhost") || text.contains("no address associated") ||
                text.contains("nodename nor servname") ->
                "域名解析失败：检查主机名与当前网络的 DNS"
            text.contains("timeout") || text.contains("timed out") ->
                "连接超时：地址不可达或对方没有响应"
            text.contains("refused") ->
                "连接被拒绝：端口没开、服务没启动或被防火墙挡住"
            text.contains("unreachable") || text.contains("no route") ->
                "网络不可达：当前网络到不了这个地址（局域网 IP 在蜂窝下通常不可达）"
            text.contains("ssl") || text.contains("certificat") || text.contains("trust") ->
                "安全连接失败：证书不受信任或 TLS 版本不匹配"
            text.contains("reset") || text.contains("broken pipe") || text.contains("eof") ->
                "连接被对端中断：服务器提前关闭了连接"
            text.contains("auth") || text.contains("401") ->
                "认证失败：账号或密码不正确"
            text.contains("permission denied") || text.contains("403") ->
                "没有访问权限：账号对该目录没有读权限"
            text.contains("no such file") || text.contains("not found") || text.contains("404") ->
                "路径不存在：根路径或目录写错了"
            text.contains("read-only") || text.contains("readonly") ->
                "目标目录只读：无法写入"
            else -> null
        }
    }

    private const val CJK_START = 0x4E00
    private const val CJK_END = 0x9FFF
}
