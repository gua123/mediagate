#ifndef MEDIAGATE_SUBTITLE_EXTRACT_H
#define MEDIAGATE_SUBTITLE_EXTRACT_H

#include <string>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
}

namespace mediagate {

/** 容器里的一条字幕轨（**只描述，不解码**）。 */
struct SubtitleTrack {
    int index = -1;              // 流下标（抽取时用它）
    std::string codec;           // FFmpeg 的编码名（subrip / ass / mov_text / hdmv_pgs_subtitle…）
    std::string language;        // 容器里的语言标记（可能是空）
    std::string title;           // 容器里的轨名（可能是空）
    bool textBased = false;      // 能不能走"读 payload 转文本"这条路（图像类为 false）
};

/** 列出容器里的字幕轨（顺序＝流顺序；没有字幕轨返回空）。 */
std::vector<SubtitleTrack> listSubtitleTracks(AVFormatContext* format);

/**
 * 把某条字幕轨抽成 **SRT 文本**（App 已有的解析器直接能吃）。
 *
 * 为什么输出统一是 SRT：本项目 FFmpeg 没编字幕解码器，文本类字幕靠"读 packet 纯文本 payload"，
 * 统一产出 SRT 就不必再给 App 加一种格式；ASS 的样式与覆盖标签在这里被剥掉（App 的 SRT 解析器不需要）。
 *
 * @param maxCues 最多抽多少条（防超长片头把内存吃满；0 = 不限）
 * @param error   失败时的中文原因（走不了这条路的图像字幕也在这里说清）
 */
std::string extractSubtitleAsSrt(AVFormatContext* format, int streamIndex, int maxCues, std::string* error);

}  // namespace mediagate

#endif  // MEDIAGATE_SUBTITLE_EXTRACT_H
