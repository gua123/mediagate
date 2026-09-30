plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gua123.mediagate.feature.connections"
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
        // 纯 JVM 单测（表单校验 / 状态归约 / 结果聚合排序 / 展示格式化）里若碰到未 mock 的
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
    implementation(project(":core:model"))
    // MediaGateDatabase / ConnectionDao / NetworkRuleEntity 出现在 ConnectionsEnvironment 的公开签名（R7/R8）
    api(project(":core:database"))
    // CredentialCipher 出现在 ConnectionsEnvironment 的公开签名（R6）
    api(project(":core:crypto"))
    // ProtocolKind / SelectableAddress / ConnectionTester 出现在页面与 ViewModel 的公开签名（R7/R8）
    api(project(":core:network"))
    // 协议握手（第三段）直接用现成后端，不重写一份（R8）：WebDAV / SFTP / FTP
    implementation(project(":data:storage-webdav"))
    implementation(project(":data:storage-sftp"))
    implementation(project(":data:storage-ftp"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // 保存连接 + 地址 + 规则要在一个事务里（room-ktx 的 withTransaction）
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
