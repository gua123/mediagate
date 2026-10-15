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
internal fun vlcProbeVerdict(started: Boolean, succeeded: Boolean, finished: Boolean): VlcProbeVerdict =
    when (probeOutcome(started, succeeded, finished)) {
        ProbeOutcome.OK -> VlcProbeVerdict.OK
        ProbeOutcome.UNKNOWN -> VlcProbeVerdict.UNKNOWN
        ProbeOutcome.FAILED -> VlcProbeVerdict.FAILED
    }

/** 探针三文件 → 结论（**两个探针共用同一套判据**：LibVLC 与自研引擎）。 */
internal enum class ProbeOutcome { UNKNOWN, OK, FAILED }

/**
 * 判据（与 [vlcProbeVerdict] 的 KDoc 同源）：
 * - 写了 ok ⇒ 通过；
 * - 连 start 都没写 ⇒ 未知（探针根本没跑起来，例如被系统拦了）；
 * - 开始过、没写成 ok ⇒ **不通过**：要么跑到底抛了 Java 异常，要么进程中途被带走。
 */
internal fun probeOutcome(started: Boolean, succeeded: Boolean, finished: Boolean): ProbeOutcome = when {
    succeeded -> ProbeOutcome.OK
    !started -> ProbeOutcome.UNKNOWN
    else -> ProbeOutcome.FAILED
}

/**
 * 自研引擎启动探针的结论（**2026-10-15**）。
 *
 * 与 LibVLC 探针同一套思路：探针跑在独立进程 `:engineprobe` 里，
 * FFmpeg 解码器初始化 / AAudio 开流这些"在个别机器上可能直接把进程带走"的步骤都在那边做；
 * 主进程只看文件，据此决定**要不要让用户切过去**。
 */
enum class EngineProbeVerdict {

    /** 还没测过。 */
    UNKNOWN,

    /** 自检通过：本机能用自研引擎。 */
    OK,

    /** 自检没通过（或进程被带走）：界面上先拦住，并给「重新测试」。 */
    FAILED,
}

internal fun engineProbeVerdict(started: Boolean, succeeded: Boolean, finished: Boolean): EngineProbeVerdict =
    when (probeOutcome(started, succeeded, finished)) {
        ProbeOutcome.OK -> EngineProbeVerdict.OK
        ProbeOutcome.UNKNOWN -> EngineProbeVerdict.UNKNOWN
        ProbeOutcome.FAILED -> EngineProbeVerdict.FAILED
    }

/**
 * 诊断卡里那一行「自研引擎探针：…」（**纯函数**，JVM 单测）。
 *
 * 四种情形都要说人话：通过（带上自检摘要）/ 没通过（带原因）/ 进程被带走 / 还没测。
 */
internal fun engineProbeLine(
    verdict: EngineProbeVerdict,
    reason: String? = null,
    report: String? = null,
): String = when (verdict) {
    EngineProbeVerdict.OK -> "自研引擎探针：通过" + (report?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: "")
    EngineProbeVerdict.FAILED ->
        "自研引擎探针：没通过" + (reason?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" }
            ?: "（进程被带走，没留下原因）")

    EngineProbeVerdict.UNKNOWN -> "自研引擎探针：还没测出结论"
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
