/**
 * subtitle_text.h 的开发机单测（**不需要设备、不需要 FFmpeg**）。
 *
 * 跑法：bash scripts/test-engine-native-host.sh
 *
 * 为什么值得单测：内嵌字幕这条路**绕开了 FFmpeg 的字幕解码器**（本项目根本没编），
 * 改成直接读 packet 的纯文本 payload 再自己拼 SRT —— 剥包装的规则（mov_text 的长度前缀、
 * ASS 的 Dialogue 前缀与 {\...} 覆盖标签、CRLF 归一）全都是纯字符串逻辑，
 * 一旦搞错，用户在真机上看到的是"字幕里有花括号""时间轴错位"，极难定位。
 */
#include <cstdio>
#include <string>

#include "subtitle_text.h"

namespace {

int g_checks = 0;
int g_failures = 0;

#define CHECK(cond)                                                  \
    do {                                                             \
        ++g_checks;                                                  \
        if (!(cond)) {                                               \
            ++g_failures;                                            \
            std::printf("  FAIL 第 %d 行： %s\n", __LINE__, #cond); \
        }                                                            \
    } while (0)

#define CHECK_STR(actual, expected)                                            \
    do {                                                                       \
        ++g_checks;                                                            \
        const std::string a_ = (actual);                                       \
        const std::string e_ = (expected);                                     \
        if (a_ != e_) {                                                        \
            ++g_failures;                                                      \
            std::printf("  FAIL 第 %d 行：期望 [%s]，实际 [%s]\n", __LINE__, \
                        e_.c_str(), a_.c_str());                               \
        }                                                                      \
    } while (0)

using mediagate::SubtitleFormat;

void testFormatOf() {
    CHECK(mediagate::subtitleFormatOf("subrip") == SubtitleFormat::SubRip);
    CHECK(mediagate::subtitleFormatOf("SRT") == SubtitleFormat::SubRip);
    CHECK(mediagate::subtitleFormatOf("ass") == SubtitleFormat::Ass);
    CHECK(mediagate::subtitleFormatOf("SSA") == SubtitleFormat::Ssa);
    CHECK(mediagate::subtitleFormatOf("mov_text") == SubtitleFormat::MovText);
    CHECK(mediagate::subtitleFormatOf("webvtt") == SubtitleFormat::WebVtt);
    // 图像类字幕：**不能**当文本处理（真跑下去会产出乱码）
    CHECK(mediagate::subtitleFormatOf("hdmv_pgs_subtitle") == SubtitleFormat::Other);
    CHECK(mediagate::subtitleFormatOf("dvd_subtitle") == SubtitleFormat::Other);
    CHECK(mediagate::subtitleFormatOf("") == SubtitleFormat::Other);
    CHECK(mediagate::isTextSubtitle(SubtitleFormat::SubRip));
    CHECK(!mediagate::isTextSubtitle(SubtitleFormat::Other));
}

void testTimestamp() {
    CHECK_STR(mediagate::srtTimestamp(0), "00:00:00,000");
    CHECK_STR(mediagate::srtTimestamp(1234), "00:00:01,234");
    CHECK_STR(mediagate::srtTimestamp(1000), "00:00:01,000");
    CHECK_STR(mediagate::srtTimestamp(59999), "00:00:59,999");
    CHECK_STR(mediagate::srtTimestamp(60000), "00:01:00,000");
    CHECK_STR(mediagate::srtTimestamp(3600000), "01:00:00,000");
    CHECK_STR(mediagate::srtTimestamp(3661001), "01:01:01,001");
    // 坏时间戳（容器里真有负数）不许产出乱码
    CHECK_STR(mediagate::srtTimestamp(-5), "00:00:00,000");
    CHECK_STR(mediagate::srtTimestamp(25), "00:00:00,025");
}

void testSubRipPayload() {
    const std::string text = "Hello\r\nWorld";
    CHECK_STR(
        mediagate::subtitlePayloadToText(
            SubtitleFormat::SubRip,
            reinterpret_cast<const uint8_t*>(text.data()),
            static_cast<int>(text.size())),
        "Hello\nWorld");
    const std::string padded = "  \n 只留正文  \n ";
    CHECK_STR(
        mediagate::subtitlePayloadToText(
            SubtitleFormat::SubRip,
            reinterpret_cast<const uint8_t*>(padded.data()),
            static_cast<int>(padded.size())),
        "只留正文");
    CHECK_STR(mediagate::subtitlePayloadToText(SubtitleFormat::SubRip, nullptr, 0), "");
}

void testMovTextPayload() {
    // mov_text：2 字节大端长度前缀 + 正文
    std::string payload;
    payload.push_back(static_cast<char>(0x00));
    payload.push_back(static_cast<char>(0x05));
    payload += "Hello";
    CHECK_STR(
        mediagate::subtitlePayloadToText(
            SubtitleFormat::MovText,
            reinterpret_cast<const uint8_t*>(payload.data()),
            static_cast<int>(payload.size())),
        "Hello");
}

void testAssPayload() {
    const std::string line = "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\i1}Hi there";
    CHECK_STR(
        mediagate::subtitlePayloadToText(
            SubtitleFormat::Ass,
            reinterpret_cast<const uint8_t*>(line.data()),
            static_cast<int>(line.size())),
        "Hi there");
    // 没有 Dialogue 前缀时也要能把覆盖标签剥掉（有些封装直接给正文）
    const std::string bare = "{\\pos(1,2)}就这样";
    CHECK_STR(
        mediagate::subtitlePayloadToText(
            SubtitleFormat::Ssa,
            reinterpret_cast<const uint8_t*>(bare.data()),
            static_cast<int>(bare.size())),
        "就这样");
}

void testSrtCue() {
    CHECK_STR(
        mediagate::srtCue(2, 1000, 2000, "Hi"),
        "2\n00:00:01,000 --> 00:00:02,000\nHi\n\n");
    // 文本自带换行时不要多补一行
    CHECK_STR(
        mediagate::srtCue(1, 0, 500, "a\nb"),
        "1\n00:00:00,000 --> 00:00:00,500\na\nb\n\n");
}

}  // namespace

int main() {
    testFormatOf();
    testTimestamp();
    testSubRipPayload();
    testMovTextPayload();
    testAssPayload();
    testSrtCue();

    if (g_failures != 0) {
        std::printf("字幕文本单测失败：%d / %d\n", g_failures, g_checks);
        return 1;
    }
    std::printf("字幕文本：全部通过（%d 项断言）\n", g_checks);
    return 0;
}
