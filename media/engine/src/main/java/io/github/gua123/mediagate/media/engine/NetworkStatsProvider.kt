package io.github.gua123.mediagate.media.engine

/**
 * 网络读取统计（2026-10-20，用户要求「nPlayer 加载快一倍」之后加的**取证**口径）。
 *
 * 播放页每次打开文件后会向宿主上报一行 PlayerEngine.metricsLine；数据源工厂实现这个接口，
 * 就能把「这次从网络读了多少字节、阻塞了多久」一并带进诊断卡——弱网时据此区分
 * 「网慢」还是「解码/渲染慢」。
 *
 * 放在 :media:engine 是为了让引擎只认接口：具体计数在 :media:playback 的数据源里。
 */
interface NetworkStatsProvider {

    /** 一行中文统计（没有数据时为 null）。 */
    fun networkStatsLine(): String?

    /** 开始播一个新文件时清零（每个文件一份统计）。 */
    fun resetNetworkStats()
}
