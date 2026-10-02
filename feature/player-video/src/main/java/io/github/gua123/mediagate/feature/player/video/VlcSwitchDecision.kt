package io.github.gua123.mediagate.feature.player.video

/** 点「内核」时该干什么（**纯函数**，2026-10-03 用户反馈「我没找到测试内核的地方」）。 */
enum class VlcKernelTap {

    /** 目标不是 LibVLC，或已经测过且可用：直接切。 */
    DIRECT,

    /** 目标 LibVLC 但还没测过 / 结果未知：先弹窗，里面给「先测试再切换」。 */
    ASK_WITH_TEST,

    /** 已知本机跑不了：**不让切**，只说明原因 + 「重新测试」。 */
    BLOCKED,
}

/**
 * 用户点内核芯片后的动作判定。
 *
 * 口径（用户原话：「如果不能运行此内核，就在切换内核这里不让切换此内核，并且需要给出提示」）：
 * - 目标不是 LibVLC → 直接切（Media3 那边没有这种风险）；
 * - 目标 LibVLC 且**已验证可用** → 直接切；
 * - 目标 LibVLC、**结果未知**（没测过，或测了但探针被系统回收）→ 弹窗，主按钮是「先测试再切换」；
 * - 目标 LibVLC、**已验证不可用** → 弹窗说明原因，**不给切换按钮**。
 */
fun vlcKernelTap(targetIsVlc: Boolean, vlcUsable: Boolean?): VlcKernelTap = when {
    !targetIsVlc -> VlcKernelTap.DIRECT
    vlcUsable == true -> VlcKernelTap.DIRECT
    vlcUsable == false -> VlcKernelTap.BLOCKED
    else -> VlcKernelTap.ASK_WITH_TEST
}
