# mediagate

Android 多协议媒体播放器（小米澎湃 OS 自用）：本地 / WebDAV / SFTP / FTP 上的视频、音乐、图片，
支持 .ts 与 .m3u8、远端进度条拖拽、缩略图、双播放内核（硬解/软解）、字幕与本地小模型音转字幕（可批量）、
局域网与公网双地址自动切换、多连接管理与连通性测试。

**计划与验收标准见 [docs/plan.md](docs/plan.md)（方案 v4，R1–R19）。**

## 构建

    source scripts/env.sh                  # 进入本项目环境（私有 JDK / Gradle / SDK / NDK）
    bash scripts/install-toolchain.sh      # 首次：装项目私有 JDK21（SDK 已在 .toolchain/android-sdk）
    ./gradlew :app:assembleDebug           # 产物 app/build/outputs/apk/debug/app-debug.apk
    ./gradlew :app:assembleRelease         # 需 keystore.properties（不进 git）

    # 音转字幕的 native 库（不进 git，按需重建）
    bash scripts/build-whisper-android.sh  # whisper.cpp v1.9.4 → media/asr/src/main/jniLibs/arm64-v8a

    # native 库 16 KB 页对齐自检（Android 15+ 必需）
    bash scripts/check-page-align.sh app/build/outputs/apk/debug/app-debug.apk

## 环境隔离（不影响本机其它项目）

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| JDK 21 | `.toolchain/jdk-21` | Temurin 解压版，不写系统 PATH |
| Gradle 9.8.0 | `.toolchain/gradle-9.8.0` + wrapper 预置 | `GRADLE_USER_HOME` 也在项目内 |
| Android SDK | `.toolchain/android-sdk` | platform 37.2 / build-tools 37.0.0 / platform-tools 37.0.1 |
| NDK / CMake | `.toolchain/android-sdk/{ndk,cmake}` | NDK r30 + CMake 4.1.2，只给本项目 native 构建用 |

系统层面**不安装任何 JDK/SDK**；公共 `/opt/android-sdk` 只作为 sdkmanager 的下载来源。
依赖仓库在 `settings.gradle.kts` 里配了阿里云镜像优先、官方源兜底。

## 版本基线（2026-09-30 实测）

AGP 9.4.1（内置 Kotlin，不再声明 kotlin.android 插件）· Gradle 9.8.0 · KSP 2.3.12 · Compose BOM 2026.09.00 ·
compileSdk 37.2 / targetSdk 36 / minSdk 33 · Media3 1.11.1 · LibVLC 3.7.6 ·
FFmpeg 简版 `dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9` · whisper.cpp v1.9.4（自建）·
Room 2.8.5 · OkHttp 5.5.0 · JSch 2.28.7（mwiede）· Commons Net 3.13.0 · Coil 3.6.3。
