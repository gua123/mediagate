package io.github.gua123.mediagate.feature.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * 保活引导项（**R18**：澎湃 OS 首次运行引导）。
 *
 * 四项固定顺序：通知权限 → 省电策略 → 自启动 → 后台弹出界面。
 */
enum class KeepAliveItemKind {

    /** 通知权限（POST_NOTIFICATIONS）：后台播放/字幕生成的通知栏与锁屏控制需要它。 */
    NOTIFICATION,

    /** 省电策略「无限制」：澎湃 OS 息屏后限制后台应用，这一项决定能不能长时间后台播放。 */
    BATTERY_UNRESTRICTED,

    /** 自启动（厂商设置，无公开查询接口 → 需手动确认）。 */
    AUTO_START,

    /** 后台弹出界面（厂商设置，无公开查询接口 → 需手动确认）。 */
    BACKGROUND_POPUP,
}

/**
 * 一项的就绪状态。
 *
 * [MANUAL_CHECK] 与 [ACTION_NEEDED] 的区别很重要：前者是**我们查不到**（厂商设置页没有 API），
 * 只能请用户自己去确认；后者是**查得到但还没开**，界面可以给一键跳转。
 */
enum class KeepAliveStatus {

    /** 已就绪。 */
    READY,

    /** 未就绪，但有一键跳转的系统页。 */
    ACTION_NEEDED,

    /** 系统查不到，需要用户手动确认（确认后算 READY）。 */
    MANUAL_CHECK,
}

/** 保活引导里能点的动作；具体怎么跳由 :app 实现（本模块不碰 Intent）。 */
enum class KeepAliveAction {

    /** 申请通知权限（系统运行时权限弹窗）。 */
    REQUEST_NOTIFICATION,

    /** 跳「省电策略 / 电池优化」设置页（申请「无限制」）。 */
    REQUEST_BATTERY_UNRESTRICTED,

    /** 打开本应用的系统详情页（自启动 / 后台弹出界面这类厂商开关的入口）。 */
    OPEN_APP_DETAILS,
}

/**
 * 一个可以一键跳转的系统入口。
 *
 * @property action 动作（:app 负责映射成 Intent）。
 * @property labelRes 按钮文案（中文资源，R16）。
 */
data class KeepAliveJump(
    val action: KeepAliveAction,
    val labelRes: Int,
)

/**
 * 一条保活引导项。
 *
 * 文案（[titleRes] / [detailRes] / [jump]）由**纯逻辑**选定，界面只负责按资源 id 显示——
 * 这样"哪一项该显示什么、能不能点"可以在 JVM 单测里逐条覆盖（R16 又保证文案都来自资源文件）。
 *
 * @property kind 项目种类。
 * @property status 当前状态（含"需手动确认"）。
 * @property titleRes 标题资源。
 * @property detailRes 说明资源（会写清"为什么要开"与"在厂商设置哪里开"）。
 * @property jump 一键跳转入口；null = 这项没有可直达的系统页。
 * @property confirmable 是否需要用户手动确认（厂商设置页查不到状态）。
 * @property confirmed 用户是否已经确认过（仅 [confirmable] 项有意义）。
 */
data class KeepAliveItem(
    val kind: KeepAliveItemKind,
    val status: KeepAliveStatus,
    val titleRes: Int,
    val detailRes: Int,
    val jump: KeepAliveJump?,
    val confirmable: Boolean = false,
    val confirmed: Boolean = false,
)

/**
 * 保活引导全貌（R18）。
 *
 * @property items 按固定顺序排列的四项。
 */
data class KeepAliveGuide(
    val items: List<KeepAliveItem> = emptyList(),
) {

    /** 是否全部就绪（含手动确认过的两项）。 */
    val allReady: Boolean get() = items.isNotEmpty() && items.all { it.status == KeepAliveStatus.READY }

    /** 已就绪项数。 */
    val readyCount: Int get() = items.count { it.status == KeepAliveStatus.READY }

    /** 总项数。 */
    val totalCount: Int get() = items.size

    /** 还差几项（未开启 + 待手动确认）。 */
    val pendingCount: Int get() = totalCount - readyCount

    /** 取某一项；不存在返回 null。 */
    fun item(kind: KeepAliveItemKind): KeepAliveItem? = items.firstOrNull { it.kind == kind }
}

/**
 * 判定保活引导的输入（R18）。
 *
 * @property notificationsGranted 通知权限是否已授予（可查）。
 * @property batteryUnrestricted 省电策略是否已「无限制」（可查：isIgnoringBatteryOptimizations）。
 * @property autoStartConfirmed 用户是否确认过已开启自启动（查不到，只能记用户确认）。
 * @property backgroundPopupConfirmed 用户是否确认过已允许后台弹出界面。
 */
data class KeepAliveInput(
    val notificationsGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val autoStartConfirmed: Boolean = false,
    val backgroundPopupConfirmed: Boolean = false,
)

/**
 * 保活引导的纯规则（**R18**）：权限/确认状态 → 引导项与文案 → 是否全部就绪。
 *
 * 全部是纯函数：不碰 Context、不查权限、不跳转（那些是 :app 的 KeepAliveHost 干的），
 * 因此每一项的状态与文案都能在 JVM 单测里断言。
 */
object KeepAliveGuideRules {

    /**
     * 生成引导项（顺序固定：通知 → 省电策略 → 自启动 → 后台弹出界面）。
     *
     * 口径说明：
     * - 通知权限**没开也给 ACTION_NEEDED**（可以一键申请）；已开就是 READY；
     * - 省电策略同理（系统提供"申请忽略电池优化"的弹窗）；
     * - 自启动 / 后台弹出界面**没有可查的 API**：没确认过一律 [KeepAliveStatus.MANUAL_CHECK]，
     *   用户确认后变 READY——不假装"已就绪"，也不假装"未开启"。
     */
    fun evaluate(input: KeepAliveInput): KeepAliveGuide = KeepAliveGuide(
        items = listOf(
            notificationItem(input.notificationsGranted),
            batteryItem(input.batteryUnrestricted),
            autoStartItem(input.autoStartConfirmed),
            backgroundPopupItem(input.backgroundPopupConfirmed),
        ),
    )

    /**
     * 手动确认（自启动 / 后台弹出界面）。
     *
     * 非 [KeepAliveItemKind.AUTO_START] / [KeepAliveItemKind.BACKGROUND_POPUP] 的项（系统查得到的）
     * 一律原样返回——它们的就绪状态只认系统查询结果，用户点了"我已开启"也不该改写事实。
     */
    fun confirm(guide: KeepAliveGuide, kind: KeepAliveItemKind, confirmed: Boolean): KeepAliveGuide {
        val target = guide.item(kind) ?: return guide
        if (!target.confirmable) return guide
        val updated = target.copy(
            confirmed = confirmed,
            status = if (confirmed) KeepAliveStatus.READY else KeepAliveStatus.MANUAL_CHECK,
        )
        return guide.copy(items = guide.items.map { if (it.kind == kind) updated else it })
    }

    private fun notificationItem(granted: Boolean): KeepAliveItem = KeepAliveItem(
        kind = KeepAliveItemKind.NOTIFICATION,
        status = if (granted) KeepAliveStatus.READY else KeepAliveStatus.ACTION_NEEDED,
        titleRes = R.string.keep_alive_notification_title,
        detailRes = R.string.keep_alive_notification_detail,
        jump = KeepAliveJump(KeepAliveAction.REQUEST_NOTIFICATION, R.string.keep_alive_notification_action),
    )

    private fun batteryItem(unrestricted: Boolean): KeepAliveItem = KeepAliveItem(
        kind = KeepAliveItemKind.BATTERY_UNRESTRICTED,
        status = if (unrestricted) KeepAliveStatus.READY else KeepAliveStatus.ACTION_NEEDED,
        titleRes = R.string.keep_alive_battery_title,
        detailRes = R.string.keep_alive_battery_detail,
        jump = KeepAliveJump(KeepAliveAction.REQUEST_BATTERY_UNRESTRICTED, R.string.keep_alive_battery_action),
    )

    private fun autoStartItem(confirmed: Boolean): KeepAliveItem = KeepAliveItem(
        kind = KeepAliveItemKind.AUTO_START,
        status = if (confirmed) KeepAliveStatus.READY else KeepAliveStatus.MANUAL_CHECK,
        titleRes = R.string.keep_alive_autostart_title,
        detailRes = R.string.keep_alive_autostart_detail,
        jump = KeepAliveJump(KeepAliveAction.OPEN_APP_DETAILS, R.string.keep_alive_open_details),
        confirmable = true,
        confirmed = confirmed,
    )

    private fun backgroundPopupItem(confirmed: Boolean): KeepAliveItem = KeepAliveItem(
        kind = KeepAliveItemKind.BACKGROUND_POPUP,
        status = if (confirmed) KeepAliveStatus.READY else KeepAliveStatus.MANUAL_CHECK,
        titleRes = R.string.keep_alive_background_popup_title,
        detailRes = R.string.keep_alive_background_popup_detail,
        jump = KeepAliveJump(KeepAliveAction.OPEN_APP_DETAILS, R.string.keep_alive_open_details),
        confirmable = true,
        confirmed = confirmed,
    )
}

/**
 * 保活引导的宿主能力（**R18**）——由 :app 实现并注入。
 *
 * :feature:settings 只显示 [guide] 并把用户点击转成 [KeepAliveAction]；
 * 查权限、跳系统设置页、把"手动确认"落盘都是 :app 的事（本模块不碰 Context / Intent）。
 */
interface KeepAliveHost {

    /** 当前引导（权限状态 + 用户确认，:app 合出来）。 */
    val guide: StateFlow<KeepAliveGuide>

    /** 重新查一次系统状态（从系统设置页返回时调用）。 */
    fun refresh()

    /** 记录/取消"我已确认"（仅自启动、后台弹出界面两项有意义）。 */
    fun setConfirmed(kind: KeepAliveItemKind, confirmed: Boolean)
}
