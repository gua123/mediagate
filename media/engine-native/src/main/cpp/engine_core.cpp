#include "engine_core.h"

namespace mediagate {

EngineInfo engineInfo() {
    EngineInfo info;
    info.name = "mediagate-engine";
    // M1 骨架版本：链路通了才往下长功能
    info.version = "0.0.1-skeleton";
    // 计划中的两条内部路径（**同一套引擎内部**，用户不需要切换）
    info.decodePaths = "mediacodec(hw) -> ffmpeg(sw)";
    return info;
}

}  // namespace mediagate
