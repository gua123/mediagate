plugins {
    alias(libs.plugins.android.library)
}

/**
 * 自研视频播放引擎的 native 内核（**2026-10-06 用户拍板**：
 * 「视频引擎只采用一套，不要来回切换，自研一套视频播放引擎，尽可能适合全部视频文件类型」）。
 *
 * 这个模块只负责**把 C++ 内核编进 APK 并暴露 JNI 边界**；播放逻辑在 src/main/cpp 里长出来。
 * 目前是 M1 骨架：证明「AGP9 内置 Kotlin + NDK r30 + CMake 4.1.2」这条链路能编出可加载的 .so。
 */
android {
    namespace = "io.github.gua123.mediagate.media.enginenative"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // 自研引擎的 JNI 桥要原样保留（native 按名字 GetMethodID；被 R8 改名只在 release 包炸）
        consumerProguardFiles("consumer-rules.pro")
    }

    // 只出 arm64-v8a（与全项目口径一致），并交给 CMake 外部构建
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // 引擎是 C++17；异常/RTTI 保留（FFmpeg 的错误路径要能抛）
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti", "-Wall")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 工具链里的 CMake（.toolchain/android-sdk/cmake/4.1.2）
            version = "4.1.2"
        }
    }

    ndkVersion = libs.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * **必须**把自研 FFmpeg 的 .so 一起打进 APK**：CMake 里声明的是 IMPORTED 库，只负责链接，
 * AGP 不会自动把它收进包 ⇒ 不显式加这一行，运行时就是 UnsatisfiedLinkError。
 * 带 -mg 后缀是为了与 ffmpeg-kit 的 libav*.so 区分（过渡期两者并存）。
 * AGP 9 用 androidComponents 的静态源目录 API（老的 sourceSets["main"].jniLibs 会类型转换失败）。
 */
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            // 目录下必须有按 ABI 命名的子目录（arm64-v8a/），由 scripts/build-ffmpeg-android.sh 同步
            "${rootProject.projectDir}/.toolchain/ffmpeg-jni",
        )
    }
}

dependencies {
    implementation(project(":core:common"))
    // PlayerEngine 契约（M4：自研引擎接进统一播放内核抽象）
    implementation(project(":media:engine"))
    // RandomAccessSource 出现在 RandomAccessMediaSource 的公开构造签名里（M4 由播放会话创建），故用 api 传递
    api(project(":data:storage-api"))
    // 把挂起的数据层桥接成 native 要的阻塞读（RandomAccessMediaSource 里用 runBlocking）
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 用真实临时文件 + FileStorageBackend 验证"引擎读数据"这条链路（不需要设备）
    testImplementation(project(":data:storage-local"))
}

/**
 * 源码级护栏与"宿主单测"会在**测试运行期**读这些文件（CMakeLists / C++ 源码 / 宿主测试源），
 * 而它们不是 Kotlin 的编译输入 ⇒ 只改 C++ 不改 Kotlin 时，Gradle 会判定"测试是最新的"直接跳过，
 * 护栏就白设了（本回合真踩到：把 aaudio 链接删掉，测试却仍然"绿的"）。
 * 显式声明成输入之后，动 C++ 就会重跑测试。
 */
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/cpp"), fileTree("src/test/cpp"))
        .withPropertyName("nativeSources")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
