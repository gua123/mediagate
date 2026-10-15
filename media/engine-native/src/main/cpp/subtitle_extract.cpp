#include "subtitle_extract.h"

#include <string>

#include "subtitle_text.h"

namespace mediagate {
namespace {

/** 流元数据里的一项（拿不到返回空串）。 */
std::string metadataOf(AVStream* stream, const char* key) {
    if (stream == nullptr) return std::string();
    const AVDictionaryEntry* entry = av_dict_get(stream->metadata, key, nullptr, 0);
    return entry != nullptr && entry->value != nullptr ? std::string(entry->value) : std::string();
}

/** packet 的时间戳（流时基）→ 毫秒；拿不到返回 -1。 */
int64_t toMillis(int64_t timestamp, AVRational timeBase) {
    if (timestamp == AV_NOPTS_VALUE) return -1;
    return av_rescale_q(timestamp, timeBase, AVRational{1, 1000});
}

}  // namespace

std::vector<SubtitleTrack> listSubtitleTracks(AVFormatContext* format) {
    std::vector<SubtitleTrack> tracks;
    if (format == nullptr) return tracks;
    for (unsigned i = 0; i < format->nb_streams; ++i) {
        AVStream* stream = format->streams[i];
        if (stream == nullptr || stream->codecpar == nullptr) continue;
        if (stream->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) continue;
        SubtitleTrack track;
        track.index = static_cast<int>(i);
        const char* codecName = avcodec_get_name(stream->codecpar->codec_id);
        track.codec = codecName != nullptr ? codecName : "unknown";
        track.language = metadataOf(stream, "language");
        track.title = metadataOf(stream, "title");
        track.textBased = isTextSubtitle(subtitleFormatOf(track.codec));
        tracks.push_back(track);
    }
    return tracks;
}

std::string extractSubtitleAsSrt(AVFormatContext* format, int streamIndex, int maxCues, std::string* error) {
    if (error != nullptr) error->clear();
    if (format == nullptr) {
        if (error != nullptr) *error = "文件没打开";
        return std::string();
    }
    if (streamIndex < 0 || static_cast<unsigned>(streamIndex) >= format->nb_streams) {
        if (error != nullptr) *error = "字幕轨下标越界";
        return std::string();
    }
    AVStream* stream = format->streams[streamIndex];
    if (stream == nullptr || stream->codecpar == nullptr ||
        stream->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
        if (error != nullptr) *error = "这一条不是字幕轨";
        return std::string();
    }
    const std::string codecName = avcodec_get_name(stream->codecpar->codec_id);
    const SubtitleFormat subtitleFormat = subtitleFormatOf(codecName);
    if (!isTextSubtitle(subtitleFormat)) {
        if (error != nullptr) {
            *error = "内嵌字幕是图像格式（" + codecName + "），暂不支持；外挂字幕不受影响";
        }
        return std::string();
    }

    // 从文件开头读（字幕轨通常整条都在前半段；读到条数够了就停）
    av_seek_frame(format, streamIndex, 0, AVSEEK_FLAG_BACKWARD);
    AVPacket* packet = av_packet_alloc();
    if (packet == nullptr) {
        if (error != nullptr) *error = "内存不足";
        return std::string();
    }

    std::string srt;
    int cues = 0;
    while (true) {
        const int read = av_read_frame(format, packet);
        if (read < 0) break;  // EOF 或读错：已抽到的照样返回，字幕不全也比"什么都没有"强
        if (packet->stream_index != streamIndex) {
            av_packet_unref(packet);
            continue;
        }
        const std::string text = subtitlePayloadToText(
            subtitleFormat, packet->data, packet->size);
        if (!text.empty()) {
            int64_t startMs = toMillis(packet->pts, stream->time_base);
            if (startMs < 0) startMs = toMillis(packet->dts, stream->time_base);
            if (startMs < 0) startMs = 0;
            int64_t durationMs = toMillis(packet->duration, stream->time_base);
            if (durationMs <= 0) durationMs = 2000;  // 容器没给时长：给 2 秒（比 0 秒好，至少看得见）
            ++cues;
            srt += srtCue(cues, startMs, startMs + durationMs, text);
        }
        av_packet_unref(packet);
        if (maxCues > 0 && cues >= maxCues) break;
    }
    av_packet_free(&packet);

    if (cues == 0 && error != nullptr) {
        *error = "这条字幕轨里没读到文本（可能是空轨）";
    }
    return srt;
}

}  // namespace mediagate
