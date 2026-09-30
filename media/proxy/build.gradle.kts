plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.proxy"
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
        // 返回默认值而不是抛 "not mocked"
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
    implementation(project(":data:storage-api"))
    // 代理是阻塞式 socket 服务，用 runBlocking 桥接挂起的数据层（与 media:playback 同一套做法）
    implementation(libs.kotlinx.coroutines.android)

    // JVM 单测：真实临时文件 + FileStorageBackend（data:storage-local）+ 协程测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":data:storage-local"))
}
