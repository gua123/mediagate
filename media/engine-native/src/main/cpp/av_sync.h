#ifndef MEDIAGATE_AV_SYNC_H
#define MEDIAGATE_AV_SYNC_H

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

/**
 * 音视频同步与时间换算的**纯逻辑**（不依赖 FFmpeg / AAudio）。
 *
 * 为什么要单独放一个头文件：native 代码在开发机上跑不了（没有设备），
 * 但这一层是"接错一个符号就能让音画错位"的地方 —— 抽出来之后可以用
 * scripts/test-engine-native-host.sh 直接在开发机上编译并跑单测
 *（见 media/engine-native/src/test/cpp/av_sync_host_test.cpp）。
 */
namespace mediagate {

// ------------------------------------------------------------------ 时间换算

/**
 * 把 FFmpeg 的 pts（时间基 = num/den 秒）换算成毫秒。
 *
 * 纯整数运算：先取整秒、再补余数，避免 pts 很大时浮点丢精度。
 * 调用方要先滤掉 AV_NOPTS_VALUE；这里对极端值做了夹取（返回 0 而不是整数溢出）。
 */
inline int64_t ptsToMs(int64_t pts, int num, int den) {
    if (den <= 0 || num <= 0) return 0;
    constexpr int64_t kLimit = static_cast<int64_t>(1) << 40;  // 约 34 年，单位是 pts
    if (pts <= -kLimit || pts >= kLimit) return 0;
    const int64_t scaled = pts * static_cast<int64_t>(num);
    return (scaled / den) * 1000 + (scaled % den) * 1000 / den;
}

/**
 * 时间轴归一：把"文件里的 pts"换算成"从 0 开始的位置"。
 *
 * 踩过的坑：TS/MPEG 流的 pts 常常不从 0 开始（例如 1.4 秒，或 90000*10），
 * 直接拿去和"从 0 开始计时的挂钟"比，第一帧会被判成"还早"，白等一大截。
 * 起点优先用流的 start_time（元信息可信时），不可信时用第一帧的 pts 兜底。
 */
struct Timeline {
    int64_t startMs = 0;
    bool bound = false;

    /** 用流的元信息绑定起点；hasStreamStart=false 表示 stream->start_time 无效。 */
    void bindFromStream(int64_t streamStartMs, bool hasStreamStart) {
        if (hasStreamStart) {
            startMs = streamStartMs;
            bound = true;
        }
    }

    /** 第一帧兜底绑定（只在元信息没给出起点时才生效）。 */
    void bindFromFirstFrame(int64_t firstFramePtsMs) {
        if (!bound) {
            startMs = firstFramePtsMs;
            bound = true;
        }
    }

    /** 文件 pts → 从 0 开始的位置。 */
    int64_t normalize(int64_t ptsMs) const { return ptsMs - startMs; }

    /** 从 0 开始的位置 → 文件 pts（seek 要的是后者）。 */
    int64_t toStreamTime(int64_t positionMs) const { return positionMs + startMs; }

    void reset() {
        startMs = 0;
        bound = false;
    }
};

// -------------------------------------------------------------- IO 位置换算

/**
 * 自定义 AVIO 的 seek 位置计算（纯函数，FFmpeg 的 whence 语义）。
 *
 * @param current 当前位置
 * @param offset  请求的偏移（可负）
 * @param base    0=SEEK_SET（从文件头）1=SEEK_CUR（从当前位置）2=SEEK_END（从末尾）
 * @param length  数据总长度（base=2 时要用）
 * @param lengthKnown 长度是否已知（数据层可能报 -1）
 * @param out     算出来的绝对位置
 * @return 能不能算（算不出 / 越界返回 false，调用方要回 AVERROR(EINVAL)）
 */
inline bool resolveSeekPosition(int64_t current, int64_t offset, int base, int64_t length,
                                bool lengthKnown, int64_t* out) {
    if (out == nullptr) return false;
    int64_t origin = 0;
    if (base == 1) {
        origin = current;
    } else if (base == 2) {
        if (!lengthKnown || length < 0) return false;
        origin = length;
    } else if (base != 0) {
        return false;
    }
    const int64_t target = origin + offset;
    if (target < 0) return false;
    *out = target;
    return true;
}

// ------------------------------------------------------------------ 同步判据

/** 视频帧该不该现在送屏。 */
struct SyncDecision {
    bool render = true;   // 现在就可以送屏
    bool drop = false;    // 太晚了：丢掉这一帧（丢帧比音画错位好看）
    int64_t waitMs = 0;   // 还早：至少再等这么多毫秒
};

/**
 * 视频帧与主时钟的比较（纯函数）。
 *
 * @param ptsMs        这一帧在媒体时间轴上的位置（已归一）
 * @param clockMs      主时钟：有音频时＝音频时钟，没有音频时＝播放挂钟
 * @param earlyToleranceMs 允许"稍早"的容差（不然每帧都要多等一点点）
 * @param dropThresholdMs  迟到超过这个值就丢帧
 */
inline SyncDecision decideVideoSync(int64_t ptsMs, double clockMs,
                                    int64_t earlyToleranceMs = 8,
                                    int64_t dropThresholdMs = 120) {
    SyncDecision decision;
    const double diff = static_cast<double>(ptsMs) - clockMs;
    if (diff > static_cast<double>(earlyToleranceMs)) {
        decision.render = false;
        decision.waitMs = static_cast<int64_t>(diff - static_cast<double>(earlyToleranceMs)) + 1;
        return decision;
    }
    if (diff < -static_cast<double>(dropThresholdMs)) {
        decision.render = false;
        decision.drop = true;
        return decision;
    }
    return decision;
}

/**
 * 音频时钟：把"设备已经播出去的帧数"换算成媒体时间（毫秒）。
 *
 * @param anchorMediaMs      锚点处的媒体时间（那一帧的 pts）
 * @param anchorFrames       锚点处的设备帧计数
 * @param presentedFrames    现在设备已播出的帧计数
 * @param sampleRate         输出采样率
 */
inline double audioClockMs(int64_t anchorMediaMs, int64_t anchorFrames,
                           int64_t presentedFrames, int sampleRate) {
    if (sampleRate <= 0) return static_cast<double>(anchorMediaMs);
    return static_cast<double>(anchorMediaMs) +
           static_cast<double>(presentedFrames - anchorFrames) * 1000.0 /
               static_cast<double>(sampleRate);
}

// -------------------------------------------------------------- PCM 环形缓冲

/**
 * 单生产者 / 单消费者 的 PCM 环形缓冲（float 交错）。
 *
 * 生产者＝播放线程；消费者＝AAudio 回调（**实时线程**）⇒
 * 这里只用原子量 + memcpy，**不加锁、不分配、不打日志**。
 *
 * 计数用单调递增的帧号（不做取模后的下标比较），当前占用＝written-read，
 * 这样"丢光未播数据"（seek）只需要把 read 推到 written，永远不会踩坏生产者。
 */
class PcmRing {
public:
    /** 配置容量（只在开始播放前调用一次，回调跑起来后不要再调用）。 */
    void configure(int channels, size_t capacityFrames) {
        channels_ = channels > 0 ? channels : 2;
        capacityFrames_ = std::max<size_t>(capacityFrames, 1);
        buffer_.assign(capacityFrames_ * static_cast<size_t>(channels_), 0.0f);
        written_.store(0, std::memory_order_relaxed);
        read_.store(0, std::memory_order_relaxed);
        delivered_.store(0, std::memory_order_relaxed);
        underruns_.store(0, std::memory_order_relaxed);
        flushes_.store(0, std::memory_order_relaxed);
        flushRequested_.store(false, std::memory_order_relaxed);
    }

    bool ready() const { return capacityFrames_ > 0 && !buffer_.empty(); }
    int channels() const { return channels_; }
    size_t capacityFrames() const { return capacityFrames_; }

    uint64_t producedFrames() const { return written_.load(std::memory_order_acquire); }
    uint64_t deliveredFrames() const { return delivered_.load(std::memory_order_acquire); }
    uint64_t underruns() const { return underruns_.load(std::memory_order_relaxed); }
    uint64_t flushes() const { return flushes_.load(std::memory_order_relaxed); }

    /** 还没被设备取走的帧数。 */
    size_t queuedFrames() const {
        const uint64_t written = written_.load(std::memory_order_acquire);
        const uint64_t read = read_.load(std::memory_order_acquire);
        return written > read ? static_cast<size_t>(written - read) : 0;
    }

    /** 生产者：还能写多少帧。 */
    size_t spaceFrames() const {
        const size_t queued = queuedFrames();
        return queued >= capacityFrames_ ? 0 : capacityFrames_ - queued;
    }

    /**
     * 生产者：写入交错 PCM。返回实际写入帧数——**缓冲满时会少于 frames**
     *（调用方应当等设备取走后再补写，而不是把音频丢掉）。
     */
    size_t write(const float* samples, size_t frames) {
        if (!ready() || samples == nullptr || frames == 0) return 0;
        const uint64_t written = written_.load(std::memory_order_relaxed);
        const uint64_t read = read_.load(std::memory_order_acquire);
        const uint64_t used = written > read ? written - read : 0;
        const size_t space = used >= capacityFrames_ ? 0 : capacityFrames_ - static_cast<size_t>(used);
        const size_t count = std::min(frames, space);
        if (count > 0) {
            copyToRing(written, samples, count);
            written_.store(written + count, std::memory_order_release);
        }
        return count;
    }

    /**
     * 消费者（实时线程）：读出交错 PCM，返回实际读到的帧数。
     * 不足的部分由调用方补静音并调用 noteUnderrun()。
     */
    size_t read(float* out, size_t frames) {
        applyFlushIfRequested();
        if (!ready() || out == nullptr || frames == 0) return 0;
        const uint64_t written = written_.load(std::memory_order_acquire);
        const uint64_t read = read_.load(std::memory_order_relaxed);
        const size_t available = written > read ? static_cast<size_t>(written - read) : 0;
        const size_t count = std::min(frames, available);
        if (count > 0) {
            copyFromRing(read, out, count);
            read_.store(read + count, std::memory_order_release);
        }
        delivered_.fetch_add(frames, std::memory_order_relaxed);
        return count;
    }

    /** 消费者：记一次欠载（回调里发现数据不够、补了静音时调用）。 */
    void noteUnderrun() { underruns_.fetch_add(1, std::memory_order_relaxed); }

    /** 生产者：请求"丢掉还没播的"（seek）。真正的丢弃由消费者执行。 */
    void requestFlush() { flushRequested_.store(true, std::memory_order_release); }

    bool flushPending() const { return flushRequested_.load(std::memory_order_acquire); }

    /**
     * 执行丢弃（read 追到 written）。
     * 正常情况下由消费者（回调）执行——read_ 只有一个写入者；
     * 只有在"回调根本没在跑"（未启动 / 已暂停 / 已停止）时，生产者可安全代劳。
     */
    void applyFlushIfRequested() {
        if (!flushRequested_.load(std::memory_order_acquire)) return;
        read_.store(written_.load(std::memory_order_acquire), std::memory_order_release);
        flushRequested_.store(false, std::memory_order_release);
        flushes_.fetch_add(1, std::memory_order_relaxed);
    }

private:
    void copyToRing(uint64_t startFrame, const float* src, size_t frames) {
        const size_t channels = static_cast<size_t>(channels_);
        size_t copied = 0;
        while (copied < frames) {
            const size_t index = static_cast<size_t>((startFrame + copied) % capacityFrames_);
            const size_t chunk = std::min(frames - copied, capacityFrames_ - index);
            std::memcpy(buffer_.data() + index * channels, src + copied * channels,
                        chunk * channels * sizeof(float));
            copied += chunk;
        }
    }

    void copyFromRing(uint64_t startFrame, float* dst, size_t frames) {
        const size_t channels = static_cast<size_t>(channels_);
        size_t copied = 0;
        while (copied < frames) {
            const size_t index = static_cast<size_t>((startFrame + copied) % capacityFrames_);
            const size_t chunk = std::min(frames - copied, capacityFrames_ - index);
            std::memcpy(dst + copied * channels, buffer_.data() + index * channels,
                        chunk * channels * sizeof(float));
            copied += chunk;
        }
    }

    std::vector<float> buffer_;
    int channels_ = 2;
    size_t capacityFrames_ = 0;
    std::atomic<uint64_t> written_{0};
    std::atomic<uint64_t> read_{0};
    std::atomic<uint64_t> delivered_{0};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<uint64_t> flushes_{0};
    std::atomic<bool> flushRequested_{false};
};

}  // namespace mediagate

#endif  // MEDIAGATE_AV_SYNC_H
