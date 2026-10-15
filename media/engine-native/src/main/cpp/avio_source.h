#ifndef MEDIAGATE_AVIO_SOURCE_H
#define MEDIAGATE_AVIO_SOURCE_H

#include <jni.h>

#include <cstdint>
#include <string>

extern "C" {
#include <libavformat/avio.h>
}

namespace mediagate {

/** 记下 JVM（JNI_OnLoad 时调用）：native 线程要回调 Kotlin，得先拿到它。 */
void setJavaVm(JavaVM* vm);

/**
 * 把 Kotlin 的 NativeMediaSource 包成 FFmpeg 的 AVIOContext。
 *
 * 为什么要这么做：全项目只认一个数据抽象（StorageBackend → RandomAccessSource），
 * 引擎若自己 fopen，**SAF 与 WebDAV/SFTP/FTP 都播不了**，分段缓存与超时层也全绕过。
 * 于是让 FFmpeg 的读/seek 回调转成 JNI 调用，落回我们自己的数据层。
 *
 * 线程：read 只发生在播放线程上（native 自己建的线程，JVM 不认识它）⇒
 * 这里按需 AttachCurrentThread，并在**线程退出时**自动 Detach（thread_local 的析构）。
 */
class SourceBridge {
public:
    /** read 的返回码，跟 Kotlin 侧 NativeMediaSource 的约定一致。 */
    static constexpr int kEof = -1;
    static constexpr int kError = -2;

    /** 借用 Kotlin 对象（存全局引用）。失败时 error 里是中文原因。 */
    bool attach(JNIEnv* env, jobject source, std::string* error);

    /** 还掉全局引用（可以在任意已附着/可附着的线程上调用）。 */
    void detach();

    bool valid() const { return source_ != nullptr; }

    /** 读一段；返回字节数，或 kEof / kError。读完自动推进内部位置。 */
    int read(uint8_t* buffer, int size);

    /** 数据总长度；未知返回 -1（结果会缓存，长度不会变）。 */
    int64_t length();

    int64_t position() const { return position_; }
    void setPosition(int64_t position) { position_ = position; }

    /** Kotlin 侧记下的中文失败原因（空＝没有）。 */
    std::string lastError();

private:
    jobject source_ = nullptr;  // 全局引用
    jmethodID readMethod_ = nullptr;
    jmethodID lengthMethod_ = nullptr;
    jmethodID errorMethod_ = nullptr;
    int64_t position_ = 0;
    int64_t length_ = -1;
    bool lengthKnown_ = false;
};

/**
 * 建一个走数据层的 AVIOContext（读/seek 都回调到 Kotlin）。
 * 失败返回 nullptr。
 */
AVIOContext* openSourceAvio(SourceBridge* bridge, int bufferSize);

/**
 * 释放 [openSourceAvio] 建出来的东西。
 * 顺序有讲究：**先 avformat_close_input，再调它**（这里会先还缓冲、再还上下文）。
 */
void closeSourceAvio(AVIOContext** avio);

}  // namespace mediagate

#endif  // MEDIAGATE_AVIO_SOURCE_H
