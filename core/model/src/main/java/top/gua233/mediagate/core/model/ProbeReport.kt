package io.github.gua123.mediagate.core.model

/**
 * 连通性测试报告（R8 多连接与自测，plan 4.5 的三段计时）。
 *
 * 一段连接是否可用要分三段看：DNS 解析 → TCP 握手 → 协议握手（WebDAV PROPFIND /
 * SFTP banner+认证 / FTP 220+登录）。这样「改错端口能复现对应错误」才有明确的错误分类，
 * 而不是一句笼统的「连接失败」。
 */
data class ProbeReport(
    /** 整条链路是否可用（三段全部通过且协议握手成功）。 */
    val ok: Boolean = false,
    /** DNS 解析耗时（毫秒）；本地后端恒为 0。 */
    val dnsMs: Long = 0,
    /** TCP 握手耗时（毫秒）；本地后端恒为 0。 */
    val connectMs: Long = 0,
    /** 协议握手耗时（毫秒）；本地后端恒为 0。 */
    val handshakeMs: Long = 0,
    /** 失败原因或提示（如「401 账号或密码错误」「主机密钥已变更」）；成功时为 null。 */
    val message: String? = null,
) {
    /** 三段耗时之和（毫秒），用于列表里显示总耗时。 */
    val totalMs: Long get() = dnsMs + connectMs + handshakeMs
}
