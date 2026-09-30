plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.subtitle"
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
        // 纯 JVM 单测（定位匹配 / SRT-VTT-ASS 解析容错 / 时间轴偏移 / 样式校验 / 写回与权限拒绝）：
        // 碰到未 mock 的 android.* 桩方法时返回默认值而不是抛 "not mocked"
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
    // RemoteEntry 出现在本模块公开签名（SubtitleLocator.locate / discover 的条目列表），故用 api 传递
    api(project(":core:model"))
    // StorageBackend 出现在本模块公开签名（SubtitleReader.read / SubtitleWriter.writeBack / discover），故用 api 传递
    api(project(":data:storage-api"))
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
