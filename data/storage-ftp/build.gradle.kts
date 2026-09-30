plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.data.storage.ftp"
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
        // JVM 单测里若碰到未 mock 的 android.* 桩方法（AppLog 底层的 android.util.Log），
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
    // StorageBackend / RemoteEntry / Caps 出现在 FtpStorageBackend 的公开签名里，向上传递
    api(project(":core:model"))
    api(project(":data:storage-api"))
    implementation(project(":core:network"))
    implementation(libs.commons.net)

    // JVM 单测：org.apache.ftpserver 起嵌入式 FTP 服务器（随机端口 / 临时目录 / 内存用户）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ftpserver.core)
}
