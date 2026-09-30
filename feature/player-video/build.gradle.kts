plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gua123.mediagate.feature.player.video"
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
        // 纯 JVM 单测（状态归约 / 切换计划 / 解码模式持久化 / 降级决策 / 进度拖拽语义 / 上下集边界）
        // 若碰到未 mock 的 android.* 桩方法（AppLog 底层的 android.util.Log），返回默认值而不是抛 "not mocked"
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
    // RemoteEntry 出现在 VideoPlayerEnvironment.siblings 的公开签名（上下集队列）
    api(project(":core:model"))
    // StorageBackend 出现在 VideoPlayerEnvironment.backend 的公开签名（R12 当前根目录）
    api(project(":data:storage-api"))
    // PlayerEngine / EngineKind / DecoderMode / SwitchPlan 是本模块公开接口与状态的一部分（R9/R10）
    api(project(":media:engine"))
    // PlaybackProgressStore 出现在 VideoPlayerEnvironment.progress 的公开签名（R18 断点续播）
    api(project(":media:playback"))
    implementation(project(":media:subtitle"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 上一集 / 下一集 / 缩放等图标在 extended 包里（core 包没有）
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // BackHandler（返回键退出播放页）
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.ui.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
