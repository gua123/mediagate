plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gua123.mediagate.feature.tasks"
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
        // 纯 JVM 单测（选择/过滤/入队/重排/暂停恢复/失败重试/总进度汇总）里若碰到未 mock 的
        // android.* 桩方法（android.util.Log 等），返回默认值而不是抛 "not mocked"
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
    // RemoteEntry 出现在 TasksEnvironment 的公开签名（R19 列目录选文件）
    api(project(":core:model"))
    // StorageException 在 ViewModel 里做中文错误分类（R19 失败原因）
    api(project(":data:storage-api"))
    // AsrQueueSnapshot / AsrCandidate / AsrSelection 出现在公开签名（R19 队列与过滤）
    api(project(":media:asr"))
    // 状态落库（plan 第 7 章 asr_batch / asr_task）：界面只读快照，DAO 在 :app 装配
    implementation(project(":core:database"))

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
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
