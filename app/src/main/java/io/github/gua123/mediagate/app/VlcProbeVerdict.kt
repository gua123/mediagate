package io.github.gua123.mediagate.app

/**
 * LibVLC 启动探针的结论（**2026-10-03 用户建议**：「在启动后就进行内核测试，如果不能运行此内核，
 * 就在切换内核这里不让切换此内核，并且需要给出提示，需要注意，测试内核失败时不要让 app 崩溃闪退」）。
 */
enum class VlcProbeVerdict {

    /** 还没测过。 */
    UNKNOWN,

    /** 探针跑通：LibVLC 能在本机初始化。 */
    OK,

    /** 探针把进程带走了（原生崩溃/被信号杀）：本机 LibVLC 不可用，界面不让切。 */
    FAILED,
}

/**
 * 从"探针留下的三个证据"推断结论（**纯函数**，JVM 单测穷举）。
 *
 * 探针跑在**独立进程**（`android:process=":vlcprobe"`）里，所以它崩了只会带走自己；
 * 主进程事后靠这三样判断：
 * - [started]：探针开始过（写了 start 标记）；
 * - [succeeded]：探针写下了 ok（说明 LibVLC 初始化返回了）；
 * - [exitReason]：系统记录的**探针进程**上次退出原因（-1 = 拿不到）。
 *
 * 为什么还要看退出原因：探针进程也可能是**被系统按低内存回收**的——那不是 LibVLC 的错，
 * 这种情况要给 [VlcProbeVerdict.UNKNOWN]（下次再测），不能误判成"本机不可用"。
 */
internal fun vlcProbeVerdict(started: Boolean, succeeded: Boolean, exitReason: Int): VlcProbeVerdict = when {
    succeeded -> VlcProbeVerdict.OK
    !started -> VlcProbeVerdict.UNKNOWN
    // 开始过、没有 ok：只有当系统记录的是"崩溃类"原因时才算不可用
    isCrashLikeExit(exitReason) -> VlcProbeVerdict.FAILED
    else -> VlcProbeVerdict.UNKNOWN
}

/** 崩溃类退出原因（原生崩溃 / Java 崩溃 / 被信号杀 / ANR）。 */
internal fun isCrashLikeExit(reason: Int): Boolean = when (reason) {
    1, // ApplicationExitInfo.REASON_CRASH
    4, // REASON_CRASH_NATIVE
    6, // REASON_ANR
    8, // REASON_SIGNALED
    -> true

    else -> false
}
