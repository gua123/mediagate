# mediagate whisper.cpp JNI 桥的 CMake 工程（M7-B / R14 / R19）。
#
# 只编一个 .so：libmediagate_whisper_jni.so。
# 它**不重复编译 whisper.cpp**，而是链接 scripts/build-whisper-android.sh 刚刚用同一个 NDK /
# 同一套 CMake 参数编出来的 libwhisper.so —— 这样两边的 ABI、STL、优化档完全一致，
# 也不会把 whisper.cpp 编两遍（small 模型的宿主库编译要几分钟）。
cmake_minimum_required(VERSION 3.22)
project(mediagate_whisper_jni CXX)

if(NOT DEFINED WHISPER_ROOT OR NOT DEFINED WHISPER_LIB_DIR)
    message(FATAL_ERROR "需要 -DWHISPER_ROOT=<whisper.cpp 源码目录> -DWHISPER_LIB_DIR=<libwhisper.so 所在目录>")
endif()

add_library(mediagate_whisper_jni SHARED whisper_jni.cpp)

target_include_directories(mediagate_whisper_jni PRIVATE
    "${WHISPER_ROOT}/include"
    "${WHISPER_ROOT}/ggml/include"
    "${CMAKE_CURRENT_SOURCE_DIR}")

find_library(mg_log_lib log)

target_link_libraries(mediagate_whisper_jni PRIVATE
    "${WHISPER_LIB_DIR}/libwhisper.so"
    ${mg_log_lib})

# plan 第 8 章：native 库必须 16 KB 页对齐（NDK r28+ 默认已是 16 KB，这里显式钉死，
# 免得换 NDK 或改参数后悄悄退回 4 KB）。scripts/check-page-align.sh 会复核。
target_link_options(mediagate_whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")

# 产物名固定：libmediagate_whisper_jni.so（Kotlin 侧 System.loadLibrary("mediagate_whisper_jni")）
set_target_properties(mediagate_whisper_jni PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "mediagate_whisper_jni")
