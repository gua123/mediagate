package io.github.gua123.mediagate.media.engine

/**
 * 播放内核种类（plan 4.6 引擎对照表，R9 多内核 / R10 硬软解）。
 *
 * - [MEDIA3]：默认内核，ProgressiveMediaSource + BackendDataSource（本地/远端同一套），
 *   MediaCodec 硬解优先，seek/字幕/缩略图联动最顺；
 * - [VLC]：兜底内核，LibVLC 3.7.6，媒体源走本机回环 HTTP 代理复用同一数据层，
 *   覆盖畸形 TS / 冷门编码，并能用 `avcodec-hw` 真正关掉硬解（R10）。
 */
enum class EngineKind(val label: String) {

    /** Media3 / ExoPlayer（系统 MediaCodec）。 */
    MEDIA3("Media3"),

    /** LibVLC（自带解码器，可强制软解）。 */
    VLC("LibVLC"),

    /**
     * **自研引擎**（`:media:engine-native`，C++17 + 自研 FFmpeg + AAudio）。
     *
     * 2026-10-06 用户拍板「视频引擎只采用一套，不要来回切换，自研一套视频播放引擎」⇒
     * 这就是唯一的那一套；Media3 / LibVLC 是**过渡期的兜底**，M5 覆盖打满后整条删除。
     * 能力差异见 [EngineCapabilities]（界面上要如实说明，不做静默降级）。
     */
    NATIVE("自研引擎"),
}
