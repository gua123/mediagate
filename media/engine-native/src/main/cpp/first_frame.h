#ifndef MEDIAGATE_FIRST_FRAME_H
#define MEDIAGATE_FIRST_FRAME_H

#include <string>

namespace mediagate {

struct FirstFrameResult {
    bool ok = false;
    std::string error;      // 中文原因
    int width = 0;
    int height = 0;
    std::string codec;      // 实际用的解码器（后面要看是硬解还是软解）
    int64_t decodeMs = 0;   // 解出这一帧花了多久（用来盯"起播速度"）
};

/**
 * 解出并显示**第一帧**（M2 的核心：解复用 → 解码 → 转 RGBA → 写 Surface）。
 *
 * 只做"第一帧"是有意的：用户的体感是"画面出来了"，先把这个闭环打通、
 * 再往上长（音频、同步、seek、连续播放）。
 *
 * 需要先 NativeEngine.setSurface(...) 绑定渲染目标；没绑定时如实报错。
 */
FirstFrameResult renderFirstFrame(const std::string& path);

}  // namespace mediagate

#endif  // MEDIAGATE_FIRST_FRAME_H
