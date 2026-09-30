plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.asr"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // 自建 native（M7-B）：libmediagate_whisper_jni.so + whisper.cpp 四件套，只打 arm64-v8a。
        // 产物由 scripts/build-whisper-android.sh 编出并放进 src/main/jniLibs/。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM 单测（分段计划 / 重叠合并 / 队列状态机 / 下载与校验 / 跳过判定）：
        // 碰到未 mock 的 android.* 桩方法返回默认值而不是抛 "not mocked"
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
    // RemoteEntry / MediaKindGuesser 出现在 AsrSelection 的公开签名（R19 过滤非视频）
    api(project(":core:model"))
    // StorageBackend / StorageException 出现在 AsrOutput.write 的公开签名（R14 写回与无权限兜底）
    api(project(":data:storage-api"))
    // FfmpegRunner / FfmpegCommand 出现在 FfmpegPcmProvider 的公开签名（R11 ASR 取音）
    api(project(":media:ffmpeg"))
    // SubtitleCue / SubtitleWriter 出现在 AsrPipeline 与 AsrOutput 的公开签名（R14 复用字幕模块）
    api(project(":media:subtitle"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
