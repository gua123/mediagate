# mediagate

Android 多协议媒体播放器（小米澎湃 OS 自用）：本地 / WebDAV / SFTP / FTP 上的视频、音乐、图片，
支持 .ts 与 .m3u8、远端进度条拖拽、缩略图、双播放内核（硬解/软解）、字幕与本地小模型音转字幕（可批量）、
局域网与公网双地址自动切换、多连接管理与连通性测试。

**计划与验收标准见 [docs/plan.md](docs/plan.md)（方案 v4，R1–R19）。**

## 构建

    source scripts/env.sh
    bash scripts/install-toolchain.sh     # 首次
    ./gradlew :app:assembleDebug

## 环境隔离

项目自带 JDK（`.toolchain/jdk-21`）与 Gradle 用户目录（`.toolchain/gradle-home`），
Android SDK 用公共只读 `/opt/android-sdk`；**系统不安装任何 JDK/SDK**，不影响本机其它项目。
