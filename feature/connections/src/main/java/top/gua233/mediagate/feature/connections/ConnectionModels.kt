package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.database.AddressEntity
import io.github.gua123.mediagate.core.database.ConnectionEntity
import io.github.gua123.mediagate.core.database.NetworkRuleEntity
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.NetworkCapability
import io.github.gua123.mediagate.core.network.NetworkRule
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress

/**
 * 界面用的连接记录（**R8**）：把 `connection` + `address` + `network_rule` 三张表拼成一个不可变快照。
 *
 * [protocol] 允许为 null：数据库里存的是字符串，将来若升级出未知协议，界面照原样显示 [protocolId]，
 * **不猜**成别的协议（错了会引导用户点错按钮）。
 */
data class ConnectionRecord(
    val id: Long,
    val name: String,
    val protocolId: String,
    val protocol: ProtocolKind?,
    val basePath: String,
    val username: String?,
    val hasSecret: Boolean,
    val options: ConnectionOptions,
    val tls: String?,
    val lastWorkingAddressId: Long?,
    val lastCheckedAt: Long?,
    val addresses: List<AddressRecord>,
    val rules: List<RuleRecord>,
) {

    /** 协议展示名（未知协议显示原始字符串）。 */
    val protocolText: String get() = protocol?.zhText ?: protocolId

    /** 该连接是否本轮（M4）能真正列目录/播放：LOCAL 与 WEBDAV 可以，SFTP/FTP 待 M5。 */
    val browsable: Boolean
        get() = protocol == ProtocolKind.LOCAL || protocol == ProtocolKind.WEBDAV

    /** 交给选路逻辑的地址列表（R7）。 */
    fun selectableAddresses(): List<SelectableAddress> = addresses.map { it.toSelectable() }

    /** 交给选路逻辑的规则列表（R7）。 */
    fun networkRules(): List<NetworkRule> = rules.map { it.toNetworkRule() }
}

/** 界面用的地址记录（**R7** 多地址）。 */
data class AddressRecord(
    val id: Long,
    val connectionId: Long,
    val label: AddressLabel,
    val scheme: String,
    val host: String,
    val port: Int,
    val priority: Int,
) {

    /** 展示串：`LAN · http://192.168.1.10:8080`。 */
    val display: String get() = label.zhText + " · " + toSelectable().display

    /** 首选地址标记用（与 [SelectableAddress] 同构）。 */
    fun toSelectable(): SelectableAddress = SelectableAddress(
        id = id,
        label = label,
        scheme = scheme,
        host = host,
        port = port,
        priority = priority,
    )
}

/** 界面用的规则记录（**R7**）。 */
data class RuleRecord(
    val id: Long,
    val connectionId: Long,
    val transport: NetworkCapability?,
    val ssidPattern: String?,
    val localSubnet: String?,
    val prefer: AddressLabel,
) {

    fun toNetworkRule(): NetworkRule = NetworkRule(
        id = id,
        transport = transport,
        ssidPattern = ssidPattern,
        localSubnet = localSubnet,
        prefer = prefer,
    )

    /** 中文摘要（列表里展示）。 */
    val display: String get() = toNetworkRule().display
}

/** `connection` 行 → 记录（不含地址与规则，由仓储拼装）。 */
internal fun ConnectionEntity.toRecordBase(): ConnectionRecord = ConnectionRecord(
    id = id,
    name = name,
    protocolId = protocol,
    protocol = ProtocolKind.fromId(protocol),
    basePath = basePath,
    username = username,
    hasSecret = !secretRef.isNullOrBlank(),
    options = ConnectionOptions.parse(options),
    tls = tls,
    lastWorkingAddressId = lastWorkingAddressId,
    lastCheckedAt = lastCheckedAt,
    addresses = emptyList(),
    rules = emptyList(),
)

/** `address` 行 → 记录（**R7**：label 字符串容错成枚举）。 */
internal fun AddressEntity.toRecord(): AddressRecord = AddressRecord(
    id = id,
    connectionId = connectionId,
    label = AddressLabel.fromId(label),
    scheme = scheme,
    host = host,
    port = port,
    priority = priority,
)

/** `network_rule` 行 → 记录（**R7**）。 */
internal fun NetworkRuleEntity.toRecord(): RuleRecord = RuleRecord(
    id = id,
    connectionId = connectionId,
    transport = transport?.let { NetworkCapability.fromId(it) },
    ssidPattern = ssidPattern,
    localSubnet = localSubnet,
    prefer = AddressLabel.fromId(prefer),
)
