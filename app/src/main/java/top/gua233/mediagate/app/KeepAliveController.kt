package io.github.gua123.mediagate.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.feature.settings.KeepAliveGuide
import io.github.gua123.mediagate.feature.settings.KeepAliveGuideRules
import io.github.gua123.mediagate.feature.settings.KeepAliveHost
import io.github.gua123.mediagate.feature.settings.KeepAliveInput
import io.github.gua123.mediagate.feature.settings.KeepAliveItemKind

/** 能查到的两项保活状态（R18）：通知权限、省电策略。 */
private data class KeepAliveRuntime(
    val notificationsGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

/**
 * 后台与保活引导的宿主实现（**R18**，澎湃 OS）：查得到的现查、查不到的记用户确认。
 *
 * - **查得到**：通知权限（[NotificationManagerCompat.areNotificationsEnabled]，含用户在系统里手动关掉
 *   通知的情况）与省电策略（[PowerManager.isIgnoringBatteryOptimizations]）——每次进设置页、每次从
 *   系统设置页返回都重查一遍（[refresh]）；
 * - **查不到**：自启动 / 后台弹出界面在厂商设置里没有公开 API，只能把用户勾的"我已开启"落盘
 *   （[KeepAliveSettings]），并明确显示「需手动确认」，不假装已就绪；
 * - **跳转**：申请忽略电池优化（[requestBatteryUnrestricted]）、打开应用详情页（[openAppDetails]）、
 *   打开通知设置页（[openNotificationSettings]）。全都带 fallback，绝不因为 ROM 少一个 Activity 就崩。
 *
 * 纯逻辑（状态 → 引导项与文案 → 是否全部就绪）在 :feature:settings 的 KeepAliveGuideRules 里，
 * 有 JVM 单测覆盖；本类只负责"取真实状态 + 跳真实页面"。
 */
class KeepAliveController(context: Context) : KeepAliveHost {

    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings = KeepAliveSettings(appContext)

    private val runtime = MutableStateFlow(readRuntime())

    /** 系统状态（每次 [refresh] 重查）与用户确认（DataStore）合成的引导。 */
    override val guide: StateFlow<KeepAliveGuide> =
        combine(runtime, settings.confirmed) { state, confirmed ->
            KeepAliveGuideRules.evaluate(
                KeepAliveInput(
                    notificationsGranted = state.notificationsGranted,
                    batteryUnrestricted = state.batteryUnrestricted,
                    autoStartConfirmed = KeepAliveItemKind.AUTO_START in confirmed,
                    backgroundPopupConfirmed = KeepAliveItemKind.BACKGROUND_POPUP in confirmed,
                ),
            )
        }.stateIn(scope, SharingStarted.Eagerly, KeepAliveGuideRules.evaluate(KeepAliveInput()))

    /** 重新查一次系统状态（进设置页 / 从系统设置页返回时调用）。 */
    override fun refresh() {
        runtime.value = readRuntime()
    }

    /** 记录"我已确认"（只有自启动、后台弹出界面两项会落盘，其余项在纯逻辑里被忽略）。 */
    override fun setConfirmed(kind: KeepAliveItemKind, confirmed: Boolean) {
        scope.launch {
            runCatching { settings.setConfirmed(kind, confirmed) }
                .onFailure { AppLog.w(TAG, "写入保活确认失败：" + kind.name, it) }
        }
        if (kind == KeepAliveItemKind.AUTO_START || kind == KeepAliveItemKind.BACKGROUND_POPUP) {
            // 确认项与系统状态无关，但刷新一次能让界面立刻反映（DataStore 写入本身也会触发）
            refresh()
        }
    }

    // ------------------------------------------------------------ 跳转（全部带 fallback）

    /**
     * 申请「省电策略：无限制」（R18）。
     *
     * 首选 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（系统弹窗，一步到位，需要清单里的
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）；个别 ROM 没有这个页面，退化成电池优化列表页。
     */
    fun requestBatteryUnrestricted() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            AppLog.w(TAG, "本机没有「忽略电池优化」弹窗页，改用电池优化列表", e)
            startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 打开本应用的系统详情页（自启动 / 后台弹出界面这类厂商开关的入口）。 */
    fun openAppDetails() {
        startSafely(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", appContext.packageName, null),
            ),
        )
    }

    /** 打开本应用的通知设置页（通知权限被"拒绝且不再询问"时的兜底入口）。 */
    fun openNotificationSettings() {
        startSafely(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName),
        )
    }

    // ------------------------------------------------------------ 内部

    /** 现查一次可查的两项。 */
    private fun readRuntime(): KeepAliveRuntime = KeepAliveRuntime(
        notificationsGranted = runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(false),
        batteryUnrestricted = runCatching {
            appContext.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        }.getOrDefault(false),
    )

    /** 起一个系统页；失败只记日志（设置页不该因为 ROM 缺页面而崩）。 */
    private fun startSafely(intent: Intent) {
        val withFlags = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(withFlags) }
            .onFailure { AppLog.w(TAG, "打开系统设置页失败：" + withFlags.action, it) }
    }

    private companion object {
        const val TAG = "keep-alive"
    }
}
