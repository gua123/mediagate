#include "native_player.h"

#include <android/log.h>

#include <chrono>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>
}

#include "surface_renderer.h"

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

/** 暂停/停止时的轮询间隔：够了，不要再密（省电）。 */
constexpr auto kIdlePoll = std::chrono::milliseconds(5);

/** 单帧最多等多久（防止时间戳异常时整段卡住）。 */
constexpr int64_t kMaxFrameWaitMs = 200;

}  // namespace

/**
 * 由 JNI 层提供（见 engine_jni.cpp）。
 *
 * ⚠️ 必须在**匿名 namespace 之外**声明：放在里面会变成内部链接，
 * 链接期报 `undefined symbol: mediagate::(anonymous namespace)::globalSurfaceRenderer()`
 *（本轮 M3 真踩了一次）。
 */
SurfaceRenderer& globalSurfaceRenderer();

NativePlayer::~NativePlayer() {
    stop();
}

void NativePlayer::closeLocked() {
    // 这里只清"元信息"；FFmpeg 资源在 worker 线程里成对创建与释放（避免跨线程释放）
    std::lock_guard<std::mutex> lock(mutex_);
    path_.clear();
    codecName_.clear();
    durationMs_ = 0;
    videoWidth_ = 0;
    videoHeight_ = 0;
}

bool NativePlayer::open(const std::string& path, std::string* error) {
    if (running_.load()) stop();
    std::lock_guard<std::mutex> lock(mutex_);
    path_ = path;
    error_.clear();
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
    std::lock_guard<std::mutex> lock(mutex_);
    snapshot.durationMs = durationMs_;
    snapshot.videoWidth = videoWidth_;
    snapshot.videoHeight = videoHeight_;
    snapshot.codec = codecName_;
    snapshot.error = error_;
    return snapshot;
}

/**
 * 帧循环（工作线程）。
 *
 * 顺序：打开 → 解复用 → 解码 → **按 pts 节流** → 送屏；期间响应 暂停 / seek / 停止。
 */
void NativePlayer::threadLoop() {
    AVFormatContext* format = nullptr;
    AVCodecContext* codecCtx = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    SwsContext* sws = nullptr;
    std::vector<uint8_t> rgba;
    int videoIndex = -1;
    AVRational timeBase{1, 1000};

    auto fail = [&](const std::string& message) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "自研播放失败：%s", message.c_str());
        {
            std::lock_guard<std::mutex> lock(mutex_);
            error_ = message;
        }
        state_.store(static_cast<int>(PlayerState::ERROR));
        running_.store(false);
    };

    std::string path;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        path = path_;
    }
    if (path.empty()) {
        fail("没有要播放的文件");
        return;
    }

    if (avformat_open_input(&format, path.c_str(), nullptr, nullptr) < 0 || format == nullptr) {
        fail("打不开这个文件");
        return;
    }
    if (avformat_find_stream_info(format, nullptr) < 0) {
        fail("读不出媒体信息（文件可能损坏或还没下完）");
        avformat_close_input(&format);
        return;
    }
    const AVCodec* decoder = nullptr;
    videoIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &decoder, 0);
    if (videoIndex < 0 || decoder == nullptr) {
        fail("这个文件里没有能解码的视频流");
        avformat_close_input(&format);
        return;
    }
    codecCtx = avcodec_alloc_context3(decoder);
    if (codecCtx == nullptr ||
        avcodec_parameters_to_context(codecCtx, format->streams[videoIndex]->codecpar) < 0 ||
        avcodec_open2(codecCtx, decoder, nullptr) < 0) {
        fail("解码器打不开（这个编码可能本机不支持）");
        if (codecCtx != nullptr) avcodec_free_context(&codecCtx);
        avformat_close_input(&format);
        return;
    }
    codecCtx->thread_count = 0;
    timeBase = format->streams[videoIndex]->time_base;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        codecName_ = decoder->name == nullptr ? "unknown" : decoder->name;
        durationMs_ = format->duration == AV_NOPTS_VALUE ? 0 : format->duration / (AV_TIME_BASE / 1000);
        videoWidth_ = codecCtx->width;
        videoHeight_ = codecCtx->height;
    }

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    const int64_t clockStartUs = av_gettime_relative();
    int64_t renderedPtsUs = 0;
    bool eof = false;

    while (running_.load()) {
        if (paused_.load()) {
            // 暂停：把"时钟"一起暂停（否则继续播时会瞬间追帧）
            std::this_thread::sleep_for(kIdlePoll);
            continue;
        }
        if (hasSeek_.exchange(false)) {
            const int64_t targetMs = seekTargetMs_.load();
            const int64_t ts = av_rescale_q(targetMs, AVRational{1, 1000}, timeBase);
            // 按关键帧往回找：解出来的第一帧才是能显示的完整帧
            if (av_seek_frame(format, videoIndex, ts, AVSEEK_FLAG_BACKWARD) >= 0) {
                avcodec_flush_buffers(codecCtx);
                eof = false;
                positionMs_.store(targetMs);
            }
        }

        if (!eof) {
            const int readRet = av_read_frame(format, packet);
            if (readRet < 0) {
                eof = true;
            } else if (packet->stream_index == videoIndex) {
                if (avcodec_send_packet(codecCtx, packet) >= 0) {
                    while (avcodec_receive_frame(codecCtx, frame) == 0) {
                        const int64_t ptsMs = frame->pts == AV_NOPTS_VALUE
                            ? positionMs_.load()
                            : av_rescale_q(frame->pts, timeBase, AVRational{1, 1000});
                        positionMs_.store(ptsMs);
                        // 按时间戳节流：早了就睡一会儿（但把睡眠切成小片，随时响应暂停/seek/停止）
                        const int64_t targetUs = ptsMs * 1000;
                        while (running_.load() && !paused_.load() && !hasSeek_.load()) {
                            const int64_t elapsedUs = av_gettime_relative() - clockStartUs;
                            const int64_t waitUs = targetUs - elapsedUs;
                            if (waitUs <= 0) break;
                            const int64_t sliceMs = std::min<int64_t>(waitUs / 1000, kMaxFrameWaitMs);
                            std::this_thread::sleep_for(std::chrono::milliseconds(std::max<int64_t>(sliceMs, 1)));
                        }
                        if (!running_.load()) break;

                        const int srcW = frame->width;
                        const int srcH = frame->height;
                        int winW = 0;
                        int winH = 0;
                        globalSurfaceRenderer().windowSize(&winW, &winH);
                        if (srcW > 0 && srcH > 0 && winW > 0 && winH > 0) {
                            const double scale = std::min(static_cast<double>(winW) / srcW,
                                                          static_cast<double>(winH) / srcH);
                            const int dstW = std::max(1, static_cast<int>(srcW * scale));
                            const int dstH = std::max(1, static_cast<int>(srcH * scale));
                            if (sws == nullptr || rgba.empty()) {
                                sws = sws_getContext(srcW, srcH, static_cast<AVPixelFormat>(frame->format),
                                                     dstW, dstH, AV_PIX_FMT_RGBA, SWS_BILINEAR,
                                                     nullptr, nullptr, nullptr);
                                rgba.assign(static_cast<size_t>(dstW) * dstH * 4, 0);
                            }
                            if (sws != nullptr) {
                                uint8_t* dstData[4] = {rgba.data(), nullptr, nullptr, nullptr};
                                int dstStride[4] = {dstW * 4, 0, 0, 0};
                                sws_scale(sws, frame->data, frame->linesize, 0, srcH, dstData, dstStride);
                                if (globalSurfaceRenderer().blitRgba(rgba.data(), dstW, dstH)) {
                                    frames_.fetch_add(1);
                                    renderedPtsUs = targetUs;
                                }
                            }
                        }
                        av_frame_unref(frame);
                        if (eof) break;
                    }
                }
                av_packet_unref(packet);
            } else {
                av_packet_unref(packet);
            }
        } else {
            // 读完了：停在最后一帧（等停止或 seek），不要忙等
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            (void)renderedPtsUs;
        }
    }

    if (sws != nullptr) sws_freeContext(sws);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codecCtx);
    avformat_close_input(&format);
}

}  // namespace mediagate
