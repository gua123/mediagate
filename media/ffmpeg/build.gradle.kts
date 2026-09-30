plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.ffmpeg"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM 单测里若碰到未 mock 的 android.* 桩方法（core:common 的 AppLog 底层是 android.util.Log），
        // 返回默认值而不是抛 "not mocked"；命令构造与输出解析都是纯 Kotlin，不依赖 Android。
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    // RandomAccessSource 出现在 TimestampRepair 的公开签名里（plan 3 章「所有媒体数据只经过它」），
    // 需要传递给上层，故用 api 而不是 implementation
    api(project(":data:storage-api"))
    implementation(libs.kotlinx.coroutines.android)
    // FFmpeg 简版（社区维护分支，16 KB 页对齐；抽帧兜底 / 时间戳重建 / ASR 取音）
    implementation(libs.ffmpeg.kit.min)

    // JVM 单测：命令构造纯逻辑 + 输出解析纯函数 + 假 Runner（真跑 FFmpeg 需要设备）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
