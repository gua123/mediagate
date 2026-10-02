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
 * 从"探针留下的三个文件"推断结论（**纯函数**，JVM 单测穷举）。
 *
 * 探针跑在**独立进程**（`android:process=":vlcprobe"`）里，所以它崩了只会带走自己；
 * 主进程事后只看文件（**不再依赖 ApplicationExitInfo**——2026-10-03 真机：反射读它拿不到东西，
 * 结果探针明明被带走了却报"没得到结论"，用户按了按钮还是不知道该不该切）：
 * - [started]：写了 `start`（探针开始过）；
 * - [succeeded]：写了 `ok`（LibVLC 初始化返回了）；
 * - [finished]：写了 `done`（**这段代码执行到底**，无论成功还是抛了 Java 异常）。
 *
 * 判据：`start` 有、`ok` 没有、`done` 也没有 ⇒ 进程在初始化中途被带走了（原生崩溃/被信号杀），
 * 就是"本机跑不了 LibVLC"。误判的代价可控：界面永远留着「重新测试」，下次会重跑一遍。
 */
internal fun vlcProbeVerdict(started: Boolean, succeeded: Boolean, finished: Boolean): VlcProbeVerdict = when {
    succeeded -> VlcProbeVerdict.OK
    !started -> VlcProbeVerdict.UNKNOWN
    // 开始过、没写成 ok：跑到底了（Java 层抛异常）或中途被带走，两种都算"起不来"
    else -> VlcProbeVerdict.FAILED
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
