plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.playback"
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
        // JVM 单测（BackendDataSource）：Media3 的 DataSpec 需要 android.net.Uri 实例，
        // 未 mock 的 android.* 方法返回默认值而不是抛 "not mocked"。
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
    implementation(project(":core:model"))
    // StorageBackend 出现在本模块公开签名（BackendDataSource / BackendDataSourceFactory /
    // PlaybackHost），:app 实现 PlaybackHost 时要用，故用 api 传递。
    api(project(":data:storage-api"))
    implementation(project(":media:engine"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)

    // JVM 单测：真实临时文件 + FileStorageBackend（data:storage-local）+ 协程测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":data:storage-local"))
}
