#include "audio_output.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

/** 环形缓冲按"一秒"配：视频解码偶尔卡一下，也够声音撑过去。 */
constexpr int kBufferSeconds = 1;

/** 状态切换最多等多久（AAudio 的 start/pause 是异步的）。 */
constexpr int64_t kStateWaitMs = 100;

std::string resultText(aaudio_result_t result) {
    // 库返回的英文串只用于日志；给界面看的一律是我们自己的中文 + 数字码
    const char* text = AAudio_convertResultToText(result);
    return std::string("系统错误码 ") + std::to_string(static_cast<int>(result)) + "（" +
           (text == nullptr ? "未知" : text) + "）";
}

}  // namespace

AudioOutput::~AudioOutput() {
    close();
}

void AudioOutput::setError(const std::string& message) {
    error_ = message;
    __android_log_print(ANDROID_LOG_WARN, kTag, "音频输出：%s", message.c_str());
}

bool AudioOutput::open(int sampleRate, int channels, std::string* error) {
    close();
    error_.clear();
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    channels_ = channels == 1 ? 1 : 2;
    ring_.configure(channels_, static_cast<size_t>(sampleRate_) * kBufferSeconds);

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        setError("音频输出初始化失败：" + resultText(result));
        if (error != nullptr) *error = error_;
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder, channels_);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setDataCallback(builder, &AudioOutput::dataCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream_ == nullptr) {
        stream_ = nullptr;
        setError("打不开音频输出：" + resultText(result));
        if (error != nullptr) *error = error_;
        return false;
    }

    // 设备可能给一个和请求不同的采样率（共享模式下由系统混音）——以实际为准，
    // 否则音频时钟会按错误的速率走，音画会越差越多。
    const int32_t actualRate = AAudioStream_getSampleRate(stream_);
    if (actualRate > 0 && actualRate != sampleRate_) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "音频输出：请求 %d Hz，实际 %d Hz",
                            sampleRate_, actualRate);
        sampleRate_ = actualRate;
    }
    epochValid_.store(false);
    return true;
}

void AudioOutput::close() {
    if (stream_ == nullptr) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
    epochValid_.store(false);
}

bool AudioOutput::isRunning() const {
    return stream_ != nullptr && AAudioStream_getState(stream_) == AAUDIO_STREAM_STATE_STARTED;
}

aaudio_stream_state_t AudioOutput::waitForState(aaudio_stream_state_t current, int64_t timeoutMs) const {
    if (stream_ == nullptr) return AAUDIO_STREAM_STATE_CLOSED;
    aaudio_stream_state_t state = current;
    const int64_t deadlineNs = timeoutMs * 1000 * 1000;
    AAudioStream_waitForStateChange(stream_, current, &state, deadlineNs);
    return state;
}

void AudioOutput::start() {
    if (stream_ == nullptr) return;
    const aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) return;
    const aaudio_result_t result = AAudioStream_requestStart(stream_);
    if (result != AAUDIO_OK) {
        setError("音频输出启动失败：" + resultText(result));
        return;
    }
    const aaudio_stream_state_t next = waitForState(state, kStateWaitMs);
    if (next != AAUDIO_STREAM_STATE_STARTED && next != AAUDIO_STREAM_STATE_STARTING) {
        setError("音频输出没能进入播放状态（系统错误码 " + std::to_string(static_cast<int>(next)) + "）");
    }
}

void AudioOutput::pause() {
    if (stream_ == nullptr) return;
    const aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state != AAUDIO_STREAM_STATE_STARTED && state != AAUDIO_STREAM_STATE_STARTING) return;
    AAudioStream_requestPause(stream_);
    waitForState(state, kStateWaitMs);
    // 回调已经停了：此刻由生产者代劳"丢弃"是安全的（read_ 没有别的写入者）
    ring_.applyFlushIfRequested();
}

void AudioOutput::stop() {
    if (stream_ == nullptr) return;
    const aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_STOPPED || state == AAUDIO_STREAM_STATE_CLOSED) return;
    AAudioStream_requestStop(stream_);
    waitForState(state, kStateWaitMs);
    ring_.applyFlushIfRequested();
}

size_t AudioOutput::write(const float* samples, size_t frames, int64_t mediaMs) {
    if (stream_ == nullptr || !ring_.ready()) return 0;
    // 缓冲空了 ⇒ 设备已经把之前的都播完了，此刻"媒体时间 ↔ 设备帧计数"可以重新锚定。
    // 每次空了都重锚，误差不会累积（欠载/seek 之后能自己纠回来）。
    if (ring_.queuedFrames() == 0) {
        epochMediaMs_.store(mediaMs, std::memory_order_relaxed);
        epochFrames_.store(static_cast<int64_t>(ring_.deliveredFrames()), std::memory_order_relaxed);
        epochValid_.store(true, std::memory_order_release);
    }
    return ring_.write(samples, frames);
}

void AudioOutput::flush() {
    if (stream_ == nullptr) return;
    ring_.requestFlush();
    if (!isRunning()) {
        // 回调没在跑（没启动 / 已暂停 / 已停止）⇒ 生产者直接代劳
        ring_.applyFlushIfRequested();
        return;
    }
    // 回调通常一两毫秒就会把标志消费掉；等不到就自己来（宁可多丢一点，也不要放出旧音频）
    for (int i = 0; i < 50 && ring_.flushPending(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    ring_.applyFlushIfRequested();
}

void AudioOutput::seekEpoch(int64_t mediaMs) {
    epochMediaMs_.store(mediaMs, std::memory_order_relaxed);
    epochFrames_.store(static_cast<int64_t>(ring_.deliveredFrames()), std::memory_order_relaxed);
    epochValid_.store(true, std::memory_order_release);
}

int64_t AudioOutput::presentedFrames() const {
    if (stream_ == nullptr) return static_cast<int64_t>(ring_.deliveredFrames());
    if (AAudioStream_getState(stream_) == AAUDIO_STREAM_STATE_STARTED) {
        int64_t position = 0;
        int64_t nanos = 0;
        if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &position, &nanos) == AAUDIO_OK) {
            return position;
        }
        // 刚 start 的那一小段时间拿不到时间戳：按"交付帧数 - 设备缓冲"估一个
        const int32_t buffered = AAudioStream_getBufferSizeInFrames(stream_);
        const int64_t delivered = static_cast<int64_t>(ring_.deliveredFrames());
        return std::max<int64_t>(0, delivered - (buffered > 0 ? buffered : 0));
    }
    // 暂停/停止时回调不再跑，交付计数自然冻结 ⇒ 时钟也跟着停
    return static_cast<int64_t>(ring_.deliveredFrames());
}

double AudioOutput::clockMs() const {
    if (!epochValid_.load(std::memory_order_acquire)) return 0.0;
    return audioClockMs(epochMediaMs_.load(std::memory_order_relaxed),
                        epochFrames_.load(std::memory_order_relaxed), presentedFrames(), sampleRate_);
}

aaudio_data_callback_result_t AudioOutput::dataCallback(AAudioStream* /*stream*/, void* userData,
                                                        void* audioData, int32_t numFrames) {
    auto* self = static_cast<AudioOutput*>(userData);
    if (self == nullptr || audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    return self->onData(static_cast<float*>(audioData), numFrames);
}

aaudio_data_callback_result_t AudioOutput::onData(float* out, int32_t numFrames) {
    // ⚠️ 实时线程：只用原子量 + memcpy，不加锁、不分配、不打日志
    const size_t want = static_cast<size_t>(numFrames);
    const size_t got = ring_.read(out, want);
    if (got < want) {
        const size_t channels = static_cast<size_t>(channels_);
        std::memset(out + got * channels, 0, (want - got) * channels * sizeof(float));
        ring_.noteUnderrun();
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

}  // namespace mediagate
