#include <jni.h>

#include <string>

#include <android/native_window_jni.h>

#include "engine_core.h"
#include "surface_renderer.h"

namespace {

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

/** 全局唯一的渲染器（一套引擎＝一个渲染目标）。 */
mediagate::SurfaceRenderer& surfaceRenderer() {
    static mediagate::SurfaceRenderer renderer;
    return renderer;
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
/**
 * 绑定/解绑渲染目标（Kotlin 侧传 android.view.Surface；nullptr 表示解绑）。
 *
 * 为什么 JNI 边界上传 Surface 而不是尺寸/像素：**Surface 是句柄**——一次传递，
 * 之后所有帧都在 native 侧直接写，不需要逐帧跨语言（方案 §8 的"边界要粗"）。
 */
extern "C" JNIEXPORT void JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeSetSurface(
        JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* window = surface == nullptr ? nullptr : ANativeWindow_fromSurface(env, surface);
    // ANativeWindow_fromSurface 已经 +1 引用；SurfaceRenderer 负责在换/释放时 -1
    surfaceRenderer().setWindow(window);
}

/** 是否已绑定可画的 Surface。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeHasSurface(JNIEnv*, jclass) {
    return surfaceRenderer().hasWindow() ? JNI_TRUE : JNI_FALSE;
}

/** 画一帧测试图案（M2 用来验证"Kotlin Surface → native 渲染"这条链路）。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeDrawTestPattern(
        JNIEnv*, jclass, jint frameIndex) {
    return surfaceRenderer().drawTestPattern(frameIndex) ? JNI_TRUE : JNI_FALSE;
}

/** 用纯色清屏。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeClearSurface(
        JNIEnv*, jclass, jint argb) {
    return surfaceRenderer().clear(static_cast<uint32_t>(argb)) ? JNI_TRUE : JNI_FALSE;
}
/** 自研 FFmpeg 的版本串（没链接时返回中文说明）。 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_gua123_mediagate_media_enginenative_NativeEngine_nativeFfmpegVersion(JNIEnv* env, jclass) {
    return toJString(env, mediagate::ffmpegVersion());
}
