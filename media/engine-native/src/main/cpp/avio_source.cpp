#include "avio_source.h"

#include <android/log.h>

#include <cerrno>
#include <cstring>

extern "C" {
#include <libavutil/error.h>
#include <libavutil/mem.h>
}

#include "av_sync.h"

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

JavaVM* g_vm = nullptr;

/** 记录"这个线程是我们 attach 上来的"，线程退出时负责 detach（否则 ART 会抱怨）。 */
struct ThreadEnv {
    bool attached = false;

    ~ThreadEnv() {
        if (attached && g_vm != nullptr) g_vm->DetachCurrentThread();
    }
};

thread_local ThreadEnv g_threadEnv;

/** 拿到当前线程的 JNIEnv（native 线程按需附着）。 */
JNIEnv* envForCurrentThread() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    const jint state = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_OK) return env;
    if (state != JNI_EDETACHED) return nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    g_threadEnv.attached = true;
    return env;
}

/** FFmpeg 的读回调：转成 JNI 调用。 */
int readPacket(void* opaque, uint8_t* buffer, int size) {
    auto* bridge = static_cast<SourceBridge*>(opaque);
    if (bridge == nullptr || buffer == nullptr || size <= 0) return AVERROR(EINVAL);
    const int read = bridge->read(buffer, size);
    if (read == SourceBridge::kEof) return AVERROR_EOF;
    if (read < 0) return AVERROR(EIO);
    return read;
}

/** FFmpeg 的 seek 回调：我们本来就是随机访问，所以这里只做位置换算。 */
int64_t seekSource(void* opaque, int64_t offset, int whence) {
    auto* bridge = static_cast<SourceBridge*>(opaque);
    if (bridge == nullptr) return AVERROR(EINVAL);
    if (whence == AVSEEK_SIZE) {
        const int64_t length = bridge->length();
        return length >= 0 ? length : AVERROR(ENOSYS);
    }
    int base = 0;
    switch (whence & ~AVSEEK_FORCE) {
        case SEEK_SET: base = 0; break;
        case SEEK_CUR: base = 1; break;
        case SEEK_END: base = 2; break;
        default: return AVERROR(EINVAL);
    }
    const int64_t length = bridge->length();
    int64_t target = 0;
    if (!resolveSeekPosition(bridge->position(), offset, base, length, length >= 0, &target)) {
        return AVERROR(EINVAL);
    }
    bridge->setPosition(target);
    return target;
}

}  // namespace

void setJavaVm(JavaVM* vm) {
    g_vm = vm;
}

bool SourceBridge::attach(JNIEnv* env, jobject source, std::string* error) {
    detach();
    if (env == nullptr || source == nullptr) {
        if (error != nullptr) *error = "没有可用的数据来源";
        return false;
    }
    jclass clazz = env->GetObjectClass(source);
    if (clazz == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (error != nullptr) *error = "数据来源不可用（取不到它的类型）";
        return false;
    }
    readMethod_ = env->GetMethodID(clazz, "read", "(JLjava/nio/ByteBuffer;I)I");
    lengthMethod_ = env->GetMethodID(clazz, "length", "()J");
    errorMethod_ = env->GetMethodID(clazz, "lastError", "()Ljava/lang/String;");
    env->DeleteLocalRef(clazz);
    if (readMethod_ == nullptr || lengthMethod_ == nullptr || errorMethod_ == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        readMethod_ = nullptr;
        lengthMethod_ = nullptr;
        errorMethod_ = nullptr;
        if (error != nullptr) *error = "数据来源接口不匹配（要实现 NativeMediaSource）";
        return false;
    }
    source_ = env->NewGlobalRef(source);
    if (source_ == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        readMethod_ = nullptr;
        lengthMethod_ = nullptr;
        errorMethod_ = nullptr;
        if (error != nullptr) *error = "数据来源引用失败（内存不足）";
        return false;
    }
    position_ = 0;
    length_ = -1;
    lengthKnown_ = false;
    return true;
}

void SourceBridge::detach() {
    readMethod_ = nullptr;
    lengthMethod_ = nullptr;
    errorMethod_ = nullptr;
    position_ = 0;
    length_ = -1;
    lengthKnown_ = false;
    if (source_ == nullptr) return;
    JNIEnv* env = envForCurrentThread();
    if (env != nullptr) env->DeleteGlobalRef(source_);
    source_ = nullptr;
}

int SourceBridge::read(uint8_t* buffer, int size) {
    if (source_ == nullptr || readMethod_ == nullptr) return kError;
    JNIEnv* env = envForCurrentThread();
    if (env == nullptr) return kError;
    // NewDirectByteBuffer 只包一层，不拷贝：写它就等于写进 FFmpeg 的缓冲。
    // 注意这里不是 JNI 方法调用，**局部引用不会自动回收**，用完必须自己删。
    jobject direct = env->NewDirectByteBuffer(buffer, size);
    if (direct == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return kError;
    }
    const jint read = env->CallIntMethod(source_, readMethod_, static_cast<jlong>(position_),
                                         direct, static_cast<jint>(size));
    env->DeleteLocalRef(direct);
    if (env->ExceptionCheck()) {
        // Kotlin 侧抛了异常：清掉（不能让异常飘回 FFmpeg 的 C 代码），当作读失败
        env->ExceptionClear();
        return kError;
    }
    if (read > 0) position_ += read;
    return read;
}

int64_t SourceBridge::length() {
    if (lengthKnown_) return length_;
    if (source_ == nullptr || lengthMethod_ == nullptr) return -1;
    JNIEnv* env = envForCurrentThread();
    if (env == nullptr) return -1;
    const jlong value = env->CallLongMethod(source_, lengthMethod_);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    length_ = value;
    lengthKnown_ = true;
    return length_;
}

std::string SourceBridge::lastError() {
    if (source_ == nullptr || errorMethod_ == nullptr) return std::string();
    JNIEnv* env = envForCurrentThread();
    if (env == nullptr) return std::string();
    auto* text = static_cast<jstring>(env->CallObjectMethod(source_, errorMethod_));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return std::string();
    }
    if (text == nullptr) return std::string();
    const char* raw = env->GetStringUTFChars(text, nullptr);
    std::string result = raw == nullptr ? std::string() : std::string(raw);
    if (raw != nullptr) env->ReleaseStringUTFChars(text, raw);
    env->DeleteLocalRef(text);
    return result;
}

AVIOContext* openSourceAvio(SourceBridge* bridge, int bufferSize) {
    if (bridge == nullptr || !bridge->valid() || bufferSize <= 0) return nullptr;
    auto* buffer = static_cast<uint8_t*>(av_malloc(static_cast<size_t>(bufferSize)));
    if (buffer == nullptr) return nullptr;
    AVIOContext* avio = avio_alloc_context(buffer, bufferSize, 0, bridge, &readPacket, nullptr,
                                           &seekSource);
    if (avio == nullptr) {
        av_freep(&buffer);
        __android_log_print(ANDROID_LOG_WARN, kTag, "数据层 AVIO 建不出来（内存不足）");
        return nullptr;
    }
    return avio;
}

void closeSourceAvio(AVIOContext** avio) {
    if (avio == nullptr || *avio == nullptr) return;
    // 缓冲是我们给的，得我们自己还（avio_context_free 只管它自己那份）
    av_freep(&(*avio)->buffer);
    avio_context_free(avio);
    *avio = nullptr;
}

}  // namespace mediagate
