package io.github.gua123.mediagate.core.network

/**
 * 地址标签（plan 第 7 章 `address.label`）：局域网 / 公网域名。
 *
 * 它既是展示标签，也是选路的"优先网络"（[SelectionResult.preferred]）。
 */
enum class AddressLabel(val id: String, val zhText: String) {
    LAN("LAN", "局域网"),
    WAN("WAN", "公网"),
    ;

    companion object {
        /** 数据库里的字符串 → 枚举；认不出按 LAN（历史数据默认局域网）。 */
        fun fromId(id: String?): AddressLabel =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: LAN
    }
}

/**
 * 参与选路的一个地址（**R7**：一个连接可挂 LAN / 公网多组地址）。
 *
 * 纯数据、无 Android 依赖，直接由 `address` 表映射而来（:feature:connections 负责映射）。
 *
 * @param id 数据库主键（0 = 还没落库的临时地址）。
 * @param label LAN / WAN。
 * @param scheme 传输方案：http / https / sftp / ftp / file。
 * @param host 主机名或 IP。
 * @param port 端口（LOCAL 用 0）。
 * @param priority 同一网络内的人工优先级，越大越先（plan 第 7 章 `address.priority`）。
 */
data class SelectableAddress(
    val id: Long,
    val label: AddressLabel,
    val scheme: String,
    val host: String,
    val port: Int,
    val priority: Int = 0,
) {

    /** 展示串：`http://192.168.1.10:8080`（端口 0 表示无端口，如本地路径）。 */
    val display: String
        get() = if (port > 0) scheme + "://" + host + ":" + port else scheme + "://" + host

    /** 缓存/日志用稳定 key。 */
    val key: String get() = id.toString() + "@" + scheme + "://" + host + ":" + port
}
