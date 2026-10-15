#ifndef MEDIAGATE_SELF_CHECK_H
#define MEDIAGATE_SELF_CHECK_H

#include <string>

namespace mediagate {

/**
 * 引擎自检（给**独立进程探针**用）：这台机器上，自研引擎的各条链路能不能初始化起来。
 *
 * 覆盖的是「一上来就崩 / 起不来」那一类问题（也正是当年把 LibVLC 判死的那类）：
 *  - native 库能不能加载（调用方 System.loadLibrary 已经走到这里了）；
 *  - FFmpeg 能不能用：版本串 + 真开一个 H.264 解码器与一个 AAC 解码器（alloc + open + free）；
 *  - swscale / swresample 能不能建上下文；
 *  - **AAudio 能不能开流**（自研引擎最容易原生崩的一步）。
 *
 * 不覆盖：某个具体文件能不能播（那取决于文件本身，由「打开失败自动回退」兜底）。
 *
 * @return 通过时以 "ok|" 开头、后面是人话摘要；不通过时以 "错误：" 开头（中文，可直接给用户看）。
 */
std::string selfCheck();

}  // namespace mediagate

#endif  // MEDIAGATE_SELF_CHECK_H
