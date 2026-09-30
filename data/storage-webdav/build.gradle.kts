plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.data.storage.webdav"
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
        // JVM 单测（协议解析 / Range / 假 WebDAV 服务器）里若碰到未 mock 的 android.* 桩方法
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
    // StorageBackend / RemoteEntry / Caps 出现在 WebDavStorageBackend 的公开签名里，向上传递
    api(project(":core:model"))
    api(project(":data:storage-api"))
    implementation(project(":core:network"))
    implementation(libs.okhttp)

    // JVM 单测：用 com.sun.net.httpserver 起假 WebDAV 服务器 + 协程测试作用域
    // （com.sun.net.httpserver 是 JDK 自带模块，只出现在测试源集里，Android 侧不引用）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
