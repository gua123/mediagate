#include "engine_core.h"

namespace mediagate {

EngineInfo engineInfo() {
    EngineInfo info;
    info.name = "mediagate-engine";
    // 版本跟着里程碑走（诊断卡会显示它，方便对着截图定位问题）
    info.version = "0.4.0-m4";
    // 计划中的两条内部路径（**同一套引擎内部**，用户不需要切换）
    info.decodePaths = "mediacodec(hw) -> ffmpeg(sw)";
    return info;
}

}  // namespace mediagate
// FFmpeg 是 C 库：**显式套一层 extern "C"**（头文件自己也有，但这里再包一层最稳，
// 避免任何包含顺序/宏状态导致 C++ 名字修饰，链接时报 "undefined symbol: av_version_info()"）。
#ifdef MEDIAGATE_WITH_FFMPEG
extern "C" {
#include <libavutil/avutil.h>
}
#endif

namespace mediagate {

/**
 * 自研 FFmpeg 的版本串（**编译期证据**：能编过就说明头文件与 .so 都对上了）。
 *
 * 没链接 FFmpeg 时如实返回中文说明——引擎能力缺失要能自述，而不是假装正常。
 */
std::string ffmpegVersion() {
#ifdef MEDIAGATE_WITH_FFMPEG
    return std::string(av_version_info());
#else
    return "未链接 FFmpeg";
#endif
}

}  // namespace mediagate
