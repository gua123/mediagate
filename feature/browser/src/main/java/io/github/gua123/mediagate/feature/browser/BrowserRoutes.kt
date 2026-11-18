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
     * @param reuseState true = 切走再切回保留该页自己的返回栈与 ViewModel 状态（HOME / CONNECTIONS / SETTINGS）；
     *   false = **每次都从干净的一页开始**（「浏览」页签）。
     *
     * **2026-11-18 修复**：`restoreState = true` 会把上次 entry 连同它的 ViewModel 一起恢复，
     * 于是首页三个媒体入口点进来看到的是"上次那个文件夹 + 上次那个过滤"，和页签渲染同一份列表。
     *
     * `saveState` 保持 true 不动，哪怕目标是浏览页：`popUpTo { saveState }` 保存的是**被切走的那一页**
     * （切去浏览页时可能是连接页/设置页），关掉它会让另外三个页签"切走再切回"丢状态 —— 那是顺手修不能
     * 付的代价。浏览页自己的那份存了也用不上（`restoreState = false` 不会去取），代价只是栈里一点死数据。
     */
    fun topLevelNavFlags(reuseState: Boolean): TopLevelNavFlags =
        TopLevelNavFlags(saveState = true, restoreState = reuseState)
}
