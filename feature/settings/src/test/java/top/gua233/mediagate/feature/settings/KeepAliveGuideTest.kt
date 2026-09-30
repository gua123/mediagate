package io.github.gua123.mediagate.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活引导纯规则的单测（**R18**，澎湃 OS）：权限/确认状态 → 引导项与文案 → 是否全部就绪。
 *
 * 这些用例覆盖的是"界面会显示什么、能点什么"——真正的权限查询与系统页跳转在 :app 的
 * KeepAliveHost 里（真机才能验证），这里保证判定逻辑本身没有含糊地带。
 */
class KeepAliveGuideTest {

    private fun guide(
        notifications: Boolean = false,
        battery: Boolean = false,
        autoStart: Boolean = false,
        backgroundPopup: Boolean = false,
    ): KeepAliveGuide = KeepAliveGuideRules.evaluate(
        KeepAliveInput(
            notificationsGranted = notifications,
            batteryUnrestricted = battery,
            autoStartConfirmed = autoStart,
            backgroundPopupConfirmed = backgroundPopup,
        ),
    )

    @Test
    fun nothingGrantedShowsTwoActionableAndTwoManualItems() {
        val result = guide()

        assertEquals(KeepAliveStatus.ACTION_NEEDED, result.item(KeepAliveItemKind.NOTIFICATION)!!.status)
        assertEquals(KeepAliveStatus.ACTION_NEEDED, result.item(KeepAliveItemKind.BATTERY_UNRESTRICTED)!!.status)
        assertEquals(KeepAliveStatus.MANUAL_CHECK, result.item(KeepAliveItemKind.AUTO_START)!!.status)
        assertEquals(KeepAliveStatus.MANUAL_CHECK, result.item(KeepAliveItemKind.BACKGROUND_POPUP)!!.status)
        assertFalse(result.allReady)
        assertEquals(0, result.readyCount)
        assertEquals(4, result.pendingCount)
    }

    @Test
    fun everythingGrantedAndConfirmedMeansAllReady() {
        val result = guide(notifications = true, battery = true, autoStart = true, backgroundPopup = true)

        assertTrue(result.allReady)
        assertEquals(4, result.readyCount)
        assertEquals(0, result.pendingCount)
        assertEquals(4, result.totalCount)
    }

    @Test
    fun itemsKeepTheFixedOrder() {
        val kinds = guide().items.map { it.kind }

        assertEquals(
            listOf(
                KeepAliveItemKind.NOTIFICATION,
                KeepAliveItemKind.BATTERY_UNRESTRICTED,
                KeepAliveItemKind.AUTO_START,
                KeepAliveItemKind.BACKGROUND_POPUP,
            ),
            kinds,
        )
    }

    @Test
    fun notificationItemJumpsToRuntimePermissionRequest() {
        val item = guide().item(KeepAliveItemKind.NOTIFICATION)!!

        assertEquals(KeepAliveAction.REQUEST_NOTIFICATION, item.jump!!.action)
        assertFalse("通知权限查得到，不需要用户手动确认", item.confirmable)
        assertFalse(item.confirmed)
    }

    @Test
    fun grantedNotificationBecomesReady() {
        val item = guide(notifications = true).item(KeepAliveItemKind.NOTIFICATION)!!

        assertEquals(KeepAliveStatus.READY, item.status)
    }

    @Test
    fun batteryItemJumpsToBatteryOptimizationSettings() {
        assertEquals(
            KeepAliveAction.REQUEST_BATTERY_UNRESTRICTED,
            guide().item(KeepAliveItemKind.BATTERY_UNRESTRICTED)!!.jump!!.action,
        )
        assertEquals(
            KeepAliveStatus.READY,
            guide(battery = true).item(KeepAliveItemKind.BATTERY_UNRESTRICTED)!!.status,
        )
    }

    @Test
    fun manualItemsOfferAppDetailsJumpAndConfirmation() {
        val autoStart = guide().item(KeepAliveItemKind.AUTO_START)!!
        val popup = guide().item(KeepAliveItemKind.BACKGROUND_POPUP)!!

        assertEquals(KeepAliveAction.OPEN_APP_DETAILS, autoStart.jump!!.action)
        assertEquals(KeepAliveAction.OPEN_APP_DETAILS, popup.jump!!.action)
        assertTrue("厂商设置查不到，必须让用户确认", autoStart.confirmable)
        assertTrue(popup.confirmable)
    }

    @Test
    fun confirmingAutoStartTurnsItReady() {
        val confirmed = KeepAliveGuideRules.confirm(guide(), KeepAliveItemKind.AUTO_START, confirmed = true)
        val item = confirmed.item(KeepAliveItemKind.AUTO_START)!!

        assertEquals(KeepAliveStatus.READY, item.status)
        assertTrue(item.confirmed)
        assertEquals(1, confirmed.readyCount)
    }

    @Test
    fun unconfirmingGoesBackToManualCheck() {
        val confirmed = KeepAliveGuideRules.confirm(
            guide(autoStart = true),
            KeepAliveItemKind.AUTO_START,
            confirmed = false,
        )

        assertEquals(KeepAliveStatus.MANUAL_CHECK, confirmed.item(KeepAliveItemKind.AUTO_START)!!.status)
        assertFalse(confirmed.item(KeepAliveItemKind.AUTO_START)!!.confirmed)
    }

    @Test
    fun confirmingOnlyTouchesTheTargetItem() {
        val before = guide()
        val after = KeepAliveGuideRules.confirm(before, KeepAliveItemKind.BACKGROUND_POPUP, confirmed = true)

        assertEquals(before.item(KeepAliveItemKind.AUTO_START), after.item(KeepAliveItemKind.AUTO_START))
        assertEquals(before.item(KeepAliveItemKind.NOTIFICATION), after.item(KeepAliveItemKind.NOTIFICATION))
        assertTrue(after.item(KeepAliveItemKind.BACKGROUND_POPUP)!!.confirmed)
    }

    @Test
    fun confirmingASystemCheckedItemIsIgnored() {
        // 通知权限是系统查得到的：用户点"我已开启"不能改写事实
        val before = guide()
        val after = KeepAliveGuideRules.confirm(before, KeepAliveItemKind.NOTIFICATION, confirmed = true)

        assertSame(before, after)
        assertEquals(KeepAliveStatus.ACTION_NEEDED, after.item(KeepAliveItemKind.NOTIFICATION)!!.status)
    }

    @Test
    fun confirmingAnUnknownItemKeepsTheGuide() {
        val before = guide()
        val after = KeepAliveGuideRules.confirm(before, KeepAliveItemKind.AUTO_START, confirmed = true)

        assertEquals(4, after.totalCount)
        assertNull(KeepAliveGuide(items = emptyList()).item(KeepAliveItemKind.AUTO_START))
        assertFalse(before.allReady)
    }

    @Test
    fun partialProgressIsCountedCorrectly() {
        val result = guide(notifications = true, autoStart = true)

        assertEquals(2, result.readyCount)
        assertEquals(2, result.pendingCount)
        assertFalse(result.allReady)
    }

    @Test
    fun emptyGuideIsNotAllReady() {
        val empty = KeepAliveGuide()

        assertFalse("没有项就不该说'全部就绪'", empty.allReady)
        assertEquals(0, empty.totalCount)
        assertEquals(0, empty.readyCount)
    }

    @Test
    fun everyItemCarriesChineseCopyResources() {
        guide().items.forEach { item ->
            assertNotNull("每项都要有标题与说明资源", item.titleRes)
            assertNotNull(item.detailRes)
            assertNotNull("四项都有可跳转的系统入口", item.jump)
        }
    }

    @Test
    fun manualConfirmationSurvivesReevaluation() {
        // :app 把"用户确认"落盘后再喂回来：状态必须还是 READY（不是又回到需手动确认）
        val input = KeepAliveInput(
            notificationsGranted = true,
            batteryUnrestricted = true,
            autoStartConfirmed = true,
            backgroundPopupConfirmed = true,
        )

        assertTrue(KeepAliveGuideRules.evaluate(input).allReady)
    }
}
