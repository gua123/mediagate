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
 * 两个参数都是可选的字符串查询参数，因此 `browser`、`browser?kind=VIDEO`、
 * `browser?path=Movies&kind=IMAGE` 都能命中同一目标。
 */
object BrowserRoutes {

    /** 目标基名（不带参数），底部导航栏跳这里。 */
    const val BASE = "browser"

    /** 浏览页路径参数（相对根目录的 POSIX 路径；缺省为根）。 */
    const val ARG_PATH = "path"

    /** 浏览页类型过滤参数（[MediaKind] 的 name；缺省不过滤）。 */
    const val ARG_KIND = "kind"

    /** NavHost 注册用的完整路由模板。 */
    const val ROUTE = "$BASE?$ARG_PATH={$ARG_PATH}&$ARG_KIND={$ARG_KIND}"

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
    )

    /**
     * 拼一个具体的目标路由。
     *
     * @param kind 类型过滤；null = 全部显示。
     * @param path 起始目录（相对根目录）；null 或空 = 根目录。
     */
    fun route(kind: MediaKind? = null, path: String? = null): String {
        val query = buildList {
            if (!path.isNullOrEmpty()) add("$ARG_PATH=" + encode(path))
            if (kind != null) add("$ARG_KIND=" + kind.name)
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

    /** 从导航参数里取类型过滤；无法识别（含 null）返回 null。 */
    fun kindOf(argument: String?): MediaKind? {
        if (argument.isNullOrEmpty()) return null
        return MediaKind.entries.firstOrNull { it.name == argument }
    }
}
