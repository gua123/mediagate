pluginManagement {
    repositories {
        // 国内镜像优先（本机实测比官方源快 10~20 倍），官方源作兜底
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "mediagate"

// 壳：导航、DI、权限引导、设置、诊断、任务中心
include(":app")

// 核心
include(":core:common", ":core:model", ":core:database", ":core:crypto", ":core:network")

// 数据层：统一 StorageBackend → RandomAccessSource，四协议后端
include(
    ":data:storage-api",
    ":data:storage-local",
    ":data:storage-webdav",
    ":data:storage-sftp",
    ":data:storage-ftp",
)

// 媒体层：双播放内核、回环代理、抽帧、TS 索引、FFmpeg、ASR、字幕
include(
    ":media:engine",
    ":media:proxy",
    ":media:playback",
    ":media:thumbnail",
    ":media:tsext",
    ":media:ffmpeg",
    ":media:asr",
    ":media:subtitle",
)

// 功能层
include(
    ":feature:home",
    ":feature:browser",
    ":feature:player-video",
    ":feature:player-audio",
    ":feature:viewer-image",
    ":feature:connections",
    ":feature:settings",
    ":feature:tasks",
)
