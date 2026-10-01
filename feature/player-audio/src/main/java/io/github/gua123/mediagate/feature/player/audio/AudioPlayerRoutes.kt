package io.github.gua123.mediagate.feature.player.audio

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

/**
 * 音频播放页的导航契约（R1 音频 / R18）。
 *
 * 路由常量放在 :feature:player-audio 自己这里（不改 core 模块），:app 用它注册 NavHost 目标，
 * 浏览页点到音频文件时也直接用它拼目标路由。
 *
 * 只带一个路径参数：**队列由播放页自己按「同目录音频」解析**（:app 的
 * [AudioPlayerEnvironment.audioSiblings]，与浏览页同一后端、同一排序），
 * 这样路由不会被一长串文件名撑爆，也不会出现「路由里的列表和目录现状不一致」。
 */
object AudioPlayerRoutes {

    /** 目标基名（不带参数）。 */
    const val BASE = "player-audio"

    /** 音频路径参数（相对根目录的 POSIX 路径）。 */
    const val ARG_PATH = "path"

    /** NavHost 注册用的完整路由模板。 */
    const val ROUTE = "$BASE?$ARG_PATH={$ARG_PATH}"

    /** NavHost 注册用的参数声明（可空 + 默认 null，即「可省略」）。 */
    val arguments: List<NamedNavArgument> = listOf(
        navArgument(ARG_PATH) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
    )

    /** 拼一个具体的目标路由；[path] 为空时退化成不带参数的基名。 */
    fun route(path: String): String = if (path.isEmpty()) BASE else "$BASE?$ARG_PATH=" + encode(path)

    /**
     * 查询参数百分号编码。
     *
     * 与 :feature:browser / :feature:viewer-image 同口径：刻意不用 android.net.Uri.encode
     * （JVM 单测里只会拿到桩返回值），URLEncoder 把空格编成 +，查询串里要的是 %20。
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** 从导航参数里取路径（null / 空串都归一成空串）。 */
    fun pathOf(argument: String?): String = argument.orEmpty()
}
