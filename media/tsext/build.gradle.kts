plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.tsext"
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
        // 解析/索引/缓存全是纯 Kotlin，但读失败路径会走 core:common 的 AppLog（底层 android.util.Log）；
        // JVM 单测里让未 mock 的 android.* 桩方法返回默认值，而不是抛 "not mocked"
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
    // RemoteEntry 出现在 TsIndexKey.of 的公开签名里，需要传递给上层
    api(project(":core:model"))
    // RandomAccessSource 出现在 TsIndexer / openRandomAccessSource 的公开签名里
    api(project(":data:storage-api"))
    implementation(libs.kotlinx.coroutines.android)

    // JVM 单测：合成 TS 文件（PAT/PMT + 可控 PTS/IDR）+ 协程测试 + 真实临时目录
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":data:storage-local"))
}
