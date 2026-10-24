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
        versionCode = 5
        versionName = "0.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // D42 后多语种打样不属于产品回归。不能用 JUnit @Ignore：当前 AGP 9.4.1
        // 会把 runner 的 ignored 事件错误写成空 failure；用 runner 过滤器让标准报告只统计正式用例。
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.xinchen.echomind.asr.MultilingualPilotOnly"

        // 只打 arm64-v8a：真机与本机模拟器 `emulator-5554` 的 ro.product.cpu.abi 实测都是 arm64-v8a，
        // 而 sherpa-onnx 的 x86_64 那份 so 未压缩就有 27.4 MB，留着只是白白让调试包翻倍。
        // （早期还没接 AAR 时这里带 x86_64 是给 x86 模拟器兜底，本机 AVD 是 arm64，从来用不上。）
        ndk {
            abiFilters += listOf("arm64-v8a")
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
        // WMA 解码的 JNI 库（libwma.so）与 sherpa AAR 同一套规矩：二进制不入仓库，
        // 本机手工放 / CI 按 release 资产哈希取回（见 .github/workflows/ci.yml）
        getByName("main") {
            jniLibs.directories.add("$projectDir/libs/wma-jni")
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

/**
 * sherpa-onnx 的官方 Android AAR 是二进制依赖，不进仓库（`.gitignore` 排掉了 `app/libs/`）。
 * 缺失时直接给出确切 URL、期望大小和本机钉住的 SHA-256，不许让人对着一条 unresolved reference 猜（AGENTS.md §5）。
 * 静态链接版（`sherpa-onnx-static-link-onnxruntime-…`）把 ONNX Runtime 烧进 `libsherpa-onnx-jni.so`，
 * arm64 只有一个 so（24,169,352 B），不用再自己 exclude 用不到的 c/cxx API 库。
 */
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")
if (!sherpaAar.exists()) {
    throw GradleException(
        """
        |缺少 sherpa-onnx AAR：$sherpaAar
        |  URL     : https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar
        |  期望大小: 38,691,998 B
        |  SHA-256 : b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471
        |            （官方 release 资产的 digest 字段就是它，`gh api repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8` 可复核）
        |  取回：mkdir -p app/libs && curl -sSL -o <上面的路径> <上面的 URL>
        """.trimMargin()
    )
}

/**
 * 模型随安装包内置（FR-511，2026-10-15）：三个文件放在 `assets/models/<specId>/`，不入仓库
 * （`.gitignore` 排掉 `app/src/main/assets/models/` 与 `*.onnx`），运行时由 `ModelManager.ensureProvisioned`
 * 从 assets 拷进 filesDir 并逐文件校验。构建期只查**存在与字节数**（stat 便宜；配置阶段跑 SHA-256
 * 会让每次构建慢 1~2 秒）——完整哈希由 CI 取回时 `sha256sum -c` 与运行时就位时各做一遍，两道闸都别省。
 * 缺失时给出确切来源，不许静默打出一个没模型的 APK（AGENTS.md §5）。
 */
data class BundledModelFile(val relativePath: String, val expectedBytes: Long)

val bundledModelFiles = listOf(
    BundledModelFile(
        "src/main/assets/models/paraformer-zh-2023-09-14-int8/model.int8.onnx",
        243_371_218L,
    ),
    BundledModelFile(
        "src/main/assets/models/paraformer-zh-2023-09-14-int8/tokens.txt",
        75_756L,
    ),
    BundledModelFile(
        "src/main/assets/models/silero-vad/silero_vad.onnx",
        643_854L,
    ),
)

bundledModelFiles.forEach { model ->
    val f = file(model.relativePath)
    if (!f.exists() || f.length() != model.expectedBytes) {
        throw GradleException(
            """
            |缺少或大小不符的模型文件：${f.absolutePath}（期望 ${model.expectedBytes} B）
            |  模型不入仓库（.gitignore），按清单从上游取回后放进 assets：
            |  model.int8.onnx : https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/model.int8.onnx
            |                    SHA-256 f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945（官方）
            |  tokens.txt      : https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/tokens.txt
            |                    SHA-256 59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6（本机钉住，无官方值）
            |  silero_vad.onnx : https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
            |                    SHA-256 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6（官方）
            |  CI 会自动取回并校验；本机可照抄 .github/workflows/ci.yml 里「取回并校验内置模型」一步
            """.trimMargin()
        )
    }
}

/**
 * libwma.so（WMA 解码 + LAME mp3 编码，D40/D44）：FFmpeg 6.1.1 最小裁剪与 LAME 3.100 静态链进
 * `app/src/main/cpp/wma_jni.c`。与 sherpa AAR 同一套规矩——缺失时打印确切来源与哈希，不静默失败（AGENTS.md §5）。
 * 构建脚本与许可证（LGPL-2.1-or-later）记录在 `app/libs/wma/README.md` 与 `docs/architecture.md` D40/D44。
 */
val wmaSo = file("libs/wma-jni/arm64-v8a/libwma.so")
if (!wmaSo.exists() || wmaSo.length() != 1_329_656L) {
    throw GradleException(
        """
        |缺少或大小不符的 libwma.so：$wmaSo（期望 1,329,656 B）
        |  SHA-256 : e12081cbbadca796e8dfbafe8a4bf7a98a6c1cd3272d66cd77f9b38ad15513b5（2026-10-24 本机构建，含 LAME 与坏包容错）
        |  来源：尚未发布为 release 资产（上一版 v0.1.1 的 libwma-arm64-v8a-d9abb58d.so 没有 LAME 符号、且有 32 KB 超帧溢出缺陷，不要再用）
        |  自建：照抄 app/libs/wma/README.md 的三段脚本（FFmpeg 裁剪交叉编译 → LAME 交叉编译 → 链 wma_jni.c）
        """.trimMargin()
    )
}

dependencies {
    implementation(files(sherpaAar))

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
