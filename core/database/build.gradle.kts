plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.gua123.mediagate.core.database"
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
        // 离线迁移校验单测（MigrationSqlTest）只读 schemas/*.json，不碰 android.*；
        // 万一碰到未 mock 的桩方法，返回默认值而不是抛 "not mocked"
        unitTests.isReturnDefaultValues = true
    }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 迁移离线校验：读 Room 导出的 schema JSON，与手写 DDL 逐条比对（M7-B / R19 的 2→3）
    testImplementation(libs.junit)
}
