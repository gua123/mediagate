plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.data.storage.api"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    // RemoteEntry / Caps / ProbeReport 出现在 StorageBackend 的公开签名里，需传递给上层
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)

    // JVM 单测（SegmentedCacheBackend 的降级链：临时目录读写 + 协程测试作用域）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
