#ifndef MEDIAGATE_AUDIO_OUTPUT_H
#define MEDIAGATE_AUDIO_OUTPUT_H

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>

#include "av_sync.h"

namespace mediagate {

/**
 * 自研引擎的音频输出（M3 后半）。
 *
 * 结构：**AAudio 回调模式 + 我们自己的一秒 PCM 环形缓冲**。
 *  - 播放线程把解码并重采样好的 float 交错 PCM 写进环形缓冲（可以写在前头，
 *    所以视频解码卡一下不会立刻把声音饿死）；
 *  - AAudio 在自己的实时线程里回调取数据；数据不够时补静音并记一次欠载；
 *  - **音频时钟**：取 AAudio 的"已播出帧数"，按采样率换算成媒体时间
 *    ⇒ 视频按它对齐（音频做主轴，这是播放器的通行做法，也最不容易被听出来）。
 *
 * 只对上层暴露"打开 / 开始 / 暂停 / 停止 / 写数据 / 丢弃（seek）/ 时钟"，
 * FFmpeg 与线程模型都在 native_player 里，这里不碰。
 */
class AudioOutput {
public:
    AudioOutput() = default;
    ~AudioOutput();

    AudioOutput(const AudioOutput&) = delete;
    AudioOutput& operator=(const AudioOutput&) = delete;

    /** 建流（此时还没出声，第一次 write 之后再 start，避免开局就先欠载）。 */
    bool open(int sampleRate, int channels, std::string* error);

    /** 关流并释放。 */
    void close();

    bool isOpen() const { return stream_ != nullptr; }

    /** 是否正在出声（STARTED）。 */
    bool isRunning() const;

    /** 开始 / 从暂停继续（幂等）。 */
    void start();

    /** 暂停：设备立刻停，缓冲里的数据留着，继续播时接着放。 */
    void pause();

    /** 停止（保留流，之后还能 start）。 */
    void stop();

    /**
     * 写入交错 PCM。返回实际写入帧数——缓冲满时会少于 frames，
     * 调用方应当等一下再补写（**不要丢**，丢了就是可听的断音）。
     *
     * @param mediaMs 这一块第一帧在媒体时间轴上的位置（用于锚定音频时钟）
     */
    size_t write(const float* samples, size_t frames, int64_t mediaMs);

    /** 还能写多少帧（满了一秒的缓冲就写不进去了）。 */
    size_t spaceFrames() const { return ring_.spaceFrames(); }

    /**
     * seek（或换文件）：丢掉还没播出去的音频。
     * 调用方随后要把媒体时间轴重新锚定到新位置（见 seekEpoch）。
     */
    void flush();

    /** seek 之后立刻把时钟搬到目标位置，别让视频拿旧时钟去比。 */
    void seekEpoch(int64_t mediaMs);

    /** 音频时钟（毫秒，媒体时间轴）：视频同步用它当主轴。 */
    double clockMs() const;

    size_t queuedFrames() const { return ring_.queuedFrames(); }
    uint64_t underruns() const { return ring_.underruns(); }
    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }

    /** 出问题时给界面看的中文说明（空＝正常）。 */
    const std::string& error() const { return error_; }

private:
    static aaudio_data_callback_result_t dataCallback(AAudioStream* stream, void* userData,
                                                      void* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onData(float* out, int32_t numFrames);

    /** 设备"已经播出去"的帧数：优先用 AAudio 的时间戳，拿不到就按交付数估。 */
    int64_t presentedFrames() const;

    /** 等状态变化（最多等 timeoutMs），返回最终状态。 */
    aaudio_stream_state_t waitForState(aaudio_stream_state_t current, int64_t timeoutMs) const;

    void setError(const std::string& message);

    AAudioStream* stream_ = nullptr;
    PcmRing ring_;
    int sampleRate_ = 48000;
    int channels_ = 2;

    // 音频时钟锚点：anchorMediaMs 那一刻，设备帧计数是 anchorFrames
    std::atomic<bool> epochValid_{false};
    std::atomic<int64_t> epochMediaMs_{0};
    std::atomic<int64_t> epochFrames_{0};

    std::string error_;
};

}  // namespace mediagate

#endif  // MEDIAGATE_AUDIO_OUTPUT_H
