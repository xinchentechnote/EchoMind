import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xinchen.echomind"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xinchen.echomind"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 首版优先 arm64-v8a 真机；x86_64 只为模拟器/仪器化测试保留。
        // sherpa-onnx 的 ABI 覆盖情况确认后可能要收窄（见 docs/asr-design.md）。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            // 本机 lint-gradle 不在 Gradle 缓存里，--offline 下 release 构建会因 lint 失败
            // （实测记录见 docs/environment.md 第 5 节第 4 条）
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // domain 层坚持不 import android.*，这里不依赖该开关来掩盖问题
            isReturnDefaultValues = false
        }
    }

    lint {
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }

    // Room 导出的 schema 进版本库，用于 Migration 的编译期校验测试
    // （AGP 9 里 AndroidSourceDirectorySet.srcDir 已弃用，改用 directories 可变集合）
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

/**
 * 仪器化测试把 kotlinx-serialization 钉到 Room 编译时用的版本。
 *
 * 实测原因：Compose BOM 2026.09.00 用 `{strictly 1.7.3}` 约束 serialization-core，
 * 而 room-testing 2.8.5 解析到 json 1.8.1，于是 core/json 混版；1.7.3 里
 * `GeneratedSerializer.typeParametersSerializers()` 还是抽象方法，Room 预编译的
 * `FieldBundle$$serializer` 不再实现它 → `MigrationTestHelper` 读 schema JSON 直接 AbstractMethodError。
 * App 运行时不走 serialization，所以只动 androidTest 的配置，不影响主 APK 依赖。
 */
val serializationForce = listOf(
    "org.jetbrains.kotlinx:kotlinx-serialization-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
).map { "$it:${libs.versions.kotlinxSerialization.get()}" }.toTypedArray()

configurations.matching { it.name.contains("AndroidTest") }.all {
    resolutionStrategy.force(*serializationForce)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.arch.core.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui)
}
