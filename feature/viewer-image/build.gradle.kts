plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gua123.mediagate.feature.viewer.image"
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
        // 纯 JVM 单测（翻页边界 / 缩放钳制 / 采样率 / UiState 归约 / ViewModel）里若碰到未 mock 的
        // android.* 桩方法（AppLog 底层的 android.util.Log），返回默认值而不是抛 "not mocked"
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
    // RemoteEntry 出现在查看器公开签名（ImageViewerEnvironment.siblings / ViewerUiState.siblings）
    api(project(":core:model"))
    // StorageException 是 ImageViewerEnvironment 的失败约定，会一路传到调用方
    api(project(":data:storage-api"))
    // ImageSampling（采样率算法）与 ThumbnailRepository 的缓存 key 口径（R5/M1-F）
    implementation(project(":media:thumbnail"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // BackHandler（返回键退出查看器）
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    // JVM 单测：翻页 / 缩放 / 采样率 / UiState 归约 / ViewModel 都不依赖 Android
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
