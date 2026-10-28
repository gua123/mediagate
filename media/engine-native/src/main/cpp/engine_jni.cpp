#include <jni.h>

#include <string>

#include <android/native_window_jni.h>

#include "avio_source.h"
#include "engine_core.h"
#include "surface_renderer.h"
#include "first_frame.h"
#include "native_player.h"
#include "media_probe.h"
#include "self_check.h"
#include "subtitle_extract.h"

namespace {

/**
 * 只做"读元数据 / 抽字幕"这类一次性任务的 AVIO 缓冲。
 *
 * 比播放线程那份小一些：这些调用是同步的、用完就关，缓冲大只是白占内存。
 */
constexpr int kProbeAvioBufferBytes = 32 * 1024;

/** 一次最多抽多少条字幕 cue（防超长片头把内存吃满；正常一集字幕几千条够用）。 */
constexpr int kMaxSubtitleCues = 20000;

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

/** 全局唯一的渲染器（一套引擎＝一个渲染目标）。 */
mediagate::SurfaceRenderer& surfaceRenderer() {
    static mediagate::SurfaceRenderer renderer;
    return renderer;
}

/** 全局唯一的自研播放器（一套引擎＝一个播放核心）。 */
mediagate::NativePlayer& nativePlayer() {
    static mediagate::NativePlayer player;
    return player;
}

}  // namespace

namespace mediagate {

/** 供 first_frame.cpp 借用（所有权仍在本文件）。 */
SurfaceRenderer& globalSurfaceRenderer() {
    return surfaceRenderer();
}

}  // namespace mediagate

namespace {

}  // namespace

/**
 * 库加载时把 JVM 记下来：走数据层播放时，native 的播放线程要回调 Kotlin
 *（那个线程 JVM 不认识 ⇒ 需要 JavaVM 才能 AttachCurrentThread）。
 */
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    mediagate::setJavaVm(vm);
    return JNI_VERSION_1_6;
}

/**
 * JNI 边界（M1）：只回答"引擎是谁、内核是什么版本、内部有哪几条解码路径"。
 *
 * 边界纪律（写进方案 §8）：**粗接口**——传句柄 / fd / 直接缓冲区，
 * 绝不逐帧传数组；这里返回字符串已经是最细的一档，只用于自检与设置页显示。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeName(JNIEnv* env, jclass) {
    return toJString(env, mediagate::engineInfo().name);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeVersion(JNIEnv* env, jclass) {
    return toJString(env, mediagate::engineInfo().version);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeDecodePaths(JNIEnv* env, jclass) {
    return toJString(env, mediagate::engineInfo().decodePaths);
}
/**
 * 绑定/解绑渲染目标（Kotlin 侧传 android.view.Surface；nullptr 表示解绑）。
 *
 * 为什么 JNI 边界上传 Surface 而不是尺寸/像素：**Surface 是句柄**——一次传递，
 * 之后所有帧都在 native 侧直接写，不需要逐帧跨语言（方案 §8 的"边界要粗"）。
 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSetSurface(
        JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* window = surface == nullptr ? nullptr : ANativeWindow_fromSurface(env, surface);
    // ANativeWindow_fromSurface 已经 +1 引用；SurfaceRenderer 负责在换/释放时 -1
    surfaceRenderer().setWindow(window);
}

/**
 * 更新 Surface 缓冲尺寸。
 *
 * 为什么单独一个 JNI：surfaceCreated 时尺寸可能是 0；surfaceChanged 才有真实宽高。
 * setWindow 对同一个窗口指针会提前返回，所以必须在 changed 时显式把尺寸补上。
 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSetSurfaceSize(
        JNIEnv*, jclass, jint width, jint height) {
    surfaceRenderer().setBufferSize(width, height);
}

/** 是否已绑定可画的 Surface。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeHasSurface(JNIEnv*, jclass) {
    return surfaceRenderer().hasWindow() ? JNI_TRUE : JNI_FALSE;
}

/** 画一帧测试图案（M2 用来验证"Kotlin Surface → native 渲染"这条链路）。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeDrawTestPattern(
        JNIEnv*, jclass, jint frameIndex) {
    return surfaceRenderer().drawTestPattern(frameIndex) ? JNI_TRUE : JNI_FALSE;
}

/** 用纯色清屏。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeClearSurface(
        JNIEnv*, jclass, jint argb) {
    return surfaceRenderer().clear(static_cast<uint32_t>(argb)) ? JNI_TRUE : JNI_FALSE;
}
/** 自研 FFmpeg 的版本串（没链接时返回中文说明）。 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeFfmpegVersion(JNIEnv* env, jclass) {
    return toJString(env, mediagate::ffmpegVersion());
}
/**
 * 探测一个媒体文件（M2）：返回"容器 / 时长 / 流列表 / 选中视频流"的可读文本。
 *
 * 返回字符串而不是结构体：JNI 边界要**粗**——这里只是一条诊断/展示信息，
 * 真正的播放数据（帧、缓冲区）走的是 Surface 与 fd，不逐条跨语言。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeProbeMedia(
        JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return toJString(env, "路径为空");
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return toJString(env, "路径读取失败");
    const std::string filePath(raw);
    env->ReleaseStringUTFChars(path, raw);

    const mediagate::ProbeResult probe = mediagate::probeMedia(filePath);
    if (!probe.ok) return toJString(env, probe.error);

    std::string text = "容器 " + probe.format + "；时长 " + std::to_string(probe.durationMs / 1000) +
                       " 秒；码率 " + std::to_string(probe.bitRate / 1000) + " kbps；流 " +
                       std::to_string(probe.streams.size()) + " 条";
    for (const auto& stream : probe.streams) {
        text += "\n  #" + std::to_string(stream.index) + " " + stream.kind + " " + stream.codec;
        if (stream.width > 0) text += " " + std::to_string(stream.width) + "x" + std::to_string(stream.height);
        if (stream.sampleRate > 0) text += " " + std::to_string(stream.sampleRate) + "Hz/" +
                                        std::to_string(stream.channels) + "ch";
    }
    return toJString(env, text);
}

/** 解出并显示第一帧（M2 核心）。返回 "ok|codec|宽|高|耗时ms" 或中文错误原因。 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeRenderFirstFrame(
        JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return toJString(env, "路径为空");
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return toJString(env, "路径读取失败");
    const std::string filePath(raw);
    env->ReleaseStringUTFChars(path, raw);

    const mediagate::FirstFrameResult frame = mediagate::renderFirstFrame(filePath);
    if (!frame.ok) return toJString(env, frame.error);
    return toJString(env, "ok|" + frame.codec + "|" + std::to_string(frame.width) + "|" +
                     std::to_string(frame.height) + "|" + std::to_string(frame.decodeMs));
}
/** 打开文件并播放（M3：视频 + 音频 + 音画同步）。返回 "" 表示已开始。 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativePlayFile(
        JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return toJString(env, "路径为空");
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return toJString(env, "路径读取失败");
    const std::string filePath(raw);
    env->ReleaseStringUTFChars(path, raw);
    if (!surfaceRenderer().hasWindow()) return toJString(env, "还没有绑定画面（Surface 为空）");
    std::string error;
    if (!nativePlayer().open(filePath, &error)) return toJString(env, error);
    nativePlayer().play();
    return toJString(env, "");
}

/**
 * 用**我们自己的数据层**播放（M3：本地 / SAF / WebDAV / SFTP / FTP 同一条路）。
 *
 * 参数是实现 `NativeMediaSource` 的 Kotlin 对象；native 只借用（存全局引用），
 * 不负责关闭它——数据源由创建它的那一侧释放。返回 "" 表示已开始，否则是中文原因。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativePlaySource(
        JNIEnv* env, jclass, jobject source) {
    if (source == nullptr) return toJString(env, "没有可用的数据来源");
    if (!surfaceRenderer().hasWindow()) return toJString(env, "还没有绑定画面（Surface 为空）");
    std::string error;
    if (!nativePlayer().openSource(env, source, &error)) return toJString(env, error);
    nativePlayer().play();
    return toJString(env, "");
}

/** 暂停（画面停住，线程保留现场）。 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativePausePlayback(JNIEnv*, jclass) {
    nativePlayer().pause();
}

/** 从暂停继续。 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeResumePlayback(JNIEnv*, jclass) {
    nativePlayer().play();
}

/** 停止并关闭。 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeStopPlayback(JNIEnv*, jclass) {
    nativePlayer().stop();
}

/** 跳到某个位置（毫秒）。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSeekTo(
        JNIEnv*, jclass, jlong positionMs) {
    return nativePlayer().seekTo(static_cast<int64_t>(positionMs)) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 播放器自述（一条竖线分隔的状态串，Kotlin 侧 NativePlayerStatus 负责解析）：
 *
 *   状态|位置ms|时长ms|已显示帧数|丢帧数|宽x高|视频解码器|音频解码器|采样率|声道|音频欠载|音画偏差ms|音频输出说明|起播ms|seek等待ms
 *
 * 出错时返回 "错误：…"。字段只增不改序——M4 接进 PlayerEngine 契约之后界面也要读它。
 */
/**
 * 引擎自检（独立进程探针用）：返回 "ok|…" 或 "错误：…"（中文）。
 *
 * 为什么放在独立进程里调：自研引擎某些初始化（FFmpeg 解码器、AAudio 开流）在个别机器上
 * 可能**直接把进程带走**，与当年 LibVLC 的情况一样 —— 探针进程崩了只带走它自己。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSelfCheck(JNIEnv* env, jclass) {
    return toJString(env, mediagate::selfCheck());
}

/** 音量倍率（0..2；>1 是软件增益，会削波）。 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSetVolume(
        JNIEnv*, jclass, jfloat volume) {
    nativePlayer().setVolume(static_cast<float>(volume));
}

/** 画面缩放档位（0 适应屏幕 / 1 裁切填充 / 2 拉伸铺满 / 3 原始尺寸）。 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSetScaleMode(
        JNIEnv*, jclass, jint mode) {
    nativePlayer().setScaleMode(static_cast<int>(mode));
}

/** 当前播放位置（毫秒）。界面刷进度与字幕覆盖层都要频繁读它，所以单开一个不建字符串的入口。 */
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativePositionMs(JNIEnv*, jclass) {
    return static_cast<jlong>(nativePlayer().positionMsFast());
}

/** 媒体总时长（毫秒；未知为 0）。 */
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeDurationMs(JNIEnv*, jclass) {
    return static_cast<jlong>(nativePlayer().durationMsFast());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativePlayerStatus(JNIEnv* env, jclass) {
    const mediagate::PlayerStatus status = nativePlayer().status();
    if (!status.error.empty()) return toJString(env, "错误：" + status.error);
    const char* stateName = "空闲";
    switch (status.state) {
        case mediagate::PlayerState::PLAYING: stateName = "播放中"; break;
        case mediagate::PlayerState::PAUSED: stateName = "已暂停"; break;
        case mediagate::PlayerState::STOPPED: stateName = "已停止"; break;
        case mediagate::PlayerState::ENDED: stateName = "已播完"; break;
        case mediagate::PlayerState::ERROR: stateName = "出错"; break;
        default: break;
    }
    return toJString(env, std::string(stateName) + "|" + std::to_string(status.positionMs) + "|" +
                     std::to_string(status.durationMs) + "|" + std::to_string(status.frames) + "|" +
                     std::to_string(status.droppedFrames) + "|" + std::to_string(status.videoWidth) + "x" +
                     std::to_string(status.videoHeight) + "|" + status.codec + "|" + status.audioCodec + "|" +
                     std::to_string(status.sampleRate) + "|" + std::to_string(status.channels) + "|" +
                     std::to_string(status.audioUnderruns) + "|" + std::to_string(status.avOffsetMs) + "|" +
                     status.audioError + "|" + std::to_string(status.firstFrameMs) + "|" +
                     std::to_string(status.lastSeekMs));
}

/**
 * 列出内嵌字幕轨（**不解码**）：每行 "下标|编码|是否文本|语言|轨名"，没有字幕轨返回空串。
 *
 * 为什么要有它：本项目 FFmpeg 没编字幕解码器，但文本类字幕的 packet payload 本来就是纯文本，
 * 所以"列出 + 抽文本"这条路不需要解码器；界面据此显示"内嵌：中文（subrip）"之类的选项。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeListSubtitleTracks(
    JNIEnv* env, jclass, jobject source) {
    mediagate::SourceBridge bridge;
    std::string error;
    if (!bridge.attach(env, source, &error)) {
        return toJString(env, "错误：" + error);
    }
    AVIOContext* avio = mediagate::openSourceAvio(&bridge, kProbeAvioBufferBytes);
    AVFormatContext* format = nullptr;
    if (avio == nullptr || avformat_open_input(&format, nullptr, nullptr, nullptr) < 0 || format == nullptr) {
        if (format != nullptr) avformat_close_input(&format);
        if (avio != nullptr) mediagate::closeSourceAvio(&avio);
        const std::string sourceError = bridge.lastError();
        bridge.detach();
        return toJString(env, "错误：" + (sourceError.empty() ? std::string("打不开这个文件") : sourceError));
    }
    avformat_find_stream_info(format, nullptr);
    const std::vector<mediagate::SubtitleTrack> tracks = mediagate::listSubtitleTracks(format);
    std::string out;
    for (const mediagate::SubtitleTrack& track : tracks) {
        out += std::to_string(track.index) + "|" + track.codec + "|" + (track.textBased ? "1" : "0") + "|" +
               track.language + "|" + track.title + "\n";
    }
    avformat_close_input(&format);
    mediagate::closeSourceAvio(&avio);
    bridge.detach();
    return toJString(env, out);
}

/**
 * 把某条内嵌字幕轨抽成 **SRT 文本**（返回空串表示没有可用字幕；"错误：…" 才是失败）。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeExtractSubtitle(
    JNIEnv* env, jclass, jobject source, jint streamIndex) {
    mediagate::SourceBridge bridge;
    std::string error;
    if (!bridge.attach(env, source, &error)) {
        return toJString(env, "错误：" + error);
    }
    AVIOContext* avio = mediagate::openSourceAvio(&bridge, kProbeAvioBufferBytes);
    AVFormatContext* format = nullptr;
    if (avio == nullptr || avformat_open_input(&format, nullptr, nullptr, nullptr) < 0 || format == nullptr) {
        if (format != nullptr) avformat_close_input(&format);
        if (avio != nullptr) mediagate::closeSourceAvio(&avio);
        const std::string sourceError = bridge.lastError();
        bridge.detach();
        return toJString(env, "错误：" + (sourceError.empty() ? std::string("打不开这个文件") : sourceError));
    }
    avformat_find_stream_info(format, nullptr);
    std::string extractError;
    const std::string srt =
        mediagate::extractSubtitleAsSrt(format, streamIndex, kMaxSubtitleCues, &extractError);
    avformat_close_input(&format);
    mediagate::closeSourceAvio(&avio);
    bridge.detach();
    if (srt.empty()) {
        return toJString(env, "错误：" + (extractError.empty() ? std::string("这条字幕轨是空的") : extractError));
    }
    return toJString(env, srt);
}
