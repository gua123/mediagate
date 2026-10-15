#ifndef MEDIAGATE_SUBTITLE_TEXT_H
#define MEDIAGATE_SUBTITLE_TEXT_H

#include <cstdint>
#include <string>

namespace mediagate {

/**
 * 文本类字幕在容器里的编码（名字与 FFmpeg 的 codec name 对齐：subrip / ass / ssa / mov_text / webvtt）。
 *
 * 为什么自己认一遍而不是问 FFmpeg：**本项目的 FFmpeg 是按需 enable 的，一个字幕解码器都没编**
 * （实测 subrip/ass/mov_text/webvtt 的 decoder 符号全是 0）。而文本类字幕的 packet payload
 * **本来就是纯文本**，不需要解码器——直接读出来、剥掉包装、自己拼成 SRT 交给 App 已有的解析器就行。
 * 图像类字幕（hdmv_pgs / dvd_subtitle / dvb_subtitle）走不了这条路，如实标成 Other。
 */
enum class SubtitleFormat { SubRip, Ass, Ssa, MovText, WebVtt, Other };

/** codec name → 我们的分类（大小写不敏感；认不出就是 Other）。 */
inline SubtitleFormat subtitleFormatOf(const std::string& codecName) {
    std::string name;
    name.reserve(codecName.size());
    for (char ch : codecName) {
        name.push_back(static_cast<char>(ch >= 'A' && ch <= 'Z' ? ch - 'A' + 'a' : ch));
    }
    if (name == "subrip" || name == "srt" || name == "text") return SubtitleFormat::SubRip;
    if (name == "ass") return SubtitleFormat::Ass;
    if (name == "ssa") return SubtitleFormat::Ssa;
    if (name == "mov_text") return SubtitleFormat::MovText;
    if (name == "webvtt" || name == "vtt") return SubtitleFormat::WebVtt;
    return SubtitleFormat::Other;
}

/** 能不能直接读 payload 抽出来（图像类返回 false，界面上如实说"暂不支持"）。 */
inline bool isTextSubtitle(SubtitleFormat format) { return format != SubtitleFormat::Other; }

/** 毫秒 → SRT 时间戳（00:00:01,234）；负数按 0 算（容器里的坏时间戳不该产出一行乱码）。 */
inline std::string srtTimestamp(int64_t ms) {
    if (ms < 0) ms = 0;
    const int64_t totalSeconds = ms / 1000;
    const int64_t millis = ms % 1000;
    const int64_t seconds = totalSeconds % 60;
    const int64_t minutes = (totalSeconds / 60) % 60;
    const int64_t hours = totalSeconds / 3600;
    auto two = [](int64_t value) {
        std::string text = std::to_string(value);
        return text.size() < 2 ? std::string(2 - text.size(), '0') + text : text;
    };
    std::string millisText = std::to_string(millis);
    while (millisText.size() < 3) millisText = "0" + millisText;
    return two(hours) + ":" + two(minutes) + ":" + two(seconds) + "," + millisText;
}

namespace detail {

/** 去掉 ASS 的 {\...} 覆盖标签（样式指令，App 的 SRT 解析器不需要）。 */
inline std::string stripAssTags(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    int depth = 0;
    for (char ch : text) {
        if (ch == '{') {
            ++depth;
            continue;
        }
        if (ch == '}') {
            if (depth > 0) --depth;
            continue;
        }
        if (depth == 0) out.push_back(ch);
    }
    return out;
}

/** 去掉首尾空白与行尾的 \r（容器里的 CRLF 会污染 SRT）。 */
inline std::string trimAndNormalize(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    for (char ch : text) {
        if (ch != '\r') out.push_back(ch);
    }
    const auto begin = out.find_first_not_of(" \t\n");
    if (begin == std::string::npos) return std::string();
    const auto end = out.find_last_not_of(" \t\n");
    return out.substr(begin, end - begin + 1);
}

}  // namespace detail

/**
 * 一个字幕 packet 的 payload → 纯文本。
 *
 * - mov_text：payload 前面有 2 字节大端长度（有时还会重复出现），先剥掉；
 * - ass/ssa：行首常见 "Dialogue: 0,0:00:01.00,..."，把前缀与逗号后的第 9 个字段之后的正文取出来；
 * - subrip/webvtt：原样（只做 CRLF 与首尾空白归一）。
 */
inline std::string subtitlePayloadToText(SubtitleFormat format, const uint8_t* data, int size) {
    if (data == nullptr || size <= 0) return std::string();
    std::string raw(reinterpret_cast<const char*>(data), static_cast<size_t>(size));

    if (format == SubtitleFormat::MovText) {
        // 2 字节长度前缀（可能多次），剥到剩纯文本
        size_t offset = 0;
        while (offset + 2 <= raw.size()) {
            const int length = (static_cast<unsigned char>(raw[offset]) << 8) |
                               static_cast<unsigned char>(raw[offset + 1]);
            if (length <= 0 || offset + 2 + static_cast<size_t>(length) > raw.size()) break;
            raw = raw.substr(offset + 2, static_cast<size_t>(length));
            offset = 0;
            // 剥一层就够（嵌套长度前缀极罕见）
            break;
        }
    }

    if (format == SubtitleFormat::Ass || format == SubtitleFormat::Ssa) {
        const std::string prefix = "dialogue:";
        std::string lowered;
        lowered.reserve(raw.size());
        for (char ch : raw) {
            lowered.push_back(static_cast<char>(ch >= 'A' && ch <= 'Z' ? ch - 'A' + 'a' : ch));
        }
        const auto at = lowered.find(prefix);
        if (at != std::string::npos) {
            std::string body = raw.substr(at + prefix.size());
            // Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            int commas = 0;
            size_t cut = std::string::npos;
            for (size_t i = 0; i < body.size(); ++i) {
                if (body[i] == ',') {
                    ++commas;
                    if (commas == 9) {
                        cut = i + 1;
                        break;
                    }
                }
            }
            if (cut != std::string::npos && cut <= body.size()) body = body.substr(cut);
            return detail::trimAndNormalize(detail::stripAssTags(body));
        }
    }
    return detail::trimAndNormalize(detail::stripAssTags(raw));
}

/** 一条 cue 的 SRT 块（序号 / 时间轴 / 文本 / 空行）。 */
inline std::string srtCue(int index, int64_t startMs, int64_t endMs, const std::string& text) {
    std::string out = std::to_string(index) + "\n";
    out += srtTimestamp(startMs) + " --> " + srtTimestamp(endMs) + "\n";
    out += text;
    if (out.empty() || out.back() != '\n') out += "\n";
    out += "\n";
    return out;
}

}  // namespace mediagate

#endif  // MEDIAGATE_SUBTITLE_TEXT_H
