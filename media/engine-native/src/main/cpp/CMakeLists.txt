# 自研播放引擎内核（M1 骨架）。CMake 4.x 要求最低版本 ≥ 3.5。
cmake_minimum_required(VERSION 3.22)
project(mediagate_engine CXX)

# C++17：FFmpeg 的错误路径、STL 容器都要用
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mediagate_engine SHARED
        engine_jni.cpp
        engine_core.cpp
        surface_renderer.cpp
        media_probe.cpp
        first_frame.cpp
        native_player.cpp
        audio_output.cpp)

# 16 KB 页对齐（Android 15+ 要求；历史上 19 个 .so 都做过这个核对）
target_link_options(mediagate_engine PRIVATE "-Wl,-z,max-page-size=16384")

# 目前只需要 log / android / aaudio（后面接 MediaCodec / GLES 时在这里加）
find_library(log-lib log)
# android 库：ANativeWindow（渲染目标）与 __android_log_print
find_library(android-lib android)
# AAudio：自研引擎的音频输出（M3 后半；只走 C API，不碰 Java 层）
find_library(aaudio-lib aaudio)
target_link_libraries(mediagate_engine PRIVATE ${log-lib} ${android-lib} ${aaudio-lib})
# ---- 自研 FFmpeg（scripts/build-ffmpeg-android.sh 的产物）----
# 头文件与 .so 必须同版本，所以只能用我们自己编的这套（ffmpeg-kit 的 AAR 不给头文件）。
# 没编出来时**优雅降级**：引擎仍能构建，只是暂时没有解复用/解码能力。
set(MEDIAGATE_FFMPEG_ROOT "/root/project/mediagate/.toolchain/ffmpeg-android/arm64-v8a" CACHE PATH "自研 FFmpeg 产物目录")
if(EXISTS "${MEDIAGATE_FFMPEG_ROOT}/include/libavcodec/avcodec.h")
    message(STATUS "mediagate: 找到自研 FFmpeg → ${MEDIAGATE_FFMPEG_ROOT}")
    foreach(lib avcodec avformat avutil swresample swscale)
        add_library(${lib} SHARED IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION
                "${MEDIAGATE_FFMPEG_ROOT}/lib/lib${lib}-mg.so")
    endforeach()
    target_include_directories(mediagate_engine PRIVATE "${MEDIAGATE_FFMPEG_ROOT}/include")
    target_compile_definitions(mediagate_engine PRIVATE MEDIAGATE_WITH_FFMPEG=1)
    target_link_libraries(mediagate_engine PRIVATE avcodec avformat avutil swresample swscale)
    # 打进来的 FFmpeg 也要能被打包（AGP 只看 jniLibs，这里用 IMPORTED 的位置即可）
else()
    message(STATUS "mediagate: 未找到自研 FFmpeg（先跑 scripts/build-ffmpeg-android.sh），本次不链接")
endif()