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
        return "$n ${version() ?: "?"}（FFmpeg ${ffmpegVersion() ?: "?"}；内部解码路径：${decodePaths() ?: "?"}）"
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
    // ---- 渲染目标（M2：Kotlin 的 Surface → native 直接画） ----

    /**
     * 绑定渲染目标；传 null 解绑。
     *
     * 传的是 **Surface 句柄**（不是像素），之后所有帧都在 native 侧直接写——
     * 这是"JNI 边界要粗"的具体体现。
     */
    fun setSurface(surface: android.view.Surface?) {
        if (!isAvailable) return
        try {
            nativeSetSurface(surface)
        } catch (t: Throwable) {
            AppLog.w(TAG, "绑定 Surface 失败", t)
        }
    }

    /** 当前是否已绑定可画的 Surface。 */
    fun hasSurface(): Boolean = safeBool { nativeHasSurface() }

    /** 画一帧测试图案（M2 自检：能看到渐变色 + 一条移动的白线就说明链路通了）。 */
    fun drawTestPattern(frameIndex: Int): Boolean = safeBool { nativeDrawTestPattern(frameIndex) }

    /** 清屏（黑屏）。 */
    fun clearSurface(argb: Int = 0xFF000000.toInt()): Boolean = safeBool { nativeClearSurface(argb) }

    private inline fun safeBool(block: () -> Boolean): Boolean = try {
        block()
    } catch (t: Throwable) {
        AppLog.w(TAG, "自研引擎 native 调用失败", t)
        false
    }

    private external fun nativeSetSurface(surface: android.view.Surface?)

    private external fun nativeHasSurface(): Boolean

    private external fun nativeDrawTestPattern(frameIndex: Int): Boolean

    private external fun nativeClearSurface(argb: Int): Boolean

    /** 自研 FFmpeg 的版本串（"未链接 FFmpeg" 表示引擎还没有解码能力）。 */
    fun ffmpegVersion(): String? = safe { nativeFfmpegVersion() }

    private external fun nativeFfmpegVersion(): String
}