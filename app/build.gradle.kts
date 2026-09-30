import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名材料不进 git：keystore/ 与 keystore.properties 均已在 .gitignore
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "io.github.gua123.mediagate"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()
    // 项目私有 NDK：用于 strip native 库（whisper.cpp 等自建 .so）
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.gua123.mediagate"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
        // 自用 sideload，只打 64 位（native: libvlc / ffmpeg / whisper）
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            // LibVLC 与 ffmpeg-kit 各自都带 libc++_shared.so，同路径冲突，取其一即可
            pickFirsts += setOf("lib/**/libc++_shared.so")
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation(project(":core:database"))
    implementation(project(":core:crypto"))
    implementation(project(":core:network"))

    implementation(project(":data:storage-api"))
    implementation(project(":data:storage-local"))
    implementation(project(":data:storage-webdav"))
    implementation(project(":data:storage-sftp"))
    implementation(project(":data:storage-ftp"))

    implementation(project(":media:engine"))
    implementation(project(":media:proxy"))
    implementation(project(":media:playback"))
    implementation(project(":media:thumbnail"))
    implementation(project(":media:tsext"))
    implementation(project(":media:ffmpeg"))
    implementation(project(":media:asr"))
    implementation(project(":media:subtitle"))

    implementation(project(":feature:home"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:player-video"))
    implementation(project(":feature:player-audio"))
    implementation(project(":feature:viewer-image"))
    implementation(project(":feature:connections"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:tasks"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 底部导航 / 页面图标（浏览、连接、任务、设置…）
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    // R12：根目录设置（模式 + 路径 / 树 URI）用 DataStore Preferences 持久化
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
