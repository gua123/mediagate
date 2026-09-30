// mediagate whisper.cpp JNI 桥的公共定义（M7-B / R14 / R19）
//
// 为什么单独一个头：错误码是 native 与 Kotlin（WhisperNative 的 WhisperException）之间的契约，
// 写在两处容易漂移；放头文件里，Kotlin 侧的注释与之逐条对齐。
#ifndef MEDIAGATE_WHISPER_H
#define MEDIAGATE_WHISPER_H

// JNI 桥自身的 ABI 版本。Kotlin 侧 WhisperNative.isAvailable() 会断言这个值，
// 防止「旧的 .so 配新的 Kotlin」这种只在真机上才炸的组合。
#define MG_ABI_VERSION 1

// 错误码（全部为负）：nativeInit 返回负值即失败，同时 nativeLastError() 给出中文原因。
#define MG_ERR_EMPTY_PATH        (-1)  // 模型路径为空
#define MG_ERR_BAD_MODEL         (-2)  // 文件不存在 / 过小 / 头校验不通过（不是 ggml/GGUF）/ 与预期大小不符（下载不完整）
#define MG_ERR_ALREADY_INIT      (-3)  // 重复 init：已有存活的 whisper 上下文（防内存翻倍）
#define MG_ERR_INIT_FAILED       (-4)  // whisper_init_from_file_with_params 返回 NULL（模型损坏或 OOM）
#define MG_ERR_BAD_HANDLE        (-5)  // 句柄无效或已释放（重复 free / 用完再识别）
#define MG_ERR_EMPTY_PCM         (-6)  // PCM 为空
#define MG_ERR_SHORT_PCM         (-7)  // PCM 过短（不足 100 ms），调用方应跳过
#define MG_ERR_TRANSCRIBE_FAILED (-8)  // whisper_full 返回非 0
#define MG_ERR_JNI_BROKEN        (-9)  // JNI 侧异常（找不到 WhisperSegment 类 / 构造签名不符 / 数组分配失败）

#endif  // MEDIAGATE_WHISPER_H
