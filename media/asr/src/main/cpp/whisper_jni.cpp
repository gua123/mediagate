// whisper.cpp 的 JNI 桥（M7-B：R14 音转字幕 / R19 批量字幕任务中心）。
//
// 设计口径（与 docs/plan.md 4.7 B 一致）：
// - **最小 API**：initContext / freeContext / transcribePcm / isAvailable 四件事，别的都不暴露；
// - **句柄制**：initContext 返回不透明句柄（whisper_context* 的整数形式），Kotlin 只当 long 传；
// - **防呆优先**：重复 init、空模型、文件头/大小不符（下载不完整）、句柄失效、PCM 过短，
//   一律返回**错误码**并把中文原因存进 last_error，绝不让 native 直接崩掉用户进程；
// - **不做分段**：30 s 窗口 + 5 s 重叠的切窗逻辑放在 Kotlin（AsrPipeline，JVM 可测），
//   这里只负责「给一段 PCM，返回带时间戳的段数组」。
//
// 线程约定：whisper_context 不是线程安全的，同一句柄的 transcribe 必须串行（:media:asr 的
// AsrQueue 默认并发 1）。本桥不做额外加锁，只在句柄注册表上取锁。

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include "whisper.h"

#include "mediagate_whisper.h"

#define MG_LOG_TAG "mg-whisper-jni"
#define MG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MG_LOG_TAG, __VA_ARGS__)
#define MG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, MG_LOG_TAG, __VA_ARGS__)
#define MG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MG_LOG_TAG, __VA_ARGS__)

namespace {

/** Kotlin 侧的结果类型（:media:asr 的 WhisperSegment）。 */
constexpr const char *kSegmentClass = "io/github/gua123/mediagate/media/asr/WhisperSegment";
constexpr const char *kSegmentCtorSig = "(JJLjava/lang/String;)V";

/** legacy ggml 容器魔数 "ggml"（小端读出来的 uint32）。 */
constexpr uint32_t kGgmlFileMagic = 0x67676d6cU;

/** whisper 至少要有 100 ms 音频才谈得上识别（16 kHz → 1600 采样）。 */
constexpr jsize kMinSamples = 1600;

/** 存活上下文注册表：防重复 init、防重复 free（也防到野指针上一顿乱写）。 */
std::mutex g_mutex;
std::unordered_set<whisper_context *> g_live;
std::string g_last_error = "尚未调用任何 native 方法";

void mgSetError(const std::string &message) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error = message;
    MG_LOGW("%s", message.c_str());
}

std::string mgLastError() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_last_error;
}

std::string mgFromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool mgIsLive(whisper_context *ctx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_live.find(ctx) != g_live.end();
}

/**
 * 模型文件头校验（**纯 native，不依赖 JVM**）。
 *
 * 只做三件事，都是为了"下载不完整/拿错文件"时给一句人话，而不是让 whisper 在解析中途崩：
 * 1. 能打开、大小 >= 1 KB；
 * 2. 头 4 字节是 ggml 旧容器（"ggml"）或 GGUF —— 这正是 whisper.cpp 的加载器认的两种；
 * 3. 调用方给了 expectedSize 时，实际大小必须相等（ModelManager 的校验和/字节数口径）。
 *
 * @param sizeOut 回传实际字节数。
 * @param why 失败原因（中文）。
 */
bool mgCheckModelFile(const std::string &path, jlong expectedSize, long long *sizeOut, std::string *why) {
    FILE *fp = std::fopen(path.c_str(), "rb");
    if (fp == nullptr) {
        *why = "模型文件不存在或无法打开：" + path;
        return false;
    }
    std::fseek(fp, 0, SEEK_END);
    const long long size = std::ftell(fp);
    std::fseek(fp, 0, SEEK_SET);
    if (sizeOut != nullptr) *sizeOut = size;
    unsigned char magic[4] = {0, 0, 0, 0};
    const size_t read = std::fread(magic, 1, 4, fp);
    std::fclose(fp);

    if (size < 1024 || read != 4) {
        *why = "模型文件不完整（小于 1 KB），请删除后重新下载";
        return false;
    }
    const uint32_t little =
        (uint32_t) magic[0] | ((uint32_t) magic[1] << 8) | ((uint32_t) magic[2] << 16) | ((uint32_t) magic[3] << 24);
    const bool legacy = little == kGgmlFileMagic;
    const bool gguf = std::memcmp(magic, "GGUF", 4) == 0;
    if (!legacy && !gguf) {
        *why = "模型文件头校验不通过（既不是 ggml 也不是 GGUF 容器），可能下载被截断或拿错了文件";
        return false;
    }
    if (expectedSize > 0 && size != (long long) expectedSize) {
        char buf[256];
        std::snprintf(
            buf, sizeof(buf),
            "模型大小与预期不符（实际 %lld 字节，预期 %lld 字节），请删除后重新下载",
            size, (long long) expectedSize);
        *why = buf;
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

/** JNI 桥 ABI 版本；Kotlin 侧 isAvailable() 用它确认 .so 与代码配套。 */
JNIEXPORT jint JNICALL
Java_io_github_gua123_mediagate_media_asr_WhisperNative_nativeProbe(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return MG_ABI_VERSION;
}

/** 底层 whisper.cpp 版本号（诊断页/日志用）。 */
JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_asr_WhisperNative_nativeVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    const char *version = whisper_version();
    return env->NewStringUTF(version != nullptr ? version : "unknown");
}

/**
 * 建上下文。
 *
 * @param modelPath 模型文件绝对路径（filesDir/models/ggml-*.bin）。
 * @param threads 默认线程数（仅记录；真正生效的线程数在 transcribePcm 时给，便于"播放时降线程"）。
 * @param useGpu 是否用 GPU 后端（本轮恒 false：只跑 CPU，见 plan 4.7 B）。
 * @param expectedSize 期望字节数；<= 0 表示不校验（DownloadManager 校验完再传进来）。
 * @return > 0 句柄；< 0 错误码（见 mediagate_whisper.h）。
 */
JNIEXPORT jlong JNICALL
Java_io_github_gua123_mediagate_media_asr_WhisperNative_nativeInit(
    JNIEnv *env, jobject thiz, jstring modelPath, jint threads, jboolean useGpu, jlong expectedSize) {
    (void) thiz;
    const std::string path = mgFromJString(env, modelPath);
    if (path.empty()) {
        mgSetError("模型路径为空");
        return MG_ERR_EMPTY_PATH;
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_live.empty()) {
            // 重复 init 防呆：whisper small 一份上下文就 ~500 MB，两份必 OOM。
            // 正确姿势是先 freeContext(旧句柄) 再 init（Kotlin 的 WhisperContext.use {} 保证）。
            g_last_error = "已有存活的 whisper 上下文，请先释放再新建";
            MG_LOGW("%s", g_last_error.c_str());
            return MG_ERR_ALREADY_INIT;
        }
    }

    long long actualSize = 0;
    std::string why;
    if (!mgCheckModelFile(path, expectedSize, &actualSize, &why)) {
        mgSetError(why);
        return MG_ERR_BAD_MODEL;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu == JNI_TRUE;
    cparams.flash_attn = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        // whisper_init 失败只有两种常见原因：模型内容坏了（头对但结构不对），或者内存不够。
        mgSetError("whisper 上下文创建失败：模型内容损坏或设备内存不足");
        return MG_ERR_INIT_FAILED;
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_live.insert(ctx);
    }
    MG_LOGI(
        "whisper 上下文就绪 threads=%d useGpu=%d size=%lldMB path=%s",
        (int) threads, useGpu == JNI_TRUE ? 1 : 0, actualSize / (1024 * 1024), path.c_str());
    return (jlong) (intptr_t) ctx;
}

/** 释放上下文；幂等（重复调用只是无操作，不会 double free）。 */
JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_asr_WhisperNative_nativeFree(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    if (handle == 0) return;
    whisper_context *ctx = (whisper_context *) (intptr_t) handle;
    bool known = false;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        known = g_live.erase(ctx) > 0;
    }
    if (!known) {
        MG_LOGI("freeContext 收到未知句柄（可能已释放），忽略");
        return;
    }
    whisper_free(ctx);
    MG_LOGI("whisper 上下文已释放");
}

/**
 * 识别一段 16 kHz 单声道 float PCM。
 *
 * @param handle nativeInit 返回的句柄。
 * @param pcm [-1, 1] 的 float 采样（Kotlin 侧把 FFmpeg 的 s16le 转成 float，纯 Kotlin 可测）。
 * @param language "zh" / "en" / ""（空 = 自动检测）。
 * @param translate true 时把外语翻成英语（whisper 的 translate 任务）。
 * @param threads 本次识别的线程数（播放让路时降到 1）。
 * @param offsetMs 音频流内的起始毫秒（whisper 的 params.offset_ms：它会**跳过**开头这么多毫秒的音频，
 *   不是给时间戳加偏移）。窗口化流水线一律传 0——窗口的绝对时间由 Kotlin 侧
 *   AsrPipeline.absoluteSegments 负责；只有「单上下文流式识别整条音轨」的用法才需要它。
 * @return 段数组；失败返回 null（原因见 nativeLastError）。
 */
JNIEXPORT jobjectArray JNICALL
Java_io_github_gua123_mediagate_media_asr_WhisperNative_nativeTranscribe(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray pcm, jstring language, jboolean translate, jint threads,
    jint offsetMs) {
    (void) thiz;
    whisper_context *ctx = (whisper_context *) (intptr_t) handle;
    if (ctx == nullptr || !mgIsLive(ctx)) {
        mgSetError("上下文无效或已释放");
        return nullptr;
    }
    if (pcm == nullptr) {
        mgSetError("PCM 数据为空");
        return nullptr;
    }
    const jsize sampleCount = env->GetArrayLength(pcm);
    if (sampleCount < kMinSamples) {
        mgSetError("PCM 过短（不足 100 毫秒），跳过该窗口");
        return nullptr;
    }

    std::vector<float> samples((size_t) sampleCount);
    env->GetFloatArrayRegion(pcm, 0, sampleCount, samples.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        mgSetError("PCM 拷贝失败");
        return nullptr;
    }

    const std::string lang = mgFromJString(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? (int) threads : 4;
    params.offset_ms = offsetMs > 0 ? (int) offsetMs : 0;
    params.translate = translate == JNI_TRUE;
    // 分段由 Kotlin 的 AsrPipeline 负责，每窗独立解码，不吃上一窗的文本上下文
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = lang.empty();

    const int rc = whisper_full(ctx, params, samples.data(), (int) samples.size());
    if (rc != 0) {
        mgSetError("识别失败（whisper_full 返回 " + std::to_string(rc) + "）");
        return nullptr;
    }

    jclass segmentClass = env->FindClass(kSegmentClass);
    if (segmentClass == nullptr) {
        env->ExceptionClear();
        mgSetError("找不到 Kotlin 类 WhisperSegment，native 与 Kotlin 版本不配套");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(segmentClass, "<init>", kSegmentCtorSig);
    if (ctor == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(segmentClass);
        mgSetError("WhisperSegment 的构造签名与 native 期望的不一致");
        return nullptr;
    }

    const int count = whisper_full_n_segments(ctx);
    // 段多的时候局部引用会爆表（默认上限 512），先压一帧
    if (env->PushLocalFrame(count + 16) != 0) {
        env->ExceptionClear();
        env->DeleteLocalRef(segmentClass);
        mgSetError("JNI 局部引用帧创建失败");
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(count, segmentClass, nullptr);
    if (result == nullptr) {
        env->ExceptionClear();
        env->PopLocalFrame(nullptr);
        env->DeleteLocalRef(segmentClass);
        mgSetError("识别结果数组分配失败");
        return nullptr;
    }

    for (int i = 0; i < count; ++i) {
        // whisper 的时间戳单位是 10 ms（centisecond）
        const jlong startMs = (jlong) whisper_full_get_segment_t0(ctx, i) * 10;
        const jlong endMs = (jlong) whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);
        jstring jtext = env->NewStringUTF(text != nullptr ? text : "");
        if (jtext == nullptr) {
            env->ExceptionClear();
            continue;
        }
        jobject item = env->NewObject(segmentClass, ctor, startMs, endMs, jtext);
        if (item == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(jtext);
            continue;
        }
        env->SetObjectArrayElement(result, i, item);
        env->DeleteLocalRef(jtext);
        env->DeleteLocalRef(item);
    }

    jobjectArray finalResult = (jobjectArray) env->PopLocalFrame(result);
    env->DeleteLocalRef(segmentClass);
    if (finalResult == nullptr) {
        mgSetError("识别结果返回失败");
    }
    return finalResult;
}

/** 最近一次失败的中文原因（成功时保留上一次，便于诊断）。 */
JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_asr_WhisperNative_nativeLastError(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return env->NewStringUTF(mgLastError().c_str());
}

}  // extern "C"
