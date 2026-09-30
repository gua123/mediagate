plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.media.engine"
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
        // JVM 单测只跑纯逻辑（MediaSourceRef / SwitchPlan / 解码模式映射），
        // 若碰到未 mock 的 android.* 桩方法（AppLog 底层是 android.util.Log）返回默认值而不是抛异常
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
    implementation(project(":data:storage-api"))
    // LoopbackHttpProxy 出现在 VlcEngine 的公开构造签名（App 层创建并注入），故用 api 暴露
    api(project(":media:proxy"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // LibVLC 兜底内核（M2 接 PlayerEngine）
    implementation(libs.libvlc.all)
    // 引擎切换是挂起过程（Dispatchers.Main.immediate），StateFlow 也在主线程收敛
    implementation(libs.kotlinx.coroutines.android)

    // JVM 单测：纯逻辑（不加载 LibVLC / Media3 运行时）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
