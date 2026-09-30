plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.gua123.mediagate.data.storage.local"
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
        // JVM 单测（FileStorageBackend）里若碰到未 mock 的 android.* 桩方法，返回默认值而不是抛异常
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
    // StorageBackend / RemoteEntry / Caps 都出现在本模块的公开签名里（LocalBackends 工厂），故用 api 暴露
    api(project(":data:storage-api"))
    api(project(":core:model"))
    implementation(libs.androidx.documentfile)

    // JVM 单测：真实临时目录读写 + 协程测试作用域
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
