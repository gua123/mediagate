#ifndef MEDIAGATE_NATIVE_PLAYER_H
#define MEDIAGATE_NATIVE_PLAYER_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace mediagate {

/** 播放状态（给界面用的一档）。 */
enum class PlayerState {
    IDLE = 0,
    PLAYING = 1,
    PAUSED = 2,
    STOPPED = 3,
    ERROR = 4,
};

/** 播放器自述（诊断卡 / 状态查询用）。 */
struct PlayerStatus {
    PlayerState state = PlayerState::IDLE;
    int64_t positionMs = 0;
    int64_t durationMs = 0;
    int64_t frames = 0;
    int videoWidth = 0;
    int videoHeight = 0;
    std::string codec;
    std::string error;
};

/**
 * **自研引擎的播放核心（M3 第一步：连续视频播放）**。
 *
 * 这一版只做视频：解复用 → 解码 → **按帧时间戳节流** → 送屏，支持 播放/暂停/继续/停止/seek。
 * 音频、音视频同步、以及接进 `PlayerEngine` 契约是接下来的事（所以现在还没有任何 UI 入口）。
 *
 * 线程模型：**一个工作线程**跑帧循环；外部只通过原子标志与它通信（play/pause/stop/seek 都是
 * "设一个意图就走"），避免在 UI 线程里做阻塞的 FFmpeg 调用。
 */
class NativePlayer {
public:
    NativePlayer() = default;
    ~NativePlayer();

    NativePlayer(const NativePlayer&) = delete;
    NativePlayer& operator=(const NativePlayer&) = delete;

    /** 打开文件并准备解码器；成功后再 [play]。失败原因写回 [error]（中文）。 */
    bool open(const std::string& path, std::string* error);

    /** 开始（或从暂停继续）播放。 */
    void play();

    /** 暂停（画面停住，线程保留现场）。 */
    void pause();

    /** 停止并关闭文件（线程退出，状态回 IDLE）。 */
    void stop();

    /** 跳到某个位置（毫秒）；内部按关键帧回退定位，然后接着播。 */
    bool seekTo(int64_t positionMs);

    /** 当前状态快照。 */
    PlayerStatus status() const;

private:
    void threadLoop();
    void closeLocked();

    mutable std::mutex mutex_;
    std::string path_;
    std::string codecName_;
    std::string error_;
    int64_t durationMs_ = 0;
    int videoWidth_ = 0;
    int videoHeight_ = 0;

    std::atomic<int> state_{static_cast<int>(PlayerState::IDLE)};
    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> hasSeek_{false};
    std::atomic<int64_t> seekTargetMs_{0};
    std::atomic<int64_t> positionMs_{0};
    std::atomic<int64_t> frames_{0};
    std::thread worker_;
};

}  // namespace mediagate

#endif  // MEDIAGATE_NATIVE_PLAYER_H
