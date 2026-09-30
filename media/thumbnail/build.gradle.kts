plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.thumbnail"
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
        // JVM 单测（key / 两级缓存 / 并发上限）里若碰到未 mock 的 android.* 桩方法
        // （AppLog 底层的 android.util.Log），返回默认值而不是抛 "not mocked"
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
    // RemoteEntry / Caps 出现在公开签名里（thumbnail(entry, backend)），需传递给上层
    api(project(":core:model"))
    // StorageBackend / RandomAccessSource 同样在公开签名里（抽帧器接口也用它）
    api(project(":data:storage-api"))
    implementation(project(":media:ffmpeg"))
    // FfmpegFrameExtractor 直接用 FFmpegKit；:media:ffmpeg 里是 implementation，传递不过来
    implementation(libs.ffmpeg.kit.min)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // JVM 单测：真实临时目录 + 协程测试作用域（key / 缓存 / 并发上限都不依赖 Android API）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
