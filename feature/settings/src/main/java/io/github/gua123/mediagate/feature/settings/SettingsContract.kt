package io.github.gua123.mediagate.feature.settings

/**
 * 设置页要展示的「当前连接」信息（**R7/R8**）——由 :app 从连接记录 + 选路结果映射而来。
 *
 * 页面只读它，不持有后端、不查库：设置页是"看一眼现状 + 给出入口"，真正的管理在连接页。
 *
 * @param name 连接名；null = 还没有选当前连接（此时用的是首页选择的本地根目录）。
 * @param protocolText 协议展示名。
 * @param primaryAddress 当前网络下的首选地址（R7 选路结果）；未知为 null。
 * @param selectionReason 选路判定依据的中文说明（R7）。
 * @param browsable 该协议是否已接入（本地 / WebDAV / SFTP / FTP 都可以；协议标识认不出时为 false）。
 */
data class SettingsConnectionUi(
    val name: String? = null,
    val protocolText: String? = null,
    val primaryAddress: String? = null,
    val selectionReason: String? = null,
    val browsable: Boolean = true,
) {

    /** 是否已经指定了当前连接。 */
    val configured: Boolean get() = !name.isNullOrBlank()
}

/**
 * 设置页的一条只读说明（**R7** 网络切换策略 / **R6** 凭据口径）。
 *
 * **R21（2026-10-15）**：说明多了「归属分组」——说明本来就该贴在它解释的那个分组里
 * （选路规则跟着连接、缓存跟着网络、凭据跟着安全），而不是全部塞进一张「网络策略」卡片，
 * 那样既离题又占地方。
 *
 * @param title 小标题（中文）。
 * @param detail 详细说明（中文，可多行）。
 * @param group 这条说明属于哪个分组（默认连接组，老调用点不受影响）。
 */
data class SettingsNote(
    val title: String,
    val detail: String,
    val group: SettingsGroup = SettingsGroup.CONNECTION,
)
