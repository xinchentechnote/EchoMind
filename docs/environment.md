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
| NDK | ~~未安装~~ → **r27c（27.2.12479018）**，2026-10-16 经 `sdkmanager "ndk;27.2.12479018"` 安装（自建 libwma.so 交叉编译用，见 §9） | `ls $ANDROID_HOME/ndk` |
| Homebrew lame | 4.0（`/opt/homebrew/opt/lame`），2026-10-16 `brew install lame`（自建 ffmpeg 带 mp3 编码器、造静音素材用） | `brew list lame` |
| 自建 FFmpeg | 6.1.1（`~/ffmpeg-build/`，四棵构建树：host 造 WMA 素材、host-mp3 造静音素材、build-arm64 交叉编 libwma、**build-lame-android 交叉编 LAME**（2026-10-24 新增，D44）） | `~/ffmpeg-build/build-host-mp3/ffmpeg -version`；`llvm-readelf -h ~/ffmpeg-build/build-lame-android/libmp3lame/.libs/libmp3lame.a` 必须是 AArch64 |
| 自建 libwma.so | **1,329,656 B，SHA-256 `e12081cbbadca796e8dfbafe8a4bf7a98a6c1cd3272d66cd77f9b38ad15513b5`**（2026-10-24 重建，D44：修超帧溢出 + 接入 LAME；FFmpeg 裁剪 + LAME 3.100 静态链，`-Wl,-z,defs`、16 KB 页对齐；脚本全文 `app/libs/wma/README.md`）。已作为 v0.1.4 release 资产 `libwma-arm64-v8a-e12081cb.so` 上传，CI 按哈希取回 | `ls -l app/libs/wma-jni/arm64-v8a/libwma.so && shasum -a 256 app/libs/wma-jni/arm64-v8a/libwma.so` |
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
- **反面结论（CI 实测，run 38631763968）**：阿里云镜像只对这台 Mac 必要，**在 GitHub runner 上有害**。
  runner 打 `maven.aliyun.com/repository/gradle-plugin` 与 `/public` 对 KSP 插件 marker 返回 **HTTP 502**
  （同一 URL 在本机 curl 是 200），而 Gradle 把 5xx 当解析失败、**不会继续找下一个仓库**，
  报出来的却是「Plugin … was not found」——错误信息误导人，真因藏在 `--info` 的 `Failed to get resource: GET. [HTTP 502]`。
  `settings.gradle.kts` 因此按 `GITHUB_ACTIONS` 切换仓库顺序。想在本地复现 CI 的解析路径：
  `GITHUB_ACTIONS=true ./gradlew --refresh-dependencies help`
  （注意：这条在本机跑不通——`repo.maven.apache.org` 对 **HEAD** 请求回 403，Gradle 正好先发 HEAD；
  同一台 runner 上 GET/HEAD 都是 200。这条只对本机成立，别处别照抄。）

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
| R1 | ~~sherpa-onnx 无 Maven 坐标~~ **已确证**：官方搜索命中 0 条，Android 只有 GitHub release AAR（v1.13.8） | 依赖以本地 `app/libs/*.aar` 引入，需手工取回 | ~~待取~~ **已取回**（2026-10-15）：`sherpa-onnx-static-link-onnxruntime-1.13.8.aar` 已在 `app/libs/`（不入库），构建脚本在缺失时打印确切 URL/大小/SHA-256，CI 按钉住哈希自动取回（§8）。选型与体积见 `asr-design.md` §2.1 |
| R2 | ~~Paraformer 时间戳~~ **已确证**：中文离线 Paraformer 只有 `zh-2023-09-14` 官方声明支持时间戳，其余明确不支持 | 模型清单必须钉死型号，误导入即拒绝 | `asr-design.md` §1-A4、§8 |
| R2b | 官方要求单段音频 ≤20s（长音频必须 VAD 切段），且 Paraformer 不产出标点 | 切句只能以时间/静音为依据，标点是展示层补齐 | `asr-design.md` §5、§6 记录误差与限制 |
| ~~R3~~ | ~~本机无 NDK~~ → **已装 r27c**（2026-10-16，自建 libwma.so 用；sherpa-onnx 仍用预编译 AAR） | — | 已解决 |
| R4 | 模型 232 MiB；**GitHub release 的速度漂移极大**：Phase 0 实测 45–51 KB/s，2026-10-15 同机复测 AAR **11.8–13.0 MB/s**（50.13 MB / 4.2s 与 38.69 MB / 3.0s，`curl -sSL` 直连跟 302），hf-mirror 的 243,371,218 B 模型 **39.2 MB/s / 6.2s** | 不能拿任一次测量当常数：慢的时候直连 GitHub 取 AAR 要十几分钟，早期那次 45 KB/s 更不能当禁令 | 大下载优先 hf-mirror，**每次把实测速度记进文档**；构建脚本打印确切 URL/大小/SHA-256，失败就报失败，不静默重试 |
| ~~R5~~ | ~~只有模拟器无真机~~ → **2026-10-18 真机已接入**：华为 nova 13（RNA-AL00，HarmonyOS 4.2，ADB 序列号 TTDUT22303016501，USB 调试已授权）——见 §9 真机小节 | BECOMING_NOISY / 真机 RTF / 覆盖安装升级路径可以验证了 | 待办清单里的真机项逐条销账 |
| R6 | MP3 → PCM 需自写解码（`MediaExtractor` + `MediaCodec`） | 解码与重采样是转录质量的地基 | Phase 6 拆成纯逻辑单测 + 设备验证两部分 |
| R7 | 无 release 签名配置 | 只能装 debug 包 | MVP 可接受；签名参数只放 `GRADLE_USER_HOME/gradle.properties`，不入仓库 |
| R8 | sherpa-onnx 在低内存设备的峰值 RSS 无官方数据 | 2–3GB RAM 机型可能 OOM | Phase 6 真机实测后再定 `numThreads`/段长，此前标注为未确证 |

## 7. 验证手段可用性结论

- Gradle 构建：可用（wrapper 9.6.0 已缓存，AGP 9.4.1 已缓存，androidx 走阿里云镜像）。
- JVM 单元测试：可用（`./gradlew :app:testDebugUnitTest`）。
- 仪器化测试：可用（有运行中的 `emulator-5554`，API 36 / arm64-v8a），Room 与 Media3 端到端验证具备条件。
- 真机验证：~~不具备~~ → **可用**（2026-10-18，华为 nova 13 / HarmonyOS 4.2 接入，见 §9；BECOMING_NOISY 等真机待办可以销账）
- GitHub Actions CI：**可用且已跑绿**（见 §8）。仪器化测试仍不在 CI 里——需要模拟器，本机这台才有。

## 8. GitHub Actions（远端 CI，2026-10-15 实测）

仓库：`https://github.com/xinchentechnote/EchoMind`（**PRIVATE**），默认分支 `main`，工作流 `.github/workflows/ci.yml`。

| 项 | 实测结论 | 怎么确证的 |
| --- | --- | --- |
| runner | `ubuntu-24.04` | 显式钉住而不是 `ubuntu-latest`，因为下一行的组件清单是按这个镜像查的 |
| 预装 Android SDK | `ANDROID_HOME=/usr/local/lib/android/sdk`，platforms 含 **`android-37.0`**，build-tools 含 **`37.0.0`** | `actions/runner-images` 的 `images/ubuntu/Ubuntu2404-Readme.md`（`gh api … -H Accept:application/vnd.github.raw` 读的，不是凭印象） |
| `sdkmanager` | **不在 PATH 上**，实际位于 `$ANDROID_HOME/cmdline-tools/<版本>/bin/`（镜像里是 cmdline-tools 12.0） | 第一次 CI 就是死在 `command -v sdkmanager`；所以现在正常路径只校验目录存在，缺组件时才用绝对路径回退安装 |
| 包名格式 | 与本机一致：API 37 的平台是 `android-37.0`（不存在 `android-37`） | Readme 的 Platforms 行 + 本机 `ls $ANDROID_HOME/platforms` |
| JDK | `actions/setup-java@v6` temurin `17.0.20-8`（`/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-8/x64`） | CI 日志 `准备 JDK 17` 步骤 |
| Gradle | wrapper 9.6.0 由 runner 现取 `services.gradle.org`（日志有 `Downloading …gradle-9.6.0-bin.zip`） | 第一次 CI 日志 |
| 冷启动耗时 | Gradle 首次 5m36s（整个 job 6m19s，无缓存）；构建 + 83 项 JVM 单测 + 上传 APK | run 38632644725 |
| AAR 取回 | `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` 在 runner 上从 GitHub release 取回，`sha256sum -c` 通过后放进 `app/libs/`；配 `actions/cache@v6` | 同 run 的「取回并校验 sherpa-onnx AAR」步骤 |
| APK 体积 | 上传的 artifact 压缩后 25,405,781 B（本机 `app-debug.apk` 40,794,372 B，内含 `lib/arm64-v8a/libsherpa-onnx-jni.so` 24,169,352 B） | run 的 artifact 行 + 本机 `unzip -l` |
| action 版本 | `checkout@v7` / `setup-java@v6` / `cache@v6` / `upload-artifact@v7` / `gradle/actions/setup-gradle@v6` | `gh api repos/<owner>/<repo>/releases/latest --jq .tag_name` 逐个查的，不是写记忆里的号 |
| 不覆盖的部分 | 仪器化测试（48 项，Room Migration/Repository）、真机验证、ASR 端到端 | CI 只跑 `assembleDebug` + `testDebugUnitTest`，这两项仍然必须在本地/设备上做（§2 的命令） |

踩过的两个坑（都会重复咬人，别再猜）：

1. **`gh run watch` 的退出码会被管道吃掉**：`gh run watch … | tail` 拿到的是 `tail` 的 0。要判定成败得把 watch 单独重定向后再取 `$?`，或者直接读 `gh run view --json conclusion`。
2. **「plugin not found」通常不是没找到，是某个仓库 5xx**（§3 最后一条）。排查方法是临时加一个探测工作流，在 runner 上对同一批 URL 打 `GET`/`HEAD` 状态码矩阵；探测完立刻删掉那个工作流。

## 9. 从零搭建与编译安装实录（2026-10-18 补记成文；过程均为本机实际执行）

> 这节是一份**可照抄的笔记**：环境准备 → 工具安装 → 编译 → 装到设备。所有版本号都是本机实测值。

### 9.1 环境准备（一次性）

```bash
# 0) 每开一个终端先做（否则 GRADLE_USER_HOME 污染别的工程，见 §2 的警告）
source ~/android-dev/env.sh

# 1) JDK 17（Temurin 17.0.18，系统已装）——AGP 9.4.1 跑在它上面，homebrew 的 gradle 9.4.1 跑在 JDK 25 上不能用
java -version   # → 17.0.18

# 2) Android SDK 已就位（~/android-dev/sdk）：platforms android-36/android-37.0、build-tools 35/36/37.0.0
ls $ANDROID_HOME/platforms $ANDROID_HOME/build-tools

# 3) 本轮新装：NDK r27c（只为自建 libwma.so 交叉编译；sherpa-onnx 用预编译 AAR 不需要它）
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.2.12479018"

# 4) 本轮新装：Homebrew lame（自建 ffmpeg 带出 mp3 编码器、造静音测试素材用）
brew install lame   # lame 4.0 → /opt/homebrew/opt/lame

# 5) 自建 FFmpeg 6.1.1（源码 ffmpeg.org 直连可下，sha256 8684f4b0…；三棵构建树见 §9.4）
mkdir -p ~/ffmpeg-build && cd ~/ffmpeg-build
curl -sSL -o ffmpeg-6.1.1.tar.xz https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz && tar xf ffmpeg-6.1.1.tar.xz
```

工程依赖（AGP 9.4.1 / Gradle wrapper 9.6.0 / built-in Kotlin 2.4.21 / KSP 2.3.12 / Room 2.8.5 / Media3 1.11.1 / WorkManager 2.12.0 / Compose BOM 2026.09.00）版本与镜像口径见 §3/§4——wrapper 会自动下载 Gradle 本体，依赖走阿里云镜像，**不需要手动装任何一项**。

### 9.2 编译与测试

```bash
source ~/android-dev/env.sh
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk（v0.1.1 起含内置模型 ≈267 MB）
./gradlew :app:testDebugUnitTest      # JVM 单测（266 项）

# 仪器化测试的两个前提：模拟器在线 + 素材/模型已在设备（推送见 §9.4）；
# 刻意不用 connectedDebugAndroidTest（跑完会卸应用清数据，见 §5c-8）
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e package com.xinchen.echomind \
  com.xinchen.echomind.test/androidx.test.runner.AndroidJUnitRunner
```

构建脚本自带三道闸（缺文件直接 GradleException 并打印来源，见 `app/build.gradle.kts`）：
sherpa-onnx AAR（38,691,998 B）、whisper/sense-voice 模型三件套（§9.4）、libwma.so（1,329,656 B，v0.1.4 资产 `libwma-arm64-v8a-e12081cb.so`）。
CI（GitHub Actions）会自动取回这几样；本机照抄 `.github/workflows/ci.yml` 里对应步骤即可。

### 9.3 装到模拟器 / 真机

**模拟器**（AVD 名 `drill36`，API 36 / arm64-v8a）：

```bash
$ANDROID_HOME/emulator/emulator -avd drill36 -port 5554 -no-snapshot-save   # 后台跑
adb install -r app/build/outputs/apk/debug/app-debug.apk                    # -r 覆盖安装保数据
adb shell am start -n com.xinchen.echomind/.MainActivity
```

**真机：华为 nova 13（RNA-AL00，HarmonyOS 4.2）**——2026-10-18 实测接入，序列号 `TTDUT22303016501`：

```bash
# 手机侧（一次性）：
#   设置 → 关于手机 → 「版本号」连点 7 次 → 返回找到「系统和更新 → 开发人员选项」
#   → 打开「USB 调试」；HarmonyOS 会弹「仅充电时禁止 ADB」类提示，选传输文件模式
#   → （要用 uiautomator/模拟点击时再开）「USB 调试（安全设置）」：允许模拟点击与输入
# 插上 USB → 手机弹「是否允许 USB 调试（RSA 指纹）」→ 允许
adb devices -l
# → TTDUT22303016501   device usb:… product:RNA-AL00 model:RTE_AL00 device:HWRTE
adb -s TTDUT22303016501 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TTDUT22303016501 shell am start -n com.xinchen.echomind/.MainActivity
adb -s TTDUT22303016501 logcat --pid=$(adb -s TTDUT22303016501 shell pidof com.xinchen.echomind)
```

Android Studio 侧：打开工程根目录 → 右上设备选择器选中 nova 13 → Run ▶（等价于上面两条 install+start）。
Logcat 过滤 `package:com.xinchen.echomind`。

两条红线：
- **HarmonyOS NEXT（5.0）不兼容 APK**；nova 13 出厂是 HarmonyOS 4.2（Android 应用兼容层，`getprop ro.build.version.sdk` 会报出对应 API 级别）——买机/升级前认准这个区别；
- 首次装 267 MB 的包要等一会；覆盖安装（`-r`）数据保留，签名一致（同一台构建机）即可直升。

### 9.4 模型与素材推送（转录功能的数据准备）

模型不入仓库也不进 APK；App 首次启动会引导去设置页下载，开发期可用 adb 直推（快得多）：

```bash
# 模型在 App 私有目录 filesDir/models/<spec.id>/<fileName>，adb 直写不了 → 走 /data/local/tmp + run-as
adb push app/libs/models/<目录>/<文件> /data/local/tmp/x.bin
adb shell "run-as com.xinchen.echomind sh -c 'mkdir -p files/models/<spec.id> && cat /data/local/tmp/x.bin > files/models/<spec.id>/<文件名>'"
```

四套模型（清单与哈希的唯一出处是 `asr/ModelCatalog.kt`）：中文 Paraformer int8（243 MB）、Silero VAD（0.6 MB）、
SenseVoice 多语种 int8（226 MB）、Whisper base int8（152 MB，**不在下载列表**，仅打样）。
仪器化测试素材推到 `/sdcard/Android/data/com.xinchen.echomind/files/asr-fixtures/`（这个目录可 adb 直写）：
`pcm-fixture-real.mp3`（真歌）、`pcm-fixture-01.mp3`（30 s 静音，自建 ffmpeg 造）、`en-tts.wav`（macOS `say` 造）、
`user-test.mp3`（用户 56 分钟书解）。

### 9.5 本轮新增的坑（都有事故现场）

1. **`adb shell "run-as … sh -c '…'"` 的引号**：外层双引号、内层单引号才能活；只套一层单引号时本地 shell
   会吃掉引号，设备端 `sh -c` 只收到第一个词（表现为「命令没报错但什么都没发生」）。最稳的写法是 run-as
   后直接跟命令、不用 sh -c；带重定向的整条命令必须整体双引号包裹。
2. **screencap 截图是缩放显示**（900×2000），而 `input tap` 用设备像素（1080×2400）——看图估坐标永远差 1.2 倍。
   点击坐标一律以 `uiautomator dump` 的 bounds 为准。
3. **uiautomator dump 会返回旧文件**：连续 dump 时若界面状态没变，拉下来的还是上一份——
   判据是文件字节数完全相同；对策是 `rm` 设备侧旧文件后再 dump，或改用 screencap 目检。
4. **链式命令里 gradle 的失败会被 `tail`/`grep` 吞掉**：`./gradlew … | tail -1 && adb install …` 在构建失败时
   照样装旧 APK。构建状态必须显式判定后再进下一步（`isDecoder` 编译错就这么被吞过一次）。
5. **测试 APK 改了必须 `assembleDebugAndroidTest` + 重装**，否则 `am instrument -e class` 静默返回
   `OK (0 tests)`——旧包里没有新类，不报错只报 0。
6. GitHub Actions run 步骤里 `GITHUB_TOKEN` 不会自动导出（`set -u` 下直接 unbound）：
   用 `env: GH_TOKEN: ${{ github.token }}` 注入，或用 runner 预装的 `gh`（见 ci.yml 的 libwma 步骤）。
7. **autotools 交叉编译时 `CC=` 留空会悄悄编成宿主目标**（2026-10-24，LAME 那次）：configure 不报错、make 不报错，
   链接 .so 时只在警告里出现 `archive member 'xxx.o' is neither ET_REL nor LLVM bitcode`，产物里宿主符号全部悬空
   ——装到设备上才炸 `UnsatisfiedLinkError`。对策两道：configure 显式给 `CC=$TC/aarch64-linux-android26-clang`，
   链接加 `-Wl,-z,defs` 让悬空符号在链接期就失败；编完先 `llvm-readelf -h … | grep Machine` 确认是 AArch64。
