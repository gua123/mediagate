#include "first_frame.h"

#include <android/log.h>
#include <android/native_window.h>

#include <chrono>
#include <cstring>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

#include "surface_renderer.h"

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

std::string avError(int code) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, buf, sizeof(buf));
    return std::string(buf);
}

}  // namespace

/**
 * 由 JNI 层提供的"当前渲染目标"（一套引擎只有一个）。
 *
 * 放在这里而不是让 first_frame 自己持有：渲染目标的**所有权**归 SurfaceRenderer，
 * 这里只借用，避免两处各存一份指针导致释放时机打架。
 */
SurfaceRenderer& globalSurfaceRenderer();

FirstFrameResult renderFirstFrame(const std::string& path) {
    FirstFrameResult result;
    const auto started = std::chrono::steady_clock::now();

    if (!globalSurfaceRenderer().hasWindow()) {
        result.error = "还没有绑定画面（Surface 为空）";
        return result;
    }

    AVFormatContext* format = nullptr;
    int ret = avformat_open_input(&format, path.c_str(), nullptr, nullptr);
    if (ret < 0 || format == nullptr) {
        result.error = "打不开这个文件（可能是格式不支持，或文件没有读权限）：" + avError(ret);
        return result;
    }

    const AVCodec* decoder = nullptr;
    const int videoIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &decoder, 0);
    if (videoIndex < 0 || decoder == nullptr) {
        result.error = "这个文件里没有能解码的视频流";
        avformat_close_input(&format);
        return result;
    }

    AVCodecContext* codecCtx = avcodec_alloc_context3(decoder);
    if (codecCtx == nullptr) {
        result.error = "内存不足，创建解码器失败";
        avformat_close_input(&format);
        return result;
    }
    ret = avcodec_parameters_to_context(codecCtx, format->streams[videoIndex]->codecpar);
    if (ret < 0) {
        result.error = "解码器参数拷贝失败：" + avError(ret);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&format);
        return result;
    }
    // 多线程软解：默认单线程会让大分辨率首帧明显变慢
    codecCtx->thread_count = 0;
    ret = avcodec_open2(codecCtx, decoder, nullptr);
    if (ret < 0) {
        result.error = "解码器打不开（这个编码可能本机不支持）：" + avError(ret);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&format);
        return result;
    }
    result.codec = decoder->name == nullptr ? "unknown" : decoder->name;

    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    SwsContext* sws = nullptr;
    std::vector<uint8_t> rgba;
    bool rendered = false;

    while (!rendered && av_read_frame(format, packet) >= 0) {
        if (packet->stream_index != videoIndex) {
            av_packet_unref(packet);
            continue;
        }
        ret = avcodec_send_packet(codecCtx, packet);
        av_packet_unref(packet);
        if (ret < 0) continue;
        while (avcodec_receive_frame(codecCtx, frame) == 0) {
            const int srcW = frame->width;
            const int srcH = frame->height;
            if (srcW <= 0 || srcH <= 0) {
                av_frame_unref(frame);
                continue;
            }
            int winW = 0;
            int winH = 0;
            globalSurfaceRenderer().windowSize(&winW, &winH);
            if (winW <= 0 || winH <= 0) {
                result.error = "画面尺寸为 0（Surface 还没准备好）";
                av_frame_unref(frame);
                break;
            }
            // 保持宽高比"适应"（与项目一贯的 FIT 口径一致：宁可留黑边也不裁画面）
            const double scale = std::min(static_cast<double>(winW) / srcW, static_cast<double>(winH) / srcH);
            int dstW = static_cast<int>(srcW * scale);
            int dstH = static_cast<int>(srcH * scale);
            if (dstW <= 0 || dstH <= 0) {
                result.error = "算不出目标画面尺寸";
                av_frame_unref(frame);
                break;
            }
            if (sws == nullptr) {
                sws = sws_getContext(srcW, srcH, static_cast<AVPixelFormat>(frame->format),
                                     dstW, dstH, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
            }
            if (sws == nullptr) {
                result.error = "像素格式转换器创建失败";
                av_frame_unref(frame);
                break;
            }
            rgba.assign(static_cast<size_t>(dstW) * dstH * 4, 0);
            uint8_t* dstData[4] = {rgba.data(), nullptr, nullptr, nullptr};
            int dstStride[4] = {dstW * 4, 0, 0, 0};
            sws_scale(sws, frame->data, frame->linesize, 0, srcH, dstData, dstStride);
            if (globalSurfaceRenderer().blitRgba(rgba.data(), dstW, dstH)) {
                rendered = true;
                result.width = dstW;
                result.height = dstH;
            } else {
                result.error = "把画面写到屏幕失败（Surface 可能已经失效）";
            }
            av_frame_unref(frame);
            break;
        }
    }

    if (sws != nullptr) sws_freeContext(sws);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codecCtx);
    avformat_close_input(&format);

    if (!rendered && result.error.empty()) {
        result.error = "读到文件末尾也没解出画面（文件可能不完整）";
    }
    result.ok = rendered;
    const auto finished = std::chrono::steady_clock::now();
    result.decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(finished - started).count();
    __android_log_print(ANDROID_LOG_INFO, kTag, "首帧：%s codec=%s %dx%d 用时 %lld ms",
                        rendered ? "成功" : "失败", result.codec.c_str(), result.width, result.height,
                        static_cast<long long>(result.decodeMs));
    return result;
}

}  // namespace mediagate
