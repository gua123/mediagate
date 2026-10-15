import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 源码级护栏读的**非编译输入**必须声明成 Test 输入（2026-10-15 真机事故后补）：
// 清单少一个会话 action 时，SessionServiceDeclarationTest 本该报红，但 Gradle 因为"清单不是测试输入"
// 判定测试 UP-TO-DATE 直接跳过 —— 护栏看着绿、其实根本没跑（改了 .kt 会重跑，因为编译产物是输入；
// **清单/res 这类不参与编译的文件不会**，所以必须显式声明）。
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/java"), fileTree("src/main/res"), "src/main/AndroidManifest.xml")
        .withPropertyName("sourceGuardrailInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
        // 0.1.65：自研引擎第一次能在播放页点到（内核按钮三档轮转）。**尚未发布**——
        // 先出本机预览包给用户真机试播，试通再走一次正式发版。
        versionCode = 69
        versionName = "0.1.68"
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

    testOptions {
        // JVM 单测（字幕宿主 R14：写回 / 无权限落本地 / 读取解析）：
        // 碰到未 mock 的 android.* 桩方法（AppLog 底层的 android.util.Log）返回默认值而不是抛 "not mocked"
        unitTests.isReturnDefaultValues = true
    }

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
    // 自研单内核（native）：先只把 .so 打进包并能在诊断里自述，播放逻辑随后长出来
    implementation(project(":media:engine-native"))
    implementation(project(":media:proxy"))
    implementation(project(":media:playback"))
    // M2-B：:app 是组合根，直接用 ExoPlayerEngine 的构造签名（DataSource.Factory 来自 media3-datasource，
    // :media:engine 把它声明为 implementation，不向上传递）
    implementation(libs.media3.exoplayer)
    // M1-G（R18）：:app 是组合根，直接持有 MediaController / SessionToken 连后台播放服务
    // （:media:playback 把 media3 声明为 implementation，不向上传递）
    implementation(libs.media3.session)
    // M8-A（R13）：画中画比例要读 PlayerView.videoSize（:media:engine 把 media3-ui 声明为 implementation，不向上传递）
    implementation(libs.media3.ui)
    implementation(project(":media:thumbnail"))
    implementation(project(":media:tsext"))
    implementation(project(":media:ffmpeg"))
    implementation(project(":media:subtitle"))

    implementation(project(":feature:home"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:player-video"))
    implementation(project(":feature:player-audio"))
    implementation(project(":feature:viewer-image"))
    implementation(project(":feature:connections"))
    implementation(project(":feature:settings"))
    // R20：应用内更新的状态机与设置页区块
    implementation(project(":feature:update"))
    // R14：语音识别模型的下载与管理界面（设置页区块）

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
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
