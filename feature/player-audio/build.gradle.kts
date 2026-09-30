plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gua123.mediagate.feature.player.audio"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        // 纯 JVM 单测（格式化 / seek 换算 / 队列轮转 / UiState 归约 / ViewModel）里若碰到
        // 未 mock 的 android.* 桩方法（AppLog 底层的 android.util.Log），返回默认值而不是抛异常
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
    // RemoteEntry / MediaKindGuesser 出现在本模块公开签名（队列）
    api(project(":core:model"))
    // StorageBackend 出现在 AudioPlayerEnvironment 的公开签名（当前后端，R12）
    api(project(":data:storage-api"))
    // ThumbnailRepository 同样在公开签名里（音频内嵌封面，R5）
    api(project(":media:thumbnail"))
    implementation(project(":media:engine"))
    implementation(libs.media3.session)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 播放/暂停/上一首/下一首/循环 等图标在 extended 包里（core 包没有）
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
