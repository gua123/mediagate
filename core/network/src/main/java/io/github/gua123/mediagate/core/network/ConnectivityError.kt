package io.github.gua123.mediagate.core.network

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 连通性错误分类（**R8**：连通性测试要给每地址三段耗时与**错误分类**；**R7**：选路失败要能解释原因）。
 *
 * 枚举值（[code]）是稳定标识，可落库 / 打日志 / 后续做统计；[zhText] 与 [hint] 是给人看的中文
 * （R16 单语），界面直接展示，不需要再写一层 when。
 *
 * 分类来源有两条：底层异常（[fromThrowable]）与协议状态码（[fromHttpStatus] / [fromMessage]）。
 */
enum class ConnectivityError(
    val code: String,
    val zhText: String,
    val hint: String,
) {
    /** DNS 解析失败：域名写错，或当前网络没有 DNS。 */
    DNS_FAILED("DNS_FAILED", "DNS 解析失败", "域名是否正确？当前网络能否解析外网域名"),

    /** 连接被拒绝：端口没开放 / 服务没启动 / 被防火墙拒绝。 */
    CONNECTION_REFUSED("CONNECTION_REFUSED", "连接被拒绝", "检查端口与服务是否已启动（改错端口就是这一类）"),

    /** 超时：地址不可达或对方无响应。 */
    TIMEOUT("TIMEOUT", "连接超时", "地址不可达或对方无响应；公网地址请确认端口映射"),

    /** 网络不可达：本机路由到不了该地址（常见于蜂窝网络下访问局域网 IP）。 */
    NETWORK_UNREACHABLE("NETWORK_UNREACHABLE", "网络不可达", "当前网络到不了这个地址（局域网 IP 在蜂窝下通常不可达）"),

    /** 认证失败：账号密码错（HTTP 401 / SFTP 认证失败 / FTP 530）。 */
    AUTH_FAILED("AUTH_FAILED", "认证失败", "账号或密码不正确"),

    /** 权限不足：认证过了但没有该资源的权限（HTTP 403）。 */
    PERMISSION_DENIED("PERMISSION_DENIED", "无访问权限", "账号没有该目录的读权限"),

    /** 协议不支持：服务器不是目标协议（HTTP 405/501、非 WebDAV 服务）。 */
    PROTOCOL_UNSUPPORTED("PROTOCOL_UNSUPPORTED", "协议不支持", "这个地址不是目标协议的服务（例如不是 WebDAV）"),

    /** 路径不存在：根路径 / 目录写错（HTTP 404）。 */
    PATH_NOT_FOUND("PATH_NOT_FOUND", "路径不存在", "根路径或目录是否写对"),

    /** 地址或端口本身不合法（表单校验兜底；正常流程不会走到这里）。 */
    PORT_INVALID("PORT_INVALID", "地址或端口不合法", "端口范围是 1-65535"),

    /** 该协议没有握手实现（或协议标识认不出）：只做到 DNS/TCP 两段，未做协议握手。 */
    NOT_IMPLEMENTED("NOT_IMPLEMENTED", "协议后端未接入", "只验证了 DNS 与 TCP 两段，没做协议握手（该协议暂无握手实现）"),

    /** TLS/证书问题。 */
    TLS_FAILED("TLS_FAILED", "安全连接失败", "证书不受信任或 TLS 版本不匹配"),

    /**
     * 明文 http 被系统策略拦截（真机实测 2026-10-02：连接页显示
     * `UnknownServiceException CLEARTEXT communication ... not permitted by network security policy`）。
     *
     * 根因是清单没放开明文（targetSdk ≥ 28 默认禁止），**不是**网络不通——所以必须单独一类，
     * 免得界面把它说成「网络不可达」，把用户引去查网线和端口。
     */
    CLEARTEXT_BLOCKED("CLEARTEXT_BLOCKED", "明文流量被系统拦截", "在连接页打开「允许明文 http」，或改用 https"),

    /** 其他未分类错误（详情看 message）。 */
    UNKNOWN("UNKNOWN", "未知错误", "查看详情"),
    ;

    /** 给界面用的一行中文：「认证失败 · 账号或密码不正确」。 */
    val display: String get() = zhText + " · " + hint

    companion object {

        /**
         * 底层异常 → 分类。
         *
         * 顺序有讲究：[SocketTimeoutException] 是 [java.io.InterruptedIOException] 的子类，
         * [ConnectException] 又被 [NoRouteToHostException] 之外的各种"连不上"复用，所以先判具体类型，
         * 再看消息里是否明确写着 refused。
         */
        fun fromThrowable(t: Throwable): ConnectivityError = when (t) {
            is UnknownHostException -> DNS_FAILED
            is SocketTimeoutException -> TIMEOUT
            is PortUnreachableException -> NETWORK_UNREACHABLE
            is NoRouteToHostException -> NETWORK_UNREACHABLE
            is SSLHandshakeException -> TLS_FAILED
            is ConnectException -> {
                val text = (t.message ?: "").lowercase()
                if (text.contains("refused") || text.contains("拒绝")) CONNECTION_REFUSED else NETWORK_UNREACHABLE
            }
            is IOException -> {
                val text = (t.message ?: "").lowercase()
                when {
                    // OkHttp 在明文被系统拦时抛 UnknownServiceException，消息里必带 CLEARTEXT
                    text.contains("cleartext") -> CLEARTEXT_BLOCKED
                    text.contains("refused") -> CONNECTION_REFUSED
                    text.contains("timed out") || text.contains("timeout") -> TIMEOUT
                    text.contains("unreachable") || text.contains("no route") -> NETWORK_UNREACHABLE
                    else -> UNKNOWN
                }
            }
            else -> UNKNOWN
        }

        /** HTTP 状态码 → 分类；无法归类返回 null。 */
        fun fromHttpStatus(status: Int): ConnectivityError? = when (status) {
            401 -> AUTH_FAILED
            403 -> PERMISSION_DENIED
            404, 409, 410 -> PATH_NOT_FOUND
            405, 501 -> PROTOCOL_UNSUPPORTED
            407 -> AUTH_FAILED
            423 -> PERMISSION_DENIED
            408, 504 -> TIMEOUT
            else -> null
        }

        /**
         * 从一段中文/英文提示里猜分类（尽力而为）。
         *
         * 用途：数据层（`data:storage-webdav` 的 [io.github.gua123.mediagate.core.model.ProbeReport]）只回一句
         * 人话，不带上枚举；连接管理页要的是稳定分类。先抽 HTTP 状态码（最可靠），再退回关键词匹配。
         * 匹配不上就是 [UNKNOWN]，绝不猜成"成功"。
         */
        fun fromMessage(message: String?): ConnectivityError? {
            if (message.isNullOrBlank()) return null
            val text = message.trim()
            HTTP_CODE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
                fromHttpStatus(code)?.let { return it }
            }
            return when {
                // 顺序：明文被拦最先认（它的提示里常伴 http/https 字样，别被后面的规则抢走）
                text.contains("明文") || text.contains("cleartext", true) -> CLEARTEXT_BLOCKED
                text.contains("DNS") || text.contains("解析失败") -> DNS_FAILED
                text.contains("拒绝") -> CONNECTION_REFUSED
                text.contains("超时") || text.contains("timeout", true) -> TIMEOUT
                text.contains("不可达") || text.contains("unreachable", true) -> NETWORK_UNREACHABLE
                text.contains("密码") || text.contains("认证") || text.contains("401") -> AUTH_FAILED
                text.contains("权限") || text.contains("403") -> PERMISSION_DENIED
                text.contains("不支持") || text.contains("PROPFIND") -> PROTOCOL_UNSUPPORTED
                text.contains("不存在") || text.contains("404") -> PATH_NOT_FOUND
                text.contains("证书") || text.contains("TLS") -> TLS_FAILED
                else -> UNKNOWN
            }
        }

        private val HTTP_CODE = Regex("\\b([1-5]\\d{2})\\b")
    }
}

/**
 * 三段计时的阶段（**R8**，plan 4.5：DNS 解析 / TCP 握手 / 协议握手）。
 *
 * 测试结果带上失败发生在哪一段，界面才能把耗时行标红在正确的位置。
 */
enum class TestStage(val code: String, val zhText: String) {
    DNS("DNS", "DNS 解析"),
    TCP("TCP", "TCP 连接"),
    HANDSHAKE("HANDSHAKE", "协议握手"),
}
