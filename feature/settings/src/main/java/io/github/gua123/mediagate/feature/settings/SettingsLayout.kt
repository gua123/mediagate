package io.github.gua123.mediagate.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * 设置页的分组（**R21**，2026-10-15 用户要求「优化一下设置页面的信息排列等」）。
 *
 * 为什么要有它：设置页是**一张一张卡片攒出来的**（当前连接 → 保活 → 网络策略 → 播放 → 网络与缓冲
 * → 诊断 → 主机密钥 → 更新），攒到现在的问题很具体：
 * - 高频的（播放、更新）和低频危险操作（清缓存、忘主机密钥、看崩溃日志）**同一个视觉权重**；
 * - 「网络切换策略」那张卡里塞了 4 条**不同主题**的说明（选路 / 缓存 / 凭据 / 协议），
 *   跟它下面的「网络与缓冲」既不挨着也不是一回事；
 * - 诊断卡越来越长（引擎自述 + 探针 + 播放指标 + 崩溃 + 面包屑），把「更新」顶到了很下面。
 *
 * 结论：分成 6 组、按**使用频率**排（常用在前、排障垫底），每组一行小标题 + 一句说明，
 * 组内可折叠（**诊断默认折叠**：它是排障用的，平时不用看）。
 *
 * 枚举的**声明顺序就是页面顺序**（[settingsGroupOrder] 直接返回 entries），有 JVM 单测钉住。
 */
enum class SettingsGroup(
    /** 分组标题的资源 id。 */
    val titleRes: Int,
    /** 分组副标题（一句话说清这组管什么）。 */
    val subtitleRes: Int,
    /** 默认是否折叠（只有排障类的组是 true）。 */
    val collapsedByDefault: Boolean = false,
) {
    CONNECTION(R.string.settings_group_connection, R.string.settings_group_subtitle_connection),
    PLAYBACK(R.string.settings_group_playback, R.string.settings_group_subtitle_playback),
    NETWORK(R.string.settings_group_network, R.string.settings_group_subtitle_network),
    UPDATE(R.string.settings_group_update, R.string.settings_group_subtitle_update),
    SECURITY(R.string.settings_group_security, R.string.settings_group_subtitle_security),
    DIAGNOSTICS(
        R.string.settings_group_diagnostics,
        R.string.settings_group_subtitle_diagnostics,
        collapsedByDefault = true,
    ),
}

/**
 * 页面从上到下的分组顺序（**纯函数**，单测钉住）。
 *
 * 口径：常用的在前（连接 → 播放 → 网络），**排障类垫底**（安全 → 诊断），
 * 中间放"偶尔用一次"的更新。
 */
fun settingsGroupOrder(): List<SettingsGroup> = SettingsGroup.entries.toList()

/** 取属于某个分组的只读说明（纯函数；没给分组归属的老数据落在连接组）。 */
fun settingsNotesFor(notes: List<SettingsNote>, group: SettingsGroup): List<SettingsNote> =
    notes.filter { it.group == group }

/**
 * 各分组的**内容插槽**（由 :app 注入）。
 *
 * 为什么是具名插槽而不是一个 `ColumnScope.() -> Unit`：那样只能"整体追加在最后"，
 * 而 R21 要的是把各块**放进对应的分组里**。:feature:settings 仍然不反向依赖 :app 的模块，
 * 插槽里的内容继续遵守"组合函数零 IO"。
 *
 * 没给插槽的分组**不渲染空卡片**（[groupsWithContent] 说了算：连接与播放是内置的，永远有内容）。
 */
class SettingsSections(
    /** 播放设置（画中画自动进入、控件自动隐藏……）。 */
    val playback: (@Composable ColumnScope.() -> Unit)? = null,
    /** 网络与缓冲（预读 / 段大小 / 缓存上限与用量）。 */
    val network: (@Composable ColumnScope.() -> Unit)? = null,
    /** 应用内更新。 */
    val update: (@Composable ColumnScope.() -> Unit)? = null,
    /** 安全与凭据（SFTP 主机密钥……）。 */
    val security: (@Composable ColumnScope.() -> Unit)? = null,
    /** 诊断与日志（崩溃报告 / 引擎自检 / 播放指标）。 */
    val diagnostics: (@Composable ColumnScope.() -> Unit)? = null,
) {

    /**
     * 这次实际要渲染哪些分组（纯逻辑，单测钉住：**没有内容的组不出现**）。
     *
     * 连接与播放是页面内置内容（当前连接卡 / 后台与保活卡），所以恒在。
     */
    fun groupsWithContent(): Set<SettingsGroup> = buildSet {
        add(SettingsGroup.CONNECTION)
        add(SettingsGroup.PLAYBACK)
        if (network != null) add(SettingsGroup.NETWORK)
        if (update != null) add(SettingsGroup.UPDATE)
        if (security != null) add(SettingsGroup.SECURITY)
        if (diagnostics != null) add(SettingsGroup.DIAGNOSTICS)
    }

    companion object {

        /** 什么都没有的插槽组（首页/单测用）。 */
        val EMPTY = SettingsSections()
    }
}
