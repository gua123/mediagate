#ifndef MEDIAGATE_MEDIA_PROBE_H
#define MEDIAGATE_MEDIA_PROBE_H

#include <string>
#include <vector>

namespace mediagate {

/** 一条流的基本信息。 */
struct StreamInfo {
    int index = -1;
    std::string kind;      // video / audio / subtitle / other
    std::string codec;     // h264 / hevc / aac …
    int width = 0;
    int height = 0;
    int sampleRate = 0;
    int channels = 0;
    int64_t bitRate = 0;
};

/** 容器探测结果。 */
struct ProbeResult {
    bool ok = false;
    std::string error;                 // 失败原因（中文，直接给界面用）
    std::string format;                // 容器名，如 mov,mp4,m4a,3gp,3g2,mj2
    int64_t durationMs = 0;
    int64_t sizeBytes = 0;
    int64_t bitRate = 0;
    int videoStreamIndex = -1;
    std::vector<StreamInfo> streams;
};

/**
 * 只读探测一个媒体文件（M2 的第一个能力）。
 *
 * 为什么先做它：① 它是**真实调用 FFmpeg** 的最小闭环（能证明 libavformat 在真机上跑得起来）；
 * ② 它本身就是引擎需要的东西——拿到时长/关键帧表才好做"按索引精确预取"。
 *
 * 失败一律转成中文原因（界面红线：不把库的英文堆栈丢给用户）。
 */
ProbeResult probeMedia(const std::string& path);

}  // namespace mediagate

#endif  // MEDIAGATE_MEDIA_PROBE_H
