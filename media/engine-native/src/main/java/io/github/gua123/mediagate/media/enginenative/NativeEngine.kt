package io.github.gua123.mediagate.media.enginenative

import io.github.gua123.mediagate.core.common.AppLog

/**
 * 自研播放引擎的 Kotlin 侧入口（**2026-10-06 用户拍板：只采用一套引擎，不要来回切换**）。
 *
 * 这里只做三件事：加载 .so、暴露 native 方法、把失败变成可诊断的返回值。
 * **播放逻辑一概不写在 Kotlin 里**；Kotlin 只负责策略（用哪个连接、什么权限、怎么显示）。
 */
object NativeEngine {

    /** 必须与 CMake 里的 add_library 名字一致（有单测钉住）。 */
    const val LIBRARY_NAME: String = "mediagate_engine"

    private const val TAG = "NativeEngine"

    /** 库加载失败的原因（null＝加载成功）；设置页 / 诊断卡可以显示它。 */
    @Volatile
    var loadFailure: String? = null
        private set

    /** 是否可用：加载成功才算可用（**不抛异常**——引擎不可用时界面要给中文说明，而不是崩）。 */
    val isAvailable: Boolean
        get() = loadFailure == null

    init {
        loadFailure = try {
            System.loadLibrary(LIBRARY_NAME)
            null
        } catch (t: Throwable) {
            AppLog.w(TAG, "自研引擎 native 库加载失败：$LIBRARY_NAME", t)
            t.message ?: t.javaClass.simpleName
        }
    }

    /** 引擎名（自检用）。 */
    fun name(): String? = safe { nativeName() }

    /** 内核版本（自检用）。 */
    fun version(): String? = safe { nativeVersion() }

    /** 内部解码路径说明，例如 "mediacodec(hw) -> ffmpeg(sw)"。 */
    fun decodePaths(): String? = safe { nativeDecodePaths() }

    /** 给设置页/诊断卡的一行自述。 */
    fun describe(): String {
        val failure = loadFailure
        if (failure != null) return "自研引擎不可用（native 库加载失败：$failure）"
        val n = name() ?: return "自研引擎不可用（native 调用失败）"
        return "$n ${version() ?: "?"}（内部解码路径：${decodePaths() ?: "?"}）"
    }

    private inline fun safe(block: () -> String): String? = try {
        block()
    } catch (t: Throwable) {
        AppLog.w(TAG, "自研引擎 native 调用失败", t)
        null
    }

    private external fun nativeName(): String

    private external fun nativeVersion(): String

    private external fun nativeDecodePaths(): String
}
