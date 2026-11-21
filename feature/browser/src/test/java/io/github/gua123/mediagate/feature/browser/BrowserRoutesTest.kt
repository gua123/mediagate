package io.github.gua123.mediagate.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航契约单测：路由识别 + 「从播放页返回浏览页」的决策。
 *
 * 为什么只能是纯函数：真正的 `NavHostController` 只在 Android 运行时里存在，JVM 单测构造不出来。
 * 而 2026-11-21 那个真机缺陷（从播放页返回后点「首页」没反应、重启才恢复）恰恰是两个"看不见的决策"
 * 叠在一起：① 页签切换给起始页也带了 restoreState；② 返回浏览页靠路由模板串匹配、并拿 popBackStack
 * 的返回值当"有没有命中"。这两条现在都收敛成这里可测的纯逻辑。
 */
class BrowserRoutesTest {

    // ------------------------------------------------------------ 路由识别

    @Test
    fun `基名去掉查询参数_没有参数就是自己`() {
        assertEquals("browser", BrowserRoutes.baseNameOf("browser"))
        assertEquals("browser", BrowserRoutes.baseNameOf("browser?path=a%2Fb&kind=VIDEO"))
        assertEquals("player-video", BrowserRoutes.baseNameOf("player-video?path={path}"))
        assertEquals(null, BrowserRoutes.baseNameOf(null))
        assertEquals("空串不算一个能认出来的路由", null, BrowserRoutes.baseNameOf(""))
    }

    @Test
    fun `认浏览页只看基名_不依赖路由模板串`() {
        // 模板本身（NavHost 注册的那个）当然要认出来
        assertTrue(BrowserRoutes.isBrowserDestination(BrowserRoutes.ROUTE))
        // 关键：具体路由 / 参数数量变了、顺序变了，都不影响认页 —— 旧实现是把模板整串丢给
        // popBackStack(route) 去和 entry 做匹配，语义绑在库的匹配细节上。
        assertTrue(BrowserRoutes.isBrowserDestination("browser"))
        assertTrue(BrowserRoutes.isBrowserDestination("browser?startAtRoot=1"))
        assertTrue(BrowserRoutes.isBrowserDestination("browser?path=Movies%2F2026&kind=VIDEO&startAtRoot=1"))
        assertTrue(BrowserRoutes.isBrowserDestination("browser?kind=IMAGE"))
        // 别的页不能被误认成浏览页（前四个字符像也不行）
        assertFalse(BrowserRoutes.isBrowserDestination("browserx?path=a"))
        assertFalse(BrowserRoutes.isBrowserDestination("player-video?path={path}"))
        assertFalse(BrowserRoutes.isBrowserDestination("home"))
        assertFalse(BrowserRoutes.isBrowserDestination(null))
    }

    // ------------------------------------------------------------ 返回浏览页的决策

    @Test
    fun `返回浏览页_栈里压着浏览页就弹回去`() {
        val plan = BrowserRoutes.planReturnToBrowser(
            listOf("home", BrowserRoutes.ROUTE, "player-video?path={path}"),
        )
        assertEquals(BrowserRoutes.BrowserReturnPlan.PopToBrowser(1), plan)
    }

    @Test
    fun `返回浏览页_隔着两层也一路弹到浏览页`() {
        val plan = BrowserRoutes.planReturnToBrowser(
            listOf("home", "browser?startAtRoot=1", "viewer?path=a.jpg", "player-video?path={path}"),
        )
        assertEquals("弹到浏览页为止，别少弹也别把浏览页一起弹掉", BrowserRoutes.BrowserReturnPlan.PopToBrowser(2), plan)
    }

    @Test
    fun `返回浏览页_栈里没有浏览页就新开一条`() {
        // 首页「继续播放」直接进播放页：栈里没有浏览页，返回时要开一条（用户 2026-10-03 的要求：
        // 回到上次浏览的目录，而不是回首页）
        val plan = BrowserRoutes.planReturnToBrowser(listOf("home", "player-video?path={path}"))
        assertEquals(BrowserRoutes.BrowserReturnPlan.OpenBrowser, plan)
    }

    @Test
    fun `返回浏览页_已经在浏览页时绝不再叠一条`() {
        // 2026-11-21：旧实现 popBackStack 返回 false（目标已在栈顶也算 false）时会走 navigate，
        // 栈变成 home→browser→browser；页签的 saveState/restoreState 再把两条一起恢复回来。
        val plan = BrowserRoutes.planReturnToBrowser(listOf("home", "browser?startAtRoot=1"))
        assertEquals(BrowserRoutes.BrowserReturnPlan.AlreadyOnBrowser, plan)
    }

    @Test
    fun `返回浏览页_空栈按新开一条处理_不抛异常`() {
        assertEquals(BrowserRoutes.BrowserReturnPlan.OpenBrowser, BrowserRoutes.planReturnToBrowser(emptyList()))
        assertEquals(
            "只剩当前页且它不是浏览页（NavGraph 那层 route 是 null）",
            BrowserRoutes.BrowserReturnPlan.OpenBrowser,
            BrowserRoutes.planReturnToBrowser(listOf(null, "player-video?path={path}")),
        )
    }
}
