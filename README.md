# EchoMind · 本地离线 AI 听书 App

Android 原生（Kotlin + Jetpack Compose + Media3 + Room）听书应用。导入本地 MP3 书解 → 自动书架 → 播放并记住每一首的进度 → **完全离线的本地语音识别**生成带时间戳字幕 → 播放时七句字幕实时跟随高亮。

无服务器、无账号、无云端 ASR。首次模型准备完成后，核心功能在飞行模式下可用。

## 当前状态（诚实版）

| 能力 | 状态 |
| --- | --- |
| 工程骨架 / Compose 导航 / 构建产物 | **DONE**：`assembleDebug` 通过，已在 API 36 arm64 设备实测启动 |
| CI | **PARTIAL**：GitHub Actions（私有库 `xinchentechnote/EchoMind`）每次 push/PR 跑 `assembleDebug` + 全部 JVM 单测并上传 APK，已跑绿（run 38632644725，那一次是 83 项，`BUILD SUCCESSFUL in 5m36s`）；本机现在是 **118 项 JVM 单测 + 52 项仪器化测试**，新增部分要等下一次 push 才由 CI 复验。**仪器化测试不在 CI 里**（没有模拟器，原因与代价见 `docs/environment.md` §8；设备侧真实 URL 下载那 4 项同样只能本机跑） |
| 文件夹导入、书架 | **PARTIAL**：SAF 授权持久化、递归扫描、去重/变更/消失/复活、时长提取、标题可改、搜索、收藏、继续上次收听卡片，均已在模拟器用真实 mp3 实测。**缺**：文件夹管理/重新授权页、扫描取消与进度、行内改名 → 见 `docs/roadmap.md`「Phase 3 遗留」 |
| 播放、后台播放、播放记忆与统计 | **PARTIAL**：Media3 ExoPlayer + `MediaSessionService` 后台播放、±15s、每首独立倍速、上一首/下一首、焦点处理；按记忆位置起播、有效次数（≥5s）、累计收听时长（倍速不放大）、完播判定、会话历史、崩溃恢复与重算对账，均已在模拟器实测（59→83 项 JVM 单测 + 48 项仪器化测试）。**缺**：耳机拔出（需真机）、设置页「重算统计」入口、前台长时间播放 → 见「Phase 4 遗留」 |
| 七句字幕窗口 | **PARTIAL**：v2 表 + 显式 `MIGRATION_1_2`（`MigrationTestHelper` 设备验证）、`SubtitleSynchronizer` 二分定位、3+1+3 窗口、点句跳转、切歌换字幕。**字幕内容 100% 来自手工导入的测试文件（`MANUAL_TEST`），不是 ASR 产物** → 见「Phase 5 遗留」 |
| 本地 ASR（sherpa-onnx + Paraformer） | **PARTIAL**：**ASR 端到端仍未完成，一次推理都没跑过**。已完成的是模型准备这一层：`asr/ModelCatalog`（清单，三个文件的字节数与 SHA-256 全部实测、`tokens.txt` 如实标「无官方校验值 + 本机钉住」）、`ModelVerifier`、`ModelManager` + `HttpFetcher`（`.part` → 比大小 → 整份 SHA-256 → 改名 → 记来源，多源回退、文件级跳过、删除），已挂进 `AppGraph` 并在模拟器上用**真实 hf-mirror URL** 跑通过一次下载。**缺**：`PcmDecoder`/`VadSegmenter`/`SherpaOnnxEngine`/`TranscriptCoordinator` 与 `work/` 全空、`transcript_job` 表未建、本地导入未实现、模型管理还没有 UI 入口 → 见 `docs/roadmap.md` Phase 6 |
| 端到端验收 | **TODO**：未开始 |

详见 [docs/roadmap.md](docs/roadmap.md)。

## 功能规划

- **导入与书架**：`ACTION_OPEN_DOCUMENT_TREE` 选文件夹，递归扫描 MP3，去重、变更检测、时长提取、标题可改、搜索、收藏。
- **播放**：Media3 ExoPlayer + `MediaSessionService` 后台播放、±15s、倍速（每首独立）、上一首/下一首、音频焦点与耳机拔出处理。
- **播放记忆与统计**：每首独立恢复位置；有效播放次数（≥5s 才算一次）、累计收听时长（只算真在播的时间，倍速不放大）、完播（95% 完成率 + 真实听过 90% 内容，拖到结尾不算完播）、收听会话历史、崩溃后不重复累计。
- **本地转录**：Silero VAD 切段（≤20s）→ Paraformer 中文离线模型逐字时间戳 → 按静音/长度切句 → 字幕入库；任务可取消、可重试、进度可见、重启可恢复。
- **七句字幕**：当前句 ± 3 句、二分定位、句间停顿不跳动、点击跳转。
- **设置**：模型准备/校验/删除、默认倍速、字幕字号、文件夹授权状态、统计规则说明、重算统计。

## 快速开始

```bash
source ~/android-dev/env.sh          # Android SDK / GRADLE_USER_HOME 环境
cd /Users/xinchen/workspace/EchoMind
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.xinchen.echomind/.MainActivity
./gradlew :app:testDebugUnitTest         # 纯逻辑单元测试
./gradlew :app:connectedDebugAndroidTest # 需要设备/模拟器
```

要求：JDK 17、Android SDK platform 37（目录名 `android-37.0`）、build-tools 37、Gradle 9.6.0（wrapper 自动取）、AGP 9.4.1。
网络：`maven.aliyun.com` 镜像优先（`repo1.maven.org` 在本机 DNS 超时）；**CI 上反过来必须用官方仓库**——runner 打阿里云镜像会 502，`settings.gradle.kts` 按 `GITHUB_ACTIONS` 分岔，原因见 `docs/environment.md` §3、§8。

**`app/libs/` 不在仓库里，所以 clone 之后必须先手工取回 AAR，否则 `:app` 配置阶段就报错并打印确切 URL/大小/SHA-256**（这是有意的：宁可构建失败也不静默下载 38 MB）：

```bash
mkdir -p app/libs && curl -sSL -o app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar
```

转录功能还需要中文 Paraformer + Silero VAD 模型（校验值与来源 URL 见 `docs/asr-design.md` §3）。
**取回模型目前只有代码路径（`asr/ModelManager`）没有界面**：设置页要到 Phase 6 第 6 项才落地，本地导入路径也还没实现，所以现在是「能下载、但点不到」。
**在拿到模型之前，App 的播放功能不受影响，字幕功能如实提示不可用。**

## 文档

| 文件 | 内容 |
| --- | --- |
| [docs/requirements.md](docs/requirements.md) | 功能需求（FR 编号）、MVP 边界、验收标准 |
| [docs/architecture.md](docs/architecture.md) | 包结构、分层规则、关键接口、数据流、线程模型、决策记录 |
| [docs/database-design.md](docs/database-design.md) | 7 张表的 ER 与字段、统计口径 SQL、ID 派生、迁移策略 |
| [docs/playback-design.md](docs/playback-design.md) | 播放器拓扑、会话生命周期、两种时长口径、焦点与后台、阈值清单 |
| [docs/asr-design.md](docs/asr-design.md) | 依赖形态、模型清单与校验值、转录流水线、时间戳方案与误差、降级 |
| [docs/environment.md](docs/environment.md) | 本机工具链/网络实测、依赖版本、KSP/Kotlin 踩坑、风险表 |
| [docs/roadmap.md](docs/roadmap.md) | 阶段状态（DONE/PARTIAL/TODO）与后续计划 |
| [docs/testing.md](docs/testing.md) | 命令、分层策略、已验证结果、待补用例、真机待办 |

## 技术栈与版本

Kotlin（AGP 9.4.1 built-in Kotlin）· Jetpack Compose（BOM 2026.09.00）+ Material 3 · AndroidX Media3 1.11.1（ExoPlayer + MediaSessionService）· Room 2.8.5（KSP 2.3.12）· WorkManager 2.12.0 · Coroutines 1.11.0 · 单 Gradle 模块 + 包分层。
`minSdk 26` / `targetSdk 36` / `compileSdk 37`，ABI **只打 `arm64-v8a`**（本机 AVD 与目标真机都是它；带 x86_64 会让调试包多 27 MB）。

## 应用标识

`applicationId` / `namespace`：`com.xinchen.echomind`；数据库 `echomind.db`。
