#ifndef MEDIAGATE_ENGINE_CORE_H
#define MEDIAGATE_ENGINE_CORE_H

#include <string>

namespace mediagate {

/**
 * 引擎内核的构建信息与能力声明（M1 骨架）。
 *
 * **2026-10-06 用户拍板**：「视频引擎只采用一套，不要来回切换，自研一套视频播放引擎，
 * 尽可能适合全部视频文件类型」——所以这里不叫 Media3Engine / VlcEngine，就叫 Engine：
 * **整套播放只有它一套**；"硬解优先、软解兜底"是它内部的两条解码路径，不是两个引擎。
 */
struct EngineInfo {
    std::string name;
    std::string version;
    /** 内部解码路径（将来由 FFmpeg + MediaCodec 支撑）。 */
    std::string decodePaths;
};

EngineInfo engineInfo();

}  // namespace mediagate

#endif  // MEDIAGATE_ENGINE_CORE_H
