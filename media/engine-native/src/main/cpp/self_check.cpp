#include "self_check.h"

#include <android/log.h>

#include <chrono>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include "audio_output.h"

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

/** 真开一次解码器：找得到 + 能 alloc + 能 open 才算这条链路可用。 */
bool tryDecoder(const char* name, std::string* error) {
    const AVCodec* codec = avcodec_find_decoder_by_name(name);
    if (codec == nullptr) {
        *error = std::string("找不到 ") + name + " 解码器（这次构建缺解码能力）";
        return false;
    }
    AVCodecContext* context = avcodec_alloc_context3(codec);
    if (context == nullptr) {
        *error = std::string("分配 ") + name + " 解码器失败（内存不足）";
        return false;
    }
    const int opened = avcodec_open2(context, codec, nullptr);
    avcodec_free_context(&context);
    if (opened < 0) {
        *error = std::string(name) + " 解码器打不开（本机解码能力异常）";
        return false;
    }
    return true;
}

/** 建一次缩放器（1 像素就够：只为验证这条链路能初始化）。 */
bool trySws(std::string* error) {
    SwsContext* sws = sws_getContext(2, 2, AV_PIX_FMT_YUV420P, 2, 2, AV_PIX_FMT_RGBA,
                                     SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (sws == nullptr) {
        *error = "画面缩放器建不起来（swscale 初始化失败）";
        return false;
    }
    sws_freeContext(sws);
    return true;
}

}  // namespace

std::string selfCheck() {
    std::string error;

    // ---- FFmpeg：解码器 ----
    if (!tryDecoder("h264", &error)) return "错误：" + error;
    if (!tryDecoder("aac", &error)) return "错误：" + error;

    // ---- FFmpeg：缩放 / 重采样 ----
    if (!trySws(&error)) return "错误：" + error;
    AVChannelLayout out{};
    AVChannelLayout in{};
    av_channel_layout_default(&out, 2);
    av_channel_layout_default(&in, 2);
    SwrContext* swr = nullptr;
    const int allocated = swr_alloc_set_opts2(&swr, &out, AV_SAMPLE_FMT_FLT, 48000,
                                              &in, AV_SAMPLE_FMT_S16, 44100, 0, nullptr);
    av_channel_layout_uninit(&out);
    av_channel_layout_uninit(&in);
    if (allocated < 0 || swr == nullptr || swr_init(swr) < 0) {
        if (swr != nullptr) swr_free(&swr);
        return "错误：音频重采样器建不起来（swresample 初始化失败）";
    }
    swr_free(&swr);

    // ---- AAudio：开流 → 起播 → 写一小段静音 → 停 ----
    // 这一步是自研引擎最容易把进程带走的地方（独立进程探针就是为了它）。
    AudioOutput audio;
    std::string audioError;
    if (!audio.open(48000, 2, &audioError)) return "错误：" + audioError;
    audio.start();
    if (!audio.error().empty()) return "错误：" + audio.error();
    std::vector<float> silence(static_cast<size_t>(4800) * 2, 0.0f);  // 100 ms 静音
    size_t written = 0;
    for (int attempt = 0; attempt < 50 && written < 4800; ++attempt) {
        written += audio.write(silence.data() + written * 2, 4800 - written, 0);
        if (written < 4800) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }
    audio.stop();
    audio.close();
    if (written == 0) return "错误：音频设备打不开（写不进数据）";

    __android_log_print(ANDROID_LOG_INFO, kTag, "自检通过：FFmpeg %s；AAudio %d Hz/%d 声道",
                        av_version_info(), audio.sampleRate(), audio.channels());
    return std::string("ok|FFmpeg ") + av_version_info() + "；解码 h264/aac 正常；音频 " +
           std::to_string(audio.sampleRate()) + " Hz/" + std::to_string(audio.channels()) + " 声道正常";
}

}  // namespace mediagate
