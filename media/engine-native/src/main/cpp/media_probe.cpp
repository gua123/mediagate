#include "media_probe.h"

#include <android/log.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

std::string codecName(AVCodecID id) {
    const char* name = avcodec_get_name(id);
    return name == nullptr ? "unknown" : name;
}

const char* streamKind(AVMediaType type) {
    switch (type) {
        case AVMEDIA_TYPE_VIDEO: return "video";
        case AVMEDIA_TYPE_AUDIO: return "audio";
        case AVMEDIA_TYPE_SUBTITLE: return "subtitle";
        default: return "other";
    }
}

}  // namespace

ProbeResult probeMedia(const std::string& path) {
    ProbeResult result;
    if (path.empty()) {
        result.error = "文件路径为空";
        return result;
    }

    AVFormatContext* format = nullptr;
    // FFmpeg 是 C API：错误码要转成中文原因，不把 AVERROR 数字丢给用户
    const int openRet = avformat_open_input(&format, path.c_str(), nullptr, nullptr);
    if (openRet < 0 || format == nullptr) {
        char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(openRet, buf, sizeof(buf));
        __android_log_print(ANDROID_LOG_WARN, kTag, "avformat_open_input 失败: %s", buf);
        result.error = "打不开这个文件（可能是格式不支持，或文件没有读权限）";
        return result;
    }

    const int infoRet = avformat_find_stream_info(format, nullptr);
    if (infoRet < 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "avformat_find_stream_info 失败: %d", infoRet);
        result.error = "读不出这个文件的媒体信息（文件可能损坏或还没下完）";
        avformat_close_input(&format);
        return result;
    }

    const char* formatName = format->iformat != nullptr ? format->iformat->name : nullptr;
    result.format = formatName == nullptr ? "unknown" : formatName;
    result.durationMs = format->duration == AV_NOPTS_VALUE ? 0 : format->duration / (AV_TIME_BASE / 1000);
    result.sizeBytes = format->pb != nullptr ? avio_size(format->pb) : 0;
    result.bitRate = format->bit_rate;

    for (unsigned i = 0; i < format->nb_streams; ++i) {
        AVStream* stream = format->streams[i];
        if (stream == nullptr || stream->codecpar == nullptr) continue;
        const AVCodecParameters* par = stream->codecpar;
        StreamInfo info;
        info.index = static_cast<int>(i);
        info.kind = streamKind(par->codec_type);
        info.codec = codecName(par->codec_id);
        info.width = par->width;
        info.height = par->height;
        info.sampleRate = par->sample_rate;
        info.channels = par->ch_layout.nb_channels;
        info.bitRate = par->bit_rate;
        result.streams.push_back(info);
    }

    // 选"最佳视频流"（FFmpeg 自己会参考分辨率/码率，比我们瞎挑准）
    result.videoStreamIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (result.videoStreamIndex < 0) result.videoStreamIndex = -1;

    result.ok = true;
    avformat_close_input(&format);
    return result;
}

}  // namespace mediagate
