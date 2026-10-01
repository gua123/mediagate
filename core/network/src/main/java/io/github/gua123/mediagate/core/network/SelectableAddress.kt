package io.github.gua123.mediagate.core.network

/**
 * 地址标签（plan 第 7 章 `address.label`）：局域网 / 公网域名。
 *
 * 它既是展示标签，也是选路规则的"偏好目标"（[NetworkRule.prefer]）。
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
 * @param priority 同一连接内的人工优先级，越大越先（plan 第 7 章 `address.priority`）。
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

/**
 * 一条选路规则（**R7**，plan 第 7 章 `network_rule` 表：transport / ssidPattern / localSubnet / prefer）。
 *
 * 三个条件是**与**关系；为 null 的条件表示"不限制"。三者都为 null 的规则匹配一切网络——
 * 相当于"这个连接总是用它偏好的那一类地址"。
 *
 * @param transport 传输类型限制；null = 任意。
 * @param ssidPattern Wi-Fi 名称匹配；支持 `*` 通配（如 `Home*`）；null = 任意。
 * @param localSubnet 本机网段匹配；支持 CIDR（`192.168.1.0/24`）与前缀（`192.168.1.`）；null = 任意。
 * @param prefer 命中后优先使用的地址标签（LAN / WAN）。
 */
data class NetworkRule(
    val id: Long = 0L,
    val transport: NetworkCapability? = null,
    val ssidPattern: String? = null,
    val localSubnet: String? = null,
    val prefer: AddressLabel = AddressLabel.LAN,
) {

    /**
     * 规则"具体度"：条件写得越多越具体，命中时优先采用。
     *
     * 权重：SSID(3) > 本机网段(2) > 传输类型(1)——SSID 最贴近"到家了/到公司了"这一语义，
     * 传输类型最泛（"蜂窝就用公网"）。数值只用于排序，不代表业务含义。
     */
    val specificity: Int
        get() = (if (ssidPattern.isNullOrBlank()) 0 else 3) +
            (if (localSubnet.isNullOrBlank()) 0 else 2) +
            (if (transport == null) 0 else 1)

    /** 中文摘要（界面展示规则列表用）。 */
    val display: String
        get() = buildString {
            append("偏好").append(prefer.zhText)
            val parts = mutableListOf<String>()
            transport?.let { parts += it.zhText }
            ssidPattern?.takeIf { it.isNotBlank() }?.let { parts += "SSID≈" + it }
            localSubnet?.takeIf { it.isNotBlank() }?.let { parts += "网段 " + it }
            append("（").append(if (parts.isEmpty()) "任意网络" else parts.joinToString(" + ")).append("）")
        }
}
