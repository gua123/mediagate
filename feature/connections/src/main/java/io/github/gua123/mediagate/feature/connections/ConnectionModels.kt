package io.github.gua123.mediagate.feature.connections

import io.github.gua123.mediagate.core.database.AddressEntity
import io.github.gua123.mediagate.core.database.ConnectionEntity
import io.github.gua123.mediagate.core.network.AddressLabel
import io.github.gua123.mediagate.core.network.ProtocolKind
import io.github.gua123.mediagate.core.network.SelectableAddress

/**
 * 界面用的连接记录（**R8**）：把 `connection` + `address` + `network_rule` 三张表拼成一个不可变快照。
 *
 * [prefer] 是**优先网络**（R7，2026-10-25 起）——默认局域网 = 先内网后外网；
 * 数据库里仍沿用 `network_rule.prefer` 这一列（表结构不迁移），但不再使用
 * 传输类型 / SSID / 本机网段这些旧条件。
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
    val prefer: AddressLabel = AddressLabel.LAN,
) {

    /** 协议展示名（未知协议显示原始字符串）。 */
    val protocolText: String get() = protocol?.zhText ?: protocolId

    /**
     * 该连接是否已能真正列目录/播放（**R2** 四协议后端齐备：本地 / WebDAV / SFTP / FTP 都可以）。
     *
     * 只有"数据库里的协议标识认不出"（[protocol] == null）才为 false：那时界面照实说明，
     * 并且不允许设为当前连接——否则会静默退回本地根目录，用户以为已经切过去了。
     */
    val browsable: Boolean
        get() = protocol != null

    /** 交给选路逻辑的地址列表（R7）。 */
    fun selectableAddresses(): List<SelectableAddress> = addresses.map { it.toSelectable() }
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
    prefer = AddressLabel.LAN,
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
