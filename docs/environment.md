# Phase 0 环境与依赖核查记录

> 核查时间：2026-10-14。本文记录的是**在这台机器上实测到**的事实，作为后续版本选型与验证的基线。
> 凡未在此实测的依赖版本，代码里引入前必须先补测。

## 1. 工作区

- 路径：`/Users/xinchen/workspace/EchoMind`
- 核查时为空目录，非 Git 仓库；本次由项目初始化 `git init`（按用户确认，只做 init，不做首次提交）。
- 工程标识（用户确认）：`rootProject.name = EchoMind`，`applicationId` / `namespace` = `com.xinchen.echomind`。

## 2. 本机工具链（实测）

| 项 | 值 | 实测方式 |
| --- | --- | --- |
| OS | macOS，Darwin 24.6.0，arm64 | `uname` |
| JDK | Temurin 17.0.18（`/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`） | `java -version` |
| Android SDK | `/Users/xinchen/android-dev/sdk` | `env.sh` 导出的 `ANDROID_HOME` |
| 已安装 platform | `android-36`、`android-37.0`（注意 API 37 的目录名带 `.0`，不存在 `android-37`） | `ls $ANDROID_HOME/platforms` |
| 已安装 build-tools | 35.0.0、36.0.0、37.0.0 | `ls $ANDROID_HOME/build-tools` |
| NDK | **未安装** | `ls $ANDROID_HOME \| grep ndk` 为空 |
| Gradle wrapper 发行缓存 | `gradle-8.14.6`、`gradle-9.4.1`、`gradle-9.6.0` | `ls $GRADLE_USER_HOME/wrapper/dists` |
| 系统 gradle | `/opt/homebrew/bin/gradle` 9.4.1（跑在 homebrew openjdk 25 上） | `gradle --version` |
| 运行中的设备 | `emulator-5554`：API 36 / arm64-v8a / 1080x2400 | `adb devices -l`、`getprop` |
| 磁盘可用 | 约 76Gi | `df -h` |

**必须**：任何 `./gradlew` / `adb` / `sdkmanager` 调用前先 `source ~/android-dev/env.sh`，否则 `GRADLE_USER_HOME` 回退到 `~/.gradle`（那是别的项目的 2.2G 缓存，不属于本工程）。

## 3. 网络（本机实测口径）

- `repo1.maven.org`（`mavenCentral()` 默认主机）DNS 解析超时；`search.maven.org` 同样超时（实测 30s 无响应）。
- 可用镜像，且是本项目 `settings.gradle.kts` 里的第一顺位：
  - `https://maven.aliyun.com/repository/google`
  - `https://maven.aliyun.com/repository/public`
  - `https://maven.aliyun.com/repository/gradle-plugin`
- 镜像的目录列表 404 属正常，只有取具体 `maven-metadata.xml` / artifact 才准。
- `dl.google.com` 可达但约 400KB/s：**模型文件这类大下载要走后台任务并预留时间**，也意味着 CI/首次构建可能很慢。

## 4. 依赖版本（均已在阿里云镜像实测 `maven-metadata.xml`）

| 依赖 | 选用版本 | 实测来源 |
| --- | --- | --- |
| AGP（Android Gradle Plugin） | 9.4.1 | 本机 Gradle 缓存已有 `com.android.tools.build:gradle` 9.4.1（另有 8.13.2） |
| Gradle wrapper | 9.6.0 | AGP 9.4.1 要求 Gradle ≥ 9.6.0；wrapper dist 已本机缓存 |
| Kotlin（AGP 9 内置编译器 + compose 编译器插件版本） | 2.4.21 | 同机 `fund-drill` 工程已用该组合构建通过 |
| Compose BOM | 2026.09.00 | `androidx/compose/compose-bom` metadata |
| androidx.media3（ExoPlayer / MediaSession） | 1.11.1 | `androidx/media3/media3-exoplayer` metadata |
| Room | 2.8.5 | `androidx/room/room-runtime` metadata |
| WorkManager | 2.12.0 | `androidx/work/work-runtime-ktx` metadata |
| androidx.core:core-ktx | 1.19.1 | metadata |
| androidx.lifecycle | 2.11.0（最新稳定版；`latest` 标签指向 2.12.0-alpha04，不用） | metadata 版本列表 |
| androidx.activity:activity-compose | 1.13.0 | metadata |
| androidx.navigation:navigation-compose | 2.10.2 | metadata |
| kotlinx-coroutines（android / test） | 1.11.0 | `org/jetbrains/kotlinx/kotlinx-coroutines-android` metadata |
| androidx.documentfile:documentfile | 1.1.0 | `androidx/documentfile/documentfile` metadata |
| androidx.compose.material:material-icons-core | 由 Compose BOM 管理，解析到 1.7.8 | BOM 2026.09.00 的 constraint POM（material3 不再传递依赖图标集，需显式引入） |
| junit:junit | 4.13.2 | `junit/junit` metadata |
| androidx.test:runner / androidx.test:core | 1.7.0 / 1.7.0 | metadata |
| androidx.test.ext:junit | 1.3.0 | metadata |
| androidx.arch.core:core-testing | 2.2.0 | metadata |

后 5 行（测试依赖）是 Phase 3 引入的：版本在阿里云镜像 metadata 实测存在，且 `:app:testDebugUnitTest` 与 `:app:connectedDebugAndroidTest` 均解析、编译、运行通过。

版本统一在 `gradle/libs.versions.toml`，模块内不写死版本号。

## 5. 已知工程约束（同机已验证结论，直接沿用）

1. **AGP 9 内置 Kotlin**：`:app` 模块的 `plugins {}` 里不能再 `alias(libs.plugins.kotlin.android)`，留着是硬错误；只 apply `com.android.application` 与 `org.jetbrains.kotlin.plugin.compose`。
2. Kotlin 2.4 起 `android { kotlinOptions { jvmTarget = "17" } }` 已从警告升级为 error，改用 `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`。
3. 最新 androidx 要求 `compileSdk = 37`，否则 `checkDebugAarMetadata` 报错；`targetSdk = 36`（与本机构建/验证设备一致）。
4. **离线构建边界**：`com.android.tools.lint:lint-gradle` 不在本机缓存，所以 `assembleRelease` / `lintDebug` 在 `--offline` 下必失败。已在 `android { lint { checkReleaseBuilds = false } }` 规避；`assembleDebug` 与 `testDebugUnitTest` 不受影响。
5. debug keystore 落在 `~/android-dev/avd/debug.keystore`（因为 `env.sh` 导出了 `ANDROID_AVD_HOME`）。**不要动 `~/android-dev`**，签名身份变了会导致已装 App 必须卸载重装、本地数据库丢失。
6. `build-tools/37.0.0` 已在 `env.sh` 的 PATH 里，`apksigner`/`aapt2` 可直接调用。

### 5b. Room / KSP 与 Kotlin 版本路线（Phase 2 实测，踩坑记录）

1. 先试 `android.builtInKotlin=false` + 独立 KGP `2.3.12` → **失败**：`Plugin [id: 'org.jetbrains.kotlin.android', version: '2.3.12'] was not found`。
   实测 `kotlin-gradle-plugin` 可用版本是 `2.3.20`、`2.3.21`、`2.4.x`（**不存在 2.3.12**）；KSP 的 `2.3.0…2.3.12` 是 **KSP 自己的独立编号**，不能当 KGP 版本用。
2. 从 AGP 9.4.1 的 pom 读到 `dependencyManagement` 钉住 `com.google.devtools.ksp:symbol-processing-gradle-plugin:2.2.10-2.0.2`，且 AGP jar 内有文案
   `The 'org.jetbrains.kotlin.kapt' plugin is not compatible with built-in Kotlin support.`
   → **built-in Kotlin + KSP 是官方支持路径**，kapt 不可用。
3. 采用 **AGP 9.4.1 built-in Kotlin + KSP 2.3.12 + Room 2.8.5**，实测通过：`:app:kspDebugKotlin` 生成 `EchoMindDatabase_Impl.kt` / `AudioBookDao_Impl.kt`，`:app:assembleDebug` BUILD SUCCESSFUL。
4. Gradle 9.6 Kotlin DSL 的两处破坏性变更（直接判 error，不是 warning）：
   - `AndroidSourceDirectorySet.srcDir(...)` 弃用 → `assets.directories.add("$projectDir/schemas")`。
   - `freeCompilerArgs += listOf(...)` 无法解析 → `freeCompilerArgs.addAll(...)`。

### 5c. SAF / 设备侧实测约束（Phase 3 踩坑）

1. **`takePersistableUriPermission` 的参数只能带模式位**。传 `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION`（0x41）在 API 36 上抛
   `IllegalArgumentException("Requested flags 0x41, but only 0x3 are allowed")` —— 平台会把它和「该授权可持久化的模式」(0x3) 比较。`FLAG_GRANT_PERSISTABLE_URI_PERMISSION` 属于**请求意图**，不属于取授权调用。
2. androidx `activity:1.13.0` 的 `ActivityResultContracts.OpenDocumentTree.createIntent` **一个授权标志都不加**（`javap -c` 反编译 transform jar 看到只设 action 与 `EXTRA_INITIAL_URI`）。所以「选择器返回后 `takePersistable` 失败」不是偶发，而是必然 → 项目自定义 `OpenPersistableDocumentTree` 契约，在请求意图里加上 `READ|PERSISTABLE`。
3. 授权状态的可查手段：`adb shell dumpsys activity permissions | grep -A2 <uri>`，成功持久化后是 `mode=0x3 owned=0x3 persistable=0x3 persisted=0x1`。`ContentResolver.persistedUriPermissions` 是同一份数据的 App 侧视图。
4. `adb shell input text` 只支持 ASCII，中文直接 NPE（`InputShellCommand.sendText`）→ 设备上的输入类用例只能用 ASCII 关键词，中文路径靠仪器化测试。
5. `uiautomator dump` 失败时不覆盖目标文件，且刚启动/刚交互后常返回 `null root node` → 必须「删设备侧旧文件 → dump → pull → 校验 `</hierarchy>` → 重试」。
6. `adb shell mv 'a b' c` 的引号会在设备端被重新拆分（toybox mv 收到 4 个参数报 `not directory`）→ 整条命令作为一个字符串传：`adb shell "mv 'a b' c"`。
7. Room 的 WAL：`run-as cat databases/x.db` 只取主库会读到旧快照，必须连 `-wal`/`-shm` 一起取。
8. 仪器化测试跑完，被测 App 会被卸载且数据（含 SAF 持久化授权）清空 → 设备验证排在仪器化测试之后。
9. 编译期约束：仪器化测试的方法名不能含空格（DEX 名字校验），中文标识符无空格即可；JVM 单测用反引号 + 空格没问题。
10. `material-icons-core` 1.7.8 里 `Icons.Outlined.Star` 与 `Icons.Filled.Star` 几何完全相同（渲染都是实心），用「描边 vs 实心」表达收藏态是无效的 → 改用 ★/☆ 字符。

## 6. 风险与阻塞点

| # | 风险/阻塞 | 影响 | 处置 |
| --- | --- | --- | --- |
| R1 | ~~sherpa-onnx 无 Maven 坐标~~ **已确证**：官方搜索命中 0 条，Android 只有 GitHub release AAR（v1.13.8） | 依赖以本地 `app/libs/*.aar` 引入，需手工取回 | `asr-design.md` §2；构建脚本在 AAR 缺失时打印确切 URL 与大小 |
| R2 | ~~Paraformer 时间戳~~ **已确证**：中文离线 Paraformer 只有 `zh-2023-09-14` 官方声明支持时间戳，其余明确不支持 | 模型清单必须钉死型号，误导入即拒绝 | `asr-design.md` §1-A4、§8 |
| R2b | 官方要求单段音频 ≤20s（长音频必须 VAD 切段），且 Paraformer 不产出标点 | 切句只能以时间/静音为依据，标点是展示层补齐 | `asr-design.md` §5、§6 记录误差与限制 |
| R3 | 本机无 NDK | 无法从源码编译 sherpa-onnx | 用官方预编译 AAR（自带 arm64 `.so`），不需要 NDK |
| R4 | 模型 232 MiB；**GitHub release 的速度会大幅漂移**：Phase 0 实测 45–51 KB/s，2026-10-15 复测 `sherpa-onnx-1.13.8.aar`（50.13 MB）达 **3.46 MB/s**（`curl -r 0-6000000` 取 6 MB，HTTP 206，直连需 `-L` 跟 302） | 不能拿任一次测量当常数：慢的时候直连 GitHub 取 AAR 会要十几分钟 | 下载前先测一段区间速度；模型仍优先走 hf-mirror.com（实测 5.86 MB/s）+ 本地导入路径 |
| R5 | 只有模拟器（API 36 / arm64-v8a）无真机 | 焦点抢占、BECOMING_NOISY、低内存峰值无法验证 | `testing.md` 单列真机待办清单 |
| R6 | MP3 → PCM 需自写解码（`MediaExtractor` + `MediaCodec`） | 解码与重采样是转录质量的地基 | Phase 6 拆成纯逻辑单测 + 设备验证两部分 |
| R7 | 无 release 签名配置 | 只能装 debug 包 | MVP 可接受；签名参数只放 `GRADLE_USER_HOME/gradle.properties`，不入仓库 |
| R8 | sherpa-onnx 在低内存设备的峰值 RSS 无官方数据 | 2–3GB RAM 机型可能 OOM | Phase 6 真机实测后再定 `numThreads`/段长，此前标注为未确证 |

## 7. 验证手段可用性结论

- Gradle 构建：可用（wrapper 9.6.0 已缓存，AGP 9.4.1 已缓存，androidx 走阿里云镜像）。
- JVM 单元测试：可用（`./gradlew :app:testDebugUnitTest`）。
- 仪器化测试：可用（有运行中的 `emulator-5554`，API 36 / arm64-v8a），Room 与 Media3 端到端验证具备条件。
- 真机验证：**不具备**，见 R5。
