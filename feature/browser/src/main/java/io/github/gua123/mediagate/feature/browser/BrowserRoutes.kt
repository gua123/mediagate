package io.github.gua123.mediagate.feature.browser

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.gua123.mediagate.core.model.MediaKind
import java.net.URLEncoder

/**
 * 浏览页的导航契约（跨模块路由）。
 *
 * 路由常量放在 `:feature:browser` 自己这里（**不改 core 模块**），:app 用它注册 NavHost 目标，
 * `:feature:home` 的「视频 / 音乐 / 图片」入口卡片直接用它拼目标路由并带上类型过滤（M1）。
 *
 * 参数都是可选的字符串查询参数，因此 `browser`、`browser?kind=VIDEO`、
 * `browser?path=Movies&kind=IMAGE` 都能命中同一目标。
 *
 * **`startAtRoot`（2026-11-18）**：起点是"连接根目录"还是"上次那个目录"必须由**路由**说清楚。
 * 首页三个媒体入口与底部「浏览」页签都传 `startAtRoot=true`，否则两边都退回到
 * "记住上次浏览/播放的目录"的推断，打开后渲染成同一份列表（本次修复的根因之一）。
 */
object BrowserRoutes {

    /** 目标基名（不带参数），底部导航栏跳这里。 */
    const val BASE = "browser"

    /** 浏览页路径参数（相对根目录的 POSIX 路径；缺省为根）。 */
    const val ARG_PATH = "path"

    /** 浏览页类型过滤参数（[MediaKind] 的 name；缺省不过滤）。 */
    const val ARG_KIND = "kind"

    /**
     * 起点参数：`1` = **显式从连接根目录开始**（首页三个媒体入口 / 底部「浏览」页签）。
     *
     * 缺省（不出现）= 沿用"上次播放文件所在目录 / 上次浏览的目录"这条推断，
     * 给播放页返回、从首页「继续播放」跳进来这类"要回到原处"的来路用。
     */
    const val ARG_START_AT_ROOT = "startAtRoot"

    /** [ARG_START_AT_ROOT] 的开启取值（字符串布尔，只认 `1`）。 */
    private const val START_AT_ROOT_ON = "1"

    /** NavHost 注册用的完整路由模板。 */
    const val ROUTE = "$BASE?$ARG_PATH={$ARG_PATH}&$ARG_KIND={$ARG_KIND}&" +
        "$ARG_START_AT_ROOT={$ARG_START_AT_ROOT}"

    /** NavHost 注册用的参数声明（都可空 + 默认 null，即「可省略」）。 */
    val arguments: List<NamedNavArgument> = listOf(
        navArgument(ARG_PATH) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
        navArgument(ARG_KIND) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
        navArgument(ARG_START_AT_ROOT) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
    )

    /**
     * 拼一个具体的目标路由。
     *
     * @param kind 类型过滤；null = 全部显示。
     * @param path 起始目录（相对根目录）；null 或空 = 根目录。
     * @param startAtRoot **true = 固定从连接根目录开始**，忽略"上次浏览/播放的目录"推断
     *   （首页三个媒体入口、底部「浏览」页签用；其它带 [path] 的来路保持 false）。
     */
    fun route(
        kind: MediaKind? = null,
        path: String? = null,
        startAtRoot: Boolean = false,
    ): String {
        val query = buildList {
            if (!path.isNullOrEmpty()) add("$ARG_PATH=" + encode(path))
            if (kind != null) add("$ARG_KIND=" + kind.name)
            if (startAtRoot) add("$ARG_START_AT_ROOT=$START_AT_ROOT_ON")
        }
        return if (query.isEmpty()) BASE else "$BASE?" + query.joinToString("&")
    }

    /**
     * 查询参数百分号编码。
     *
     * 刻意不用 `android.net.Uri.encode`：那样这段逻辑在 JVM 单测里只能拿到桩返回值
     * （默认值 null），路由拼接就没法纯 JVM 验证。URLEncoder 把空格编成 `+`，
     * 查询串里要的是 `%20`，这里替换回来。
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** 从导航参数里取起始目录（null / 空串都归一成空串 = 根目录）。 */
    fun pathOf(argument: String?): String = argument.orEmpty()

    /** 从导航参数里取「是否固定从根目录开始」；只有 `1` 算 true（null / 空串 / 其它都算 false）。 */
    fun startAtRootOf(argument: String?): Boolean = argument == START_AT_ROOT_ON

    /** 从导航参数里取类型过滤；无法识别（含 null）返回 null。 */
    fun kindOf(argument: String?): MediaKind? {
        if (argument.isNullOrEmpty()) return null
        return MediaKind.entries.firstOrNull { it.name == argument }
    }

    /** 去参数的基名：`browser?path=a&kind=VIDEO` → `browser`；null / 空串 → null。 */
    fun baseNameOf(route: String?): String? =
        route?.substringBefore('?')?.takeIf { it.isNotEmpty() }

    /**
     * 这条 destination 路由是不是浏览页。
     *
     * **2026-11-21**：只比基名，**不解析路由模板**。旧实现把
     * `browser?path={path}&kind={kind}&startAtRoot={startAtRoot}` 整串丢给 `popBackStack(route)`，
     * 命中与否取决于导航库怎么把「模板」和「具体路由」做匹配 —— 参数名、参数顺序、编码方式任何一处变动
     * 都会在没人察觉的情况下改掉语义（返回键变成"再开一条浏览页"）。基名比对与参数无关：
     * 模板加参数、减参数，或者传进来的是 `browser?startAtRoot=1` 这种具体路由，都能认出来。
     */
    fun isBrowserDestination(route: String?): Boolean = baseNameOf(route) == BASE

    /** 「从播放页 / 看图页返回浏览页」该怎么做，见 [planReturnToBrowser]。 */
    sealed interface BrowserReturnPlan {
        /** 当前页之下确实压着浏览页 ⇒ 往上弹 [steps] 层回到它（它自己所在的目录保持不变）。 */
        data class PopToBrowser(val steps: Int) : BrowserReturnPlan

        /**
         * 当前页就是浏览页 ⇒ **什么都不做**。
         *
         * 旧实现这里会走 `navigate(browser)` 再压一条，栈变成 home→browser→browser：
         * 有了重复的浏览页，底部页签的 saveState/restoreState 就会把两条一起恢复回来。
         */
        data object AlreadyOnBrowser : BrowserReturnPlan

        /** 栈里压根没有浏览页（从首页「继续播放」这类来路进来）⇒ 新开一条。 */
        data object OpenBrowser : BrowserReturnPlan
    }

    /**
     * 由返回栈的路由快照（**自底向顶**）算出「回浏览页」的动作。
     *
     * 抽成纯函数是为了能进 JVM 单测：真正的 `NavHostController` 只在 Android 运行时里存在，
     * 而"到底该 pop 回栈里那条浏览页、还是原地不动、还是新开一条"恰恰只能靠这份决策钉住。
     *
     * @param backStackRoutes 每层 destination 的 `route`（模板或具体路由都行；NavGraph 那层是 null）。
     */
    fun planReturnToBrowser(backStackRoutes: List<String?>): BrowserReturnPlan {
        val lastIndex = backStackRoutes.lastIndex
        if (lastIndex < 0) return BrowserReturnPlan.OpenBrowser
        val browserIndex = backStackRoutes.indexOfLast { isBrowserDestination(it) }
        return when {
            browserIndex < 0 -> BrowserReturnPlan.OpenBrowser
            browserIndex == lastIndex -> BrowserReturnPlan.AlreadyOnBrowser
            else -> BrowserReturnPlan.PopToBrowser(lastIndex - browserIndex)
        }
    }

    /**
     * 底部导航切换时给 NavOptions 的 saveState / restoreState 组合。
     *
     * 抽成数据类 + 纯函数是为了能进 JVM 单测：真正的 `NavHostController` 在 Android 运行时里，
     * 单测没法构造，而"页签到底复不复用返回栈"恰恰只能靠这份决策来钉住。
     */
    data class TopLevelNavFlags(val saveState: Boolean, val restoreState: Boolean)

    /**
     * 计算页签切换的返回栈策略。
     *
     * @param isStartDestination 目标是 NavHost 的**起始页**（首页）—— 它永远躺在栈底，没有任何"自己的返回栈"。
     * @param reuseState true = 切走再切回保留该页自己的返回栈与 ViewModel 状态（连接 / 设置）；
     *   false = **每次都从干净的一页开始**（「浏览」页签）。
     *
     * **2026-11-18 修复**：`restoreState = true` 会把上次 entry 连同它的 ViewModel 一起恢复，
     * 于是首页三个媒体入口点进来看到的是"上次那个文件夹 + 上次那个过滤"，和页签渲染同一份列表。
     *
     * **2026-11-21 修复（真机：从播放页返回后点「首页」没反应）**：起始页签**绝对不能带 restoreState**。
     * Navigation 的 `popUpTo(id) { saveState = true }` 会把**被保留下来的那个 destination**（这里是起始页 home）
     * 也写进 `backStackMap`，映射到"刚被弹掉的那一堆"（浏览页 + 它上面的东西）。于是下一次
     * `navigate("home") { restoreState = true }` 恢复的不是首页自己的状态，而是**刚弹掉的那条浏览页** ——
     * 首页被顶掉、界面还停在浏览页，看起来就是"点了没反应"；因为每次点击都重新存一次、再恢复一次，
     * 这个环会一直转下去，只有重启进程（`backStackMap` 清空）才恢复。
     * 首页在栈底，本来就没有"切走再切回"可言，`restoreState = false` 是它的正确取值。
     *
     * `saveState` 保持 true 不动，哪怕目标是浏览页：`popUpTo { saveState }` 保存的是**被切走的那一页**
     * （切去浏览页时可能是连接页/设置页），关掉它会让连接/设置"切走再切回"丢状态 —— 那是顺手修不能
     * 付的代价。浏览页自己的那份存了也用不上（`restoreState = false` 不会去取），代价只是栈里一点死数据。
     */
    fun topLevelNavFlags(isStartDestination: Boolean, reuseState: Boolean): TopLevelNavFlags =
        TopLevelNavFlags(
            saveState = true,
            // 起始页（栈底）没有自己的返回栈：给它 restoreState 只会把"刚才压在它上面的那一页"恢复回来。
            restoreState = reuseState && !isStartDestination,
        )
}
