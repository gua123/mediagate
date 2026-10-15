#include "native_player.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/time.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include "surface_renderer.h"

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

/** 走数据层时 FFmpeg 的读缓冲大小：一次 64 KB，比逐包小读省 JNI 往返。 */
constexpr int kAvioBufferBytes = 64 * 1024;

/** 暂停/停止时的轮询间隔：够了，不要再密（省电）。 */
constexpr auto kIdlePoll = std::chrono::milliseconds(5);

/** 等帧时每片最多睡多久：切片小才来得及响应暂停 / seek / 停止。 */
constexpr int64_t kWaitSliceMs = 20;

/** 连续这么多片主时钟都没动，就当它卡住了（音频设备异常时别把画面也拖死）。 */
constexpr int kClockStallSlices = 25;

/** 音频统一重采样成 48 kHz 双声道 float（AAudio 的通用格式，避开设备差异）。 */
constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannels = 2;

/** 音频先攒够这么多帧再开声（太少会一上来就欠载、开头"咔"一下）。 */
constexpr size_t kAudioPrefillFrames = static_cast<size_t>(kAudioSampleRate) / 10;  // 100 ms

/** 音画同步：稍早可以忍，迟到超过阈值就丢帧（丢帧比音画错位好看）。 */
constexpr int64_t kSyncEarlyToleranceMs = 8;
constexpr int64_t kSyncDropThresholdMs = 120;

/** seek 之后允许"比目标点早这么多"的帧留下（避开取整误差把目标帧误杀）。 */
constexpr int64_t kSeekDropSlackMs = 20;

/** EOF 之后最多再等多久，让音频把缓冲里剩下的播完。 */
constexpr int64_t kEofDrainMs = 3000;

/** 环形缓冲满了两片之间的间隔（等设备取走）。 */
constexpr auto kAudioFullPoll = std::chrono::milliseconds(2);

}  // namespace

/**
 * 由 JNI 层提供（见 engine_jni.cpp）。
 *
 * ⚠️ 必须在**匿名 namespace 之外**声明：放在里面会变成内部链接，
 * 链接期报 undefined symbol: mediagate::(anonymous namespace)::globalSurfaceRenderer()
 *（M3 真踩过一次）。
 */
SurfaceRenderer& globalSurfaceRenderer();

NativePlayer::~NativePlayer() {
    stop();
}

void NativePlayer::closeLocked() {
    // 这里只清"元信息"；FFmpeg 资源在 worker 线程里成对创建与释放（避免跨线程释放）
    source_.detach();
    std::lock_guard<std::mutex> lock(mutex_);
    path_.clear();
    codecName_.clear();
    audioCodecName_.clear();
    audioError_.clear();
    durationMs_ = 0;
    videoWidth_ = 0;
    videoHeight_ = 0;
    sampleRate_ = 0;
    channels_ = 0;
}

bool NativePlayer::open(const std::string& path, std::string* error) {
    if (running_.load()) stop();
    source_.detach();  // 换成"本地路径"模式：把上一次的数据来源还掉
    std::lock_guard<std::mutex> lock(mutex_);
    path_ = path;
    error_.clear();
    audioError_.clear();
    if (error != nullptr) error->clear();
    state_.store(static_cast<int>(PlayerState::IDLE));
    return true;
}

bool NativePlayer::openSource(JNIEnv* env, jobject source, std::string* error) {
    if (running_.load()) stop();
    std::string message;
    if (!source_.attach(env, source, &message)) {
        if (error != nullptr) *error = message;
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    path_.clear();
    error_.clear();
    audioError_.clear();
    if (error != nullptr) error->clear();
    state_.store(static_cast<int>(PlayerState::IDLE));
    return true;
}

void NativePlayer::play() {
    if (path_.empty()) return;
    if (running_.load()) {
        // 已经在跑：只是从暂停恢复
        paused_.store(false);
        state_.store(static_cast<int>(PlayerState::PLAYING));
        return;
    }
    paused_.store(false);
    hasSeek_.store(false);
    droppedFrames_.store(0);
    avOffsetMs_.store(0);
    running_.store(true);
    state_.store(static_cast<int>(PlayerState::PLAYING));
    worker_ = std::thread([this] { threadLoop(); });
}

void NativePlayer::pause() {
    if (!running_.load()) return;
    paused_.store(true);
    state_.store(static_cast<int>(PlayerState::PAUSED));
}

void NativePlayer::stop() {
    running_.store(false);
    paused_.store(false);
    if (worker_.joinable()) worker_.join();
    state_.store(static_cast<int>(PlayerState::STOPPED));
    closeLocked();
}

bool NativePlayer::seekTo(int64_t positionMs) {
    if (!running_.load()) return false;
    seekTargetMs_.store(positionMs < 0 ? 0 : positionMs);
    hasSeek_.store(true);
    return true;
}

PlayerStatus NativePlayer::status() const {
    PlayerStatus snapshot;
    snapshot.state = static_cast<PlayerState>(state_.load());
    snapshot.positionMs = positionMs_.load();
    snapshot.frames = frames_.load();
    snapshot.droppedFrames = droppedFrames_.load();
    snapshot.avOffsetMs = avOffsetMs_.load();
    snapshot.audioUnderruns = audio_.underruns();
    std::lock_guard<std::mutex> lock(mutex_);
    snapshot.durationMs = durationMs_;
    snapshot.videoWidth = videoWidth_;
    snapshot.videoHeight = videoHeight_;
    snapshot.sampleRate = sampleRate_;
    snapshot.channels = channels_;
    snapshot.codec = codecName_;
    snapshot.audioCodec = audioCodecName_;
    snapshot.audioError = audioError_;
    snapshot.error = error_;
    return snapshot;
}

/**
 * 播放循环（工作线程）。
 *
 * 顺序：打开 → 解复用 → （音频解码 + 重采样 → 写 AAudio）/（视频解码 → 等主时钟 → 送屏）；
 * 期间响应 暂停 / seek / 停止。**主时钟**：有音频时是音频时钟，没有音频时是"播放挂钟"。
 */
void NativePlayer::threadLoop() {
    AVFormatContext* format = nullptr;
    AVIOContext* sourceAvio = nullptr;  // 走数据层时的读回调上下文（我们自己建、自己释放）
    AVCodecContext* videoCtx = nullptr;
    AVCodecContext* audioCtx = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    SwsContext* sws = nullptr;
    SwrContext* swr = nullptr;
    int swsSrcW = 0;
    int swsSrcH = 0;
    int swsDstW = 0;
    int swsDstH = 0;
    std::vector<uint8_t> rgba;
    std::vector<float> pcm;

    int videoIndex = -1;
    int audioIndex = -1;
    AVRational videoTimeBase{1, 1000};
    AVRational audioTimeBase{1, 1000};
    Timeline videoTimeline;
    Timeline audioTimeline;

    bool audioReady = false;        // 音频流 + 音频输出都就绪
    bool audioStarted = false;      // AAudio 已经开声
    bool audioHeldForPause = false; // 暂停时我们把音频设备也按住了
    bool audioDrained = false;      // EOF 之后音频已经播完并被按住
    int64_t videoDropUntilMs = -1;  // seek 之后丢掉目标点之前的视频帧
    int64_t audioDropUntilMs = -1;  // 同上（音频）
    int64_t nextAudioUs = 0;        // 下一块音频在媒体时间轴上的位置
    bool audioAnchorSet = false;

    double wallClockMs = 0;         // 本"播放段"累计走过的真实时间
    int64_t wallBaseMediaMs = 0;    // 这段真实时间对应的媒体位置
    int64_t lastTickUs = 0;

    auto fail = [&](const std::string& message) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "自研播放失败：%s", message.c_str());
        {
            std::lock_guard<std::mutex> lock(mutex_);
            error_ = message;
        }
        state_.store(static_cast<int>(PlayerState::ERROR));
        running_.store(false);
    };

    /** 把音频输出自己记的原因搬进状态（界面/诊断卡能看见）。 */
    auto syncAudioError = [&] {
        const std::string& message = audio_.error();
        if (message.empty()) return;
        std::lock_guard<std::mutex> lock(mutex_);
        audioError_ = message;
    };

    /** 挂钟只在"真的在播"时前进（暂停不算），这样暂停久了继续播也不会追帧。 */
    auto tickClock = [&] {
        const int64_t nowUs = av_gettime_relative();
        if (lastTickUs != 0 && !paused_.load()) {
            wallClockMs += static_cast<double>(nowUs - lastTickUs) / 1000.0;
        }
        lastTickUs = nowUs;
    };

    /** 音频设备是否正在出声（它一停，时钟就不动了）。 */
    auto audioMaster = [&]() -> bool {
        return audioReady && audioStarted && audio_.isRunning();
    };

    /** 主时钟：有音频就听音频的，没有就用播放挂钟。 */
    auto masterClockMs = [&]() -> double {
        if (audioMaster()) return audio_.clockMs();
        return static_cast<double>(wallBaseMediaMs) + wallClockMs;
    };

    auto updatePosition = [&] {
        int64_t position = static_cast<int64_t>(masterClockMs());
        if (position < 0) position = 0;
        const int64_t duration = durationMs_;
        if (duration > 0 && position > duration) position = duration;
        positionMs_.store(position);
    };

    /**
     * 音频帧 → 重采样 → 写进 AAudio 缓冲。
     *
     * 缓冲满时**等一下再补写**（不是丢掉）：丢了就是可听的断音。
     * 第一块写完并且攒够预填量之后才开声，避免开局就欠载。
     */
    auto handleAudioFrame = [&](AVFrame* audioFrame) {
        if (!audioReady || swr == nullptr) return;

        const int64_t rawPts = audioFrame->pts != AV_NOPTS_VALUE ? audioFrame->pts
                                                                 : audioFrame->best_effort_timestamp;
        const int64_t ptsMs = rawPts == AV_NOPTS_VALUE
                                  ? -1
                                  : ptsToMs(rawPts, audioTimeBase.num, audioTimeBase.den);
        if (ptsMs >= 0) audioTimeline.bindFromFirstFrame(ptsMs);
        const int64_t normMs = ptsMs < 0 ? -1 : audioTimeline.normalize(ptsMs);

        if (audioDropUntilMs >= 0 && normMs >= 0 && normMs < audioDropUntilMs - kSeekDropSlackMs) {
            return;  // seek 目标点之前的声音：不要
        }
        if (audioDropUntilMs >= 0) audioDropUntilMs = -1;

        if (!audioAnchorSet) {
            // 第一块（或 seek 后的第一块）：媒体时间轴从这里接上
            const int64_t startMs = normMs >= 0 ? normMs : (seekTargetMs_.load());
            nextAudioUs = startMs * 1000;
            audioAnchorSet = true;
        }

        const int outMax = swr_get_out_samples(swr, audioFrame->nb_samples);
        if (outMax <= 0) return;
        const size_t need = static_cast<size_t>(outMax) * kAudioChannels;
        if (pcm.size() < need) pcm.resize(need);
        uint8_t* outData[1] = {reinterpret_cast<uint8_t*>(pcm.data())};
        const int outSamples = swr_convert(swr, outData, outMax, audioFrame->extended_data,
                                           audioFrame->nb_samples);
        if (outSamples <= 0) return;

        size_t offset = 0;
        const size_t total = static_cast<size_t>(outSamples);
        while (offset < total && running_.load() && !paused_.load() && !hasSeek_.load()) {
            const size_t written = audio_.write(pcm.data() + offset * kAudioChannels, total - offset,
                                                nextAudioUs / 1000);
            if (written == 0) {
                // 缓冲满：等设备取走。没开声就先开声，否则永远没人取。
                if (!audioStarted) {
                    audio_.start();
                    audioStarted = true;
                    syncAudioError();
                }
                std::this_thread::sleep_for(kAudioFullPoll);
                continue;
            }
            offset += written;
            nextAudioUs += static_cast<int64_t>(written) * 1000000 / kAudioSampleRate;
            if (!audioStarted && audio_.queuedFrames() >= kAudioPrefillFrames) {
                audio_.start();
                audioStarted = true;
                syncAudioError();
            }
        }
        if (!audioStarted && audio_.queuedFrames() > 0) {
            audio_.start();
            audioStarted = true;
            syncAudioError();
        }
    };

    /** 视频帧 → 等主时钟 → 转 RGBA → 送屏。 */
    auto handleVideoFrame = [&](AVFrame* videoFrame) {
        const int64_t rawPts = videoFrame->pts != AV_NOPTS_VALUE ? videoFrame->pts
                                                                 : videoFrame->best_effort_timestamp;
        const int64_t ptsMs = rawPts == AV_NOPTS_VALUE
                                  ? -1
                                  : ptsToMs(rawPts, videoTimeBase.num, videoTimeBase.den);
        if (ptsMs >= 0) videoTimeline.bindFromFirstFrame(ptsMs);
        const int64_t normMs = ptsMs < 0 ? positionMs_.load() : videoTimeline.normalize(ptsMs);

        if (videoDropUntilMs >= 0) {
            if (normMs < videoDropUntilMs - kSeekDropSlackMs) return;  // 关键帧回退带出来的旧帧
            videoDropUntilMs = -1;
        }

        // 等主时钟走到这一帧：早了就睡（切片），迟到太多就丢
        double lastClock = -1.0;
        int stalledSlices = 0;
        while (running_.load() && !paused_.load() && !hasSeek_.load()) {
            const double clock = masterClockMs();
            if (clock > lastClock + 0.001) {
                lastClock = clock;
                stalledSlices = 0;
            } else if (++stalledSlices > kClockStallSlices) {
                break;  // 时钟卡住了（设备异常）：先把画面放出去，别一起僵住
            }
            const SyncDecision decision =
                decideVideoSync(normMs, clock, kSyncEarlyToleranceMs, kSyncDropThresholdMs);
            if (decision.drop) {
                droppedFrames_.fetch_add(1);
                return;
            }
            if (decision.render) break;
            const int64_t slice = std::max<int64_t>(std::min<int64_t>(decision.waitMs, kWaitSliceMs), 1);
            std::this_thread::sleep_for(std::chrono::milliseconds(slice));
            tickClock();
        }
        if (!running_.load() || paused_.load() || hasSeek_.load()) return;  // 期间被打断：这帧不要了

        const int srcW = videoFrame->width;
        const int srcH = videoFrame->height;
        int winW = 0;
        int winH = 0;
        globalSurfaceRenderer().windowSize(&winW, &winH);
        if (srcW <= 0 || srcH <= 0 || winW <= 0 || winH <= 0) return;

        const double scale = std::min(static_cast<double>(winW) / srcW, static_cast<double>(winH) / srcH);
        const int dstW = std::max(1, static_cast<int>(srcW * scale));
        const int dstH = std::max(1, static_cast<int>(srcH * scale));
        // 画面尺寸变了（转屏、进小窗）要重建缩放器，否则会按老尺寸缩放、比例变形
        if (sws == nullptr || swsSrcW != srcW || swsSrcH != srcH || swsDstW != dstW || swsDstH != dstH) {
            if (sws != nullptr) sws_freeContext(sws);
            sws = sws_getContext(srcW, srcH, static_cast<AVPixelFormat>(videoFrame->format), dstW, dstH,
                                 AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
            swsSrcW = srcW;
            swsSrcH = srcH;
            swsDstW = dstW;
            swsDstH = dstH;
            rgba.assign(static_cast<size_t>(dstW) * dstH * 4, 0);
        }
        if (sws == nullptr) return;

        uint8_t* dstData[4] = {rgba.data(), nullptr, nullptr, nullptr};
        int dstStride[4] = {dstW * 4, 0, 0, 0};
        sws_scale(sws, videoFrame->data, videoFrame->linesize, 0, srcH, dstData, dstStride);
        if (globalSurfaceRenderer().blitRgba(rgba.data(), dstW, dstH)) {
            frames_.fetch_add(1);
            avOffsetMs_.store(static_cast<int64_t>(normMs - masterClockMs()));
        }
    };

    // ---------------------------------------------------------------- 打开
    bool ready = false;
    const bool fromSource = source_.valid();
    std::string path;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        path = path_;
    }
    do {
        if (fromSource) {
            // 走我们的数据层：FFmpeg 的读/seek 都回调到 Kotlin（本地/SAF/远端同一套）
            sourceAvio = openSourceAvio(&source_, kAvioBufferBytes);
            if (sourceAvio == nullptr) {
                fail("打不开数据来源（引擎侧建读缓冲失败）");
                break;
            }
            format = avformat_alloc_context();
            if (format == nullptr) {
                fail("内存不足，打不开播放器");
                break;
            }
            format->pb = sourceAvio;
            format->flags |= AVFMT_FLAG_CUSTOM_IO;
            if (avformat_open_input(&format, nullptr, nullptr, nullptr) < 0 || format == nullptr) {
                const std::string reason = source_.lastError();
                fail(reason.empty() ? std::string("打不开这个文件（数据层没给出可用的媒体信息）")
                                    : ("打不开这个文件：" + reason));
                break;
            }
        } else {
            if (path.empty()) {
                fail("没有要播放的文件");
                break;
            }
            if (avformat_open_input(&format, path.c_str(), nullptr, nullptr) < 0 || format == nullptr) {
                fail("打不开这个文件");
                break;
            }
        }
        if (avformat_find_stream_info(format, nullptr) < 0) {
            const std::string reason = fromSource ? source_.lastError() : std::string();
            fail(reason.empty() ? std::string("读不出媒体信息（文件可能损坏或还没下完）")
                                : ("读不出媒体信息：" + reason));
            break;
        }

        const AVCodec* videoDecoder = nullptr;
        videoIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &videoDecoder, 0);
        if (videoIndex < 0 || videoDecoder == nullptr) {
            fail("这个文件里没有能解码的视频流");
            break;
        }
        videoCtx = avcodec_alloc_context3(videoDecoder);
        if (videoCtx == nullptr ||
            avcodec_parameters_to_context(videoCtx, format->streams[videoIndex]->codecpar) < 0 ||
            avcodec_open2(videoCtx, videoDecoder, nullptr) < 0) {
            fail("解码器打不开（这个编码可能本机不支持）");
            break;
        }
        videoCtx->thread_count = 0;  // 让 FFmpeg 自己决定解码线程数
        videoTimeBase = format->streams[videoIndex]->time_base;

        const AVCodec* audioDecoder = nullptr;
        audioIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, videoIndex, &audioDecoder, 0);
        if (audioIndex >= 0 && audioDecoder != nullptr) {
            audioCtx = avcodec_alloc_context3(audioDecoder);
            if (audioCtx == nullptr ||
                avcodec_parameters_to_context(audioCtx, format->streams[audioIndex]->codecpar) < 0 ||
                avcodec_open2(audioCtx, audioDecoder, nullptr) < 0) {
                // 音频开不了不该把视频拖下水：放弃音频，继续放画面
                if (audioCtx != nullptr) avcodec_free_context(&audioCtx);
                audioCtx = nullptr;
                audioIndex = -1;
            } else {
                audioTimeBase = format->streams[audioIndex]->time_base;
                AVChannelLayout outLayout = AV_CHANNEL_LAYOUT_STEREO;
                AVChannelLayout inLayout;
                if (audioCtx->ch_layout.nb_channels > 0) {
                    av_channel_layout_copy(&inLayout, &audioCtx->ch_layout);
                } else {
                    av_channel_layout_default(&inLayout, 2);
                }
                const int swrRet = swr_alloc_set_opts2(&swr, &outLayout, AV_SAMPLE_FMT_FLT,
                                                       kAudioSampleRate, &inLayout, audioCtx->sample_fmt,
                                                       audioCtx->sample_rate, 0, nullptr);
                av_channel_layout_uninit(&inLayout);
                if (swrRet < 0 || swr == nullptr || swr_init(swr) < 0) {
                    if (swr != nullptr) swr_free(&swr);
                    swr = nullptr;
                } else {
                    std::string audioError;
                    if (audio_.open(kAudioSampleRate, kAudioChannels, &audioError)) {
                        audioReady = true;
                    } else {
                        std::lock_guard<std::mutex> lock(mutex_);
                        audioError_ = audioError;
                    }
                }
            }
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            codecName_ = videoDecoder->name == nullptr ? "unknown" : videoDecoder->name;
            audioCodecName_ = (audioCtx != nullptr && audioDecoder->name != nullptr) ? audioDecoder->name : "";
            videoWidth_ = videoCtx->width;
            videoHeight_ = videoCtx->height;
            sampleRate_ = audioReady ? audio_.sampleRate() : 0;
            channels_ = audioReady ? audio_.channels() : 0;
            const int64_t startTime = format->start_time;
            const int64_t duration = format->duration;
            durationMs_ = duration == AV_NOPTS_VALUE
                              ? 0
                              : (duration - (startTime == AV_NOPTS_VALUE ? 0 : startTime)) / (AV_TIME_BASE / 1000);
        }

        // 时间轴起点：优先用流的 start_time，没有就让第一帧兜底（TS 常常不从 0 开始）
        const int64_t videoStart = format->streams[videoIndex]->start_time;
        videoTimeline.bindFromStream(videoStart == AV_NOPTS_VALUE
                                         ? 0
                                         : ptsToMs(videoStart, videoTimeBase.num, videoTimeBase.den),
                                     videoStart != AV_NOPTS_VALUE);
        if (audioIndex >= 0) {
            const int64_t audioStart = format->streams[audioIndex]->start_time;
            audioTimeline.bindFromStream(audioStart == AV_NOPTS_VALUE
                                             ? 0
                                             : ptsToMs(audioStart, audioTimeBase.num, audioTimeBase.den),
                                         audioStart != AV_NOPTS_VALUE);
        }
        ready = true;
    } while (false);

    // ---------------------------------------------------------------- 播放
    if (ready) {
        packet = av_packet_alloc();
        frame = av_frame_alloc();
        bool eof = false;
        int64_t eofSinceUs = 0;
        lastTickUs = av_gettime_relative();

        while (running_.load()) {
            if (paused_.load()) {
                if (!audioHeldForPause && audioReady) {
                    audio_.pause();
                    audioHeldForPause = true;
                }
                lastTickUs = 0;  // 暂停期间挂钟不走
                std::this_thread::sleep_for(kIdlePoll);
                continue;
            }
            if (audioHeldForPause) {
                // 从暂停继续：设备接着放，音频时钟从冻结处继续走
                if (audioReady) audio_.start();
                audioHeldForPause = false;
                lastTickUs = 0;
            }
            tickClock();

            if (hasSeek_.exchange(false)) {
                int64_t targetMs = seekTargetMs_.load();
                if (targetMs < 0) targetMs = 0;
                const int64_t streamTs = av_rescale_q(videoTimeline.toStreamTime(targetMs),
                                                      AVRational{1, 1000}, videoTimeBase);
                if (av_seek_frame(format, videoIndex, streamTs, AVSEEK_FLAG_BACKWARD) >= 0) {
                    avcodec_flush_buffers(videoCtx);
                    if (audioCtx != nullptr) avcodec_flush_buffers(audioCtx);
                    if (swr != nullptr) {
                        const int64_t delay = swr_get_delay(swr, kAudioSampleRate);
                        if (delay > 0) swr_drop_output(swr, static_cast<int>(delay));
                    }
                    videoDropUntilMs = targetMs;
                    audioDropUntilMs = targetMs;
                    audioAnchorSet = false;
                    nextAudioUs = targetMs * 1000;
                    if (audioReady) {
                        audio_.flush();          // 丢掉"已解码还没播出去"的旧声音
                        audio_.seekEpoch(targetMs);  // 时钟立刻搬到新位置，别让视频拿旧时钟去比
                        if (audioStarted && audioDrained) {
                            audio_.start();
                            syncAudioError();
                        }
                    }
                    audioDrained = false;
                    eof = false;
                    wallClockMs = 0;
                    wallBaseMediaMs = targetMs;
                    lastTickUs = 0;
                    positionMs_.store(targetMs);
                }
            }

            if (!eof) {
                const int readRet = av_read_frame(format, packet);
                if (readRet < 0) {
                    if (fromSource && readRet != AVERROR_EOF) {
                        // 读数据真的失败了（不是正常读完）：把数据层记的中文原因带出来，
                        // 否则用户只会看到"播着播着停了"。
                        const std::string reason = source_.lastError();
                        if (!reason.empty()) {
                            fail("读取数据出错：" + reason);
                            break;
                        }
                    }
                    eof = true;
                    eofSinceUs = av_gettime_relative();
                } else if (packet->stream_index == videoIndex) {
                    if (avcodec_send_packet(videoCtx, packet) >= 0) {
                        while (avcodec_receive_frame(videoCtx, frame) == 0) {
                            handleVideoFrame(frame);
                            av_frame_unref(frame);
                            if (!running_.load() || paused_.load() || hasSeek_.load()) break;
                        }
                    }
                    av_packet_unref(packet);
                } else if (packet->stream_index == audioIndex && audioCtx != nullptr) {
                    if (avcodec_send_packet(audioCtx, packet) >= 0) {
                        while (avcodec_receive_frame(audioCtx, frame) == 0) {
                            handleAudioFrame(frame);
                            av_frame_unref(frame);
                            if (!running_.load() || paused_.load() || hasSeek_.load()) break;
                        }
                    }
                    av_packet_unref(packet);
                } else {
                    av_packet_unref(packet);
                }
            } else {
                // 读完了：先让音频把缓冲里剩下的播完，再把画面停在最后一帧
                if (audioReady && audioStarted && !audioDrained) {
                    const bool stillPlaying = audio_.queuedFrames() > 0 &&
                                              av_gettime_relative() - eofSinceUs < kEofDrainMs * 1000;
                    if (stillPlaying) {
                        tickClock();
                        updatePosition();
                        std::this_thread::sleep_for(std::chrono::milliseconds(10));
                        continue;
                    }
                    audio_.pause();
                    audioDrained = true;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }

            updatePosition();
        }
    }

    // ---------------------------------------------------------------- 收尾
    syncAudioError();
    audio_.close();
    if (swr != nullptr) swr_free(&swr);
    if (sws != nullptr) sws_freeContext(sws);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&audioCtx);
    avcodec_free_context(&videoCtx);
    avformat_close_input(&format);
    // 顺序要紧：format 关掉之后才能还 AVIO（还完 format->pb 就是野指针了）
    closeSourceAvio(&sourceAvio);
}

}  // namespace mediagate
