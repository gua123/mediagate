#ifndef MEDIAGATE_NATIVE_PLAYER_H
#define MEDIAGATE_NATIVE_PLAYER_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "audio_output.h"
#include "avio_source.h"

namespace mediagate {

/** 播放状态（给界面用的一档）。 */
enum class PlayerState {
    IDLE = 0,
    PLAYING = 1,
    PAUSED = 2,
    STOPPED = 3,
    ERROR = 4,
    /** 播到结尾（画面停在最后一帧；seek 会回到播放中）。 */
    ENDED = 5,
};

/** 播放器自述（诊断卡 / 状态查询用）。 */
struct PlayerStatus {
    PlayerState state = PlayerState::IDLE;
    int64_t positionMs = 0;
    int64_t durationMs = 0;
    int64_t frames = 0;         // 已送屏帧数
    int64_t droppedFrames = 0;  // 因为迟到被丢掉的帧数（音画同步的代价）
    int videoWidth = 0;
    int videoHeight = 0;
    std::string codec;          // 视频解码器
    std::string audioCodec;     // 音频解码器（没有音频流时为空）
    int sampleRate = 0;         // 音频输出采样率（0＝没有音频）
    int channels = 0;
    uint64_t audioUnderruns = 0;
    int64_t avOffsetMs = 0;     // 最近一帧的音画偏差（视频 pts − 主时钟）
    std::string audioError;     // 音频输出不正常时的中文说明（空＝正常）
    std::string error;
};

/**
 * **自研引擎的播放核心**。
 *
 * 一条工作线程跑完整的播放循环：解复用 → 视频解码 → **按音频时钟节流送屏**；
 * 音频则解码 + 重采样成 48 kHz 双声道 float，写进 AAudio 输出（它自己有一秒的缓冲）。
 *
 * 设计要点：
 *  - **音频做主轴**：视频等音频时钟走到自己的 pts 才送屏，迟到太多就丢帧
 *    （丢帧比音画错位好看）。没有音频流时退化成"播放挂钟"当主轴。
 *  - 线程模型：一个工作线程 + AAudio 自己的实时回调线程；外部只通过原子标志通信，
 *    UI 线程永远不做阻塞的 FFmpeg / AAudio 调用。
 *  - 暂停时音频设备一起停（时钟跟着冻结，继续播时不会追帧）。
 *  - seek 会丢掉"已解码还没播出去"的音频，并把时间轴重新锚定到目标位置。
 */
class NativePlayer {
public:
    NativePlayer() = default;
    ~NativePlayer();

    NativePlayer(const NativePlayer&) = delete;
    NativePlayer& operator=(const NativePlayer&) = delete;

    /** 打开本地路径并准备解码器；成功后再 [play]。失败原因写回 [error]（中文）。 */
    bool open(const std::string& path, std::string* error);

    /**
     * 用**我们的数据层**打开（本地 / SAF / WebDAV / SFTP / FTP 都走这条）：
     * [source] 是实现 NativeMediaSource 的 Kotlin 对象，native 只借用（存全局引用，不负责 close）。
     */
    bool openSource(JNIEnv* env, jobject source, std::string* error);

    /** 开始（或从暂停继续）播放。 */
    void play();

    /** 暂停（画面与声音都停住，线程保留现场）。 */
    void pause();

    /** 停止并关闭文件（线程退出，状态回 IDLE）。 */
    void stop();

    /** 跳到某个位置（毫秒）；内部按关键帧回退定位，然后接着播。 */
    bool seekTo(int64_t positionMs);

    /**
     * 画面缩放档位（0=适应屏幕 1=裁切填充 2=拉伸铺满 3=原始尺寸，见 av_sync.h 的 ScaleMode）。
     * 只改"送屏缓冲算多大"，四个档位共用同一条送屏路径。
     */
    void setScaleMode(int mode);

    /** 音量倍率（0..2；>1 是软件增益，会削波）。 */
    void setVolume(float volume);

    /** 当前状态快照。 */
    PlayerStatus status() const;

    /**
     * 只读位置 / 时长（便宜版）。
     *
     * 界面每次刷新进度、字幕覆盖层每次都对位置 ⇒ 不能走 [status()]（它要拷贝解码器等字符串）。
     */
    int64_t positionMsFast() const { return positionMs_.load(); }

    int64_t durationMsFast() const { return durationMsAtomic_.load(); }

private:
    void threadLoop();
    void closeLocked();

    mutable std::mutex mutex_;
    std::string path_;
    std::string codecName_;
    std::string audioCodecName_;
    std::string audioError_;
    std::string error_;
    int64_t durationMs_ = 0;
    int videoWidth_ = 0;
    int videoHeight_ = 0;
    int sampleRate_ = 0;
    int channels_ = 0;

    /** 音频输出（AAudio）。打开失败时记录原因，视频照放。 */
    AudioOutput audio_;

    /** 数据来源（走我们数据层时才有效；本地路径打开时是空的）。 */
    SourceBridge source_;

    std::atomic<int> state_{static_cast<int>(PlayerState::IDLE)};
    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> hasSeek_{false};
    std::atomic<int64_t> seekTargetMs_{0};
    std::atomic<int64_t> positionMs_{0};
    std::atomic<int64_t> durationMsAtomic_{0};
    std::atomic<int64_t> frames_{0};
    std::atomic<int64_t> droppedFrames_{0};
    std::atomic<int64_t> avOffsetMs_{0};
    std::atomic<int> scaleMode_{0};
    std::atomic<float> volume_{1.0f};
    std::thread worker_;
};

}  // namespace mediagate

#endif  // MEDIAGATE_NATIVE_PLAYER_H
