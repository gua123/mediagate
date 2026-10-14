# 自研播放引擎内核（M1 骨架）。CMake 4.x 要求最低版本 ≥ 3.5。
cmake_minimum_required(VERSION 3.22)
project(mediagate_engine CXX)

# C++17：FFmpeg 的错误路径、STL 容器都要用
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mediagate_engine SHARED
        engine_jni.cpp
        engine_core.cpp)

# 16 KB 页对齐（Android 15+ 要求；历史上 19 个 .so 都做过这个核对）
target_link_options(mediagate_engine PRIVATE "-Wl,-z,max-page-size=16384")

# 目前只需要 log（后面接 FFmpeg / MediaCodec / GLES / AAudio 时在这里加）
find_library(log-lib log)
target_link_libraries(mediagate_engine PRIVATE ${log-lib})
