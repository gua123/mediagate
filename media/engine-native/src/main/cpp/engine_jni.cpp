#include <jni.h>

#include <string>

#include "engine_core.h"

namespace {

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

/**
 * JNI 边界（M1）：只回答"引擎是谁、内核是什么版本、内部有哪几条解码路径"。
 *
 * 边界纪律（写进方案 §8）：**粗接口**——传句柄 / fd / 直接缓冲区，
 * 绝不逐帧传数组；这里返回字符串已经是最细的一档，只用于自检与设置页显示。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeName(JNIEnv* env, jclass) {
    return toJString(env, mediagate::engineInfo().name);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeVersion(JNIEnv* env, jclass) {
    return toJString(env, mediagate::engineInfo().version);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeDecodePaths(JNIEnv* env, jclass) {
    return toJString(env, mediagate::engineInfo().decodePaths);
}
