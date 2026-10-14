# EchoMind 项目开发规范（给 AI 编码代理）

本文件是**持续开发约束**，不是入门介绍。开始任何改动前先读这里，再读 `docs/roadmap.md` 确认当前阶段。

## 1. 构建与验证（必须照做）

```bash
source ~/android-dev/env.sh        # 第一步，永远不能省
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # 需要 emulator-5554 或真机
```

- 不 `source` 就会把依赖写进 `~/.gradle`（那是用户其他项目的 2.2G 缓存，**不要污染也不要清理**）。
- 不要直接用 `/opt/homebrew/bin/gradle`（9.4.1 跑在 homebrew JDK 25 上，AGP 不接受）。
- 网络：`repo1.maven.org` 与 `search.maven.org` 在本机 DNS 超时；用 `maven.aliyun.com/repository/{google,public,gradle-plugin}` 或 `repo.maven.apache.org`。GitHub release 二进制只有 ~50 KB/s，模型走 `hf-mirror.com`（实测 5.9 MB/s），`huggingface.co` 超时。
- **UI/交互改动必须在模拟器上实测**（安装 → 启动 → `uiautomator dump` → 截图），不能只看代码就说「应该能跑」。`uiautomator dump` 失败时不会覆盖目标文件：先 `rm -f` 再 dump，并确认输出含 `</hierarchy>`。
- 不要杀用户已起的进程（含 `emulator-5554`、用户的 gradle daemon）。需要停只停自己启动的后台任务。

## 2. 版本与依赖的硬约束（踩过）

- AGP 9.4.1 + Gradle 9.6.0 + **built-in Kotlin**。`:app` 的 `plugins {}` 里**不要**加 `org.jetbrains.kotlin.android`（硬错误）；`kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }` 保留。
- Room 只能走 **KSP**（`kapt` 被 AGP 判定与 built-in Kotlin 不兼容）。KSP 版本用 catalog 里的 `2.3.12`；**KSP 的 2.3.x 是它自己的编号，不存在 Kotlin 2.3.12**，别据此去「对齐 KGP 版本」。
- Gradle 9.6 的 Kotlin DSL 里两处弃用即报错：`AndroidSourceDirectorySet.srcDir(...)` → `directories.add(...)`；`freeCompilerArgs += listOf(...)` → `addAll(...)`。
- `compileSdk = 37`（SDK 目录名是 `android-37.0`）、`targetSdk = 36`、`minSdk = 26`。降 `compileSdk` 会让整排 androidx 在 `checkDebugAarMetadata` 报错。
- 加依赖前先确认镜像上真有该版本，并把「实测来源」写进 `docs/environment.md` §4。**不要凭记忆写版本号。**
- `assembleRelease` / `lintDebug` 在 `--offline` 下必失败（`lint-gradle` 未缓存）。已用 `lint { checkReleaseBuilds = false }` 规避，别为此新增依赖。
- 不要动 `~/android-dev`（尤其 `avd/debug.keystore`）：签名身份变了会导致已装 App 必须卸载重装、本地数据库丢失。

## 3. 架构规则

- 包分层：`core` / `domain` / `data` / `playback` / `asr` / `work` / `ui`。
- **`domain` 包禁止 import 任何 `android.*` / Media3 / Room 类型**。这是「统计与字幕算法能在 JVM 单测里跑」的前提，破坏它等于删掉测试能力。
- `ui` 层禁止：构造 `ExoPlayer`、直接查 DAO、跑推理。UI 只吃 `PlaybackController` 接口与 ViewModel 的 `StateFlow`。
- 全 App 只有一个 `ExoPlayer` 实例（`PlayerHolder`）、同一时刻只有一个 ASR 引擎句柄。
- 阈值/常量只允许一处定义：`core/conf/PlaybackPolicy.kt`、`core/conf/AudioFormats.kt`、`asr/ModelCatalog.kt`。UI 上的规则说明文案必须由这些常量渲染，不许另写死字符串。
- 手工 DI（`AppGraph`）。不引 Hilt/Koin/KAPT；不引入未经实测的大型框架（FFmpeg、ExoPlayer 之外的解码库、图片加载库等）——先证明系统能力不足，再在文档里记录理由后引入。

## 4. 数据与统计

- 统计口径的唯一定义在 `docs/playback-design.md` §3。改口径 = 同时改：`PlaybackPolicy`、`SessionTracker` 实现、单测、统计页文案、本文档。**五处不一致视为 bug。**
- `listened_ms`（墙上播放时间，倍速不放大）与 `consumed_audio_ms`（音频位置正向推进，完播判定用）不得混用。
- 时长计算一律用单调时钟（`TimeSource`），墙钟只用于「什么时候」。禁止用墙钟差算时长。
- 进度写库节流 ≥1s、默认 10s；**禁止每 100ms 写库**。
- 删除书架记录不得删除 `listening_session`（历史统计要活过书籍删除）。
- 正式版禁止 `fallbackToDestructiveMigration()`。任何表结构变更：写显式 `Migration` + 提交 `app/schemas/*.json` + 加 `MigrationTestHelper` 测试。改统计字段还要提供回填 SQL。
- 字幕替换（重转录）必须在**单个事务**内完成删旧 + 插新。

## 5. ASR 的诚实性要求（本项目最容易自欺的地方）

- **禁止**用平均分配时间、按字数均分、复制粘贴文本等方式伪造字幕时间戳。句级时间必须来自模型 `timestamps` 或明确记录误差的对齐结果。
- **禁止**虚构模型下载地址、版本、大小、校验值。`ModelCatalog` 里每个值都要有来源 URL，无官方校验值的文件明确标注「无官方校验值」并本机钉住首次下载的哈希。
- 没有真实模型时：可以做 `AsrEngine` 接口、`FakeAsrEngine`、任务状态机、模型管理，但必须在 `docs/roadmap.md` 与 UI 上如实标注「ASR 端到端未完成」。测试字幕只能用于验证字幕管线，且必须在代码注释与文档里标明它不是 ASR 产物。
- 模型与 AAR 是二进制：放 `app/libs/`（已在 `.gitignore`），不提交；构建脚本遇到缺失要打印**确切 URL 与期望大小**，不要静默失败或自动偷偷下载几百 MB。
- 推理不得在主线程；`release()` 必须成对；单段音频 ≤20s（官方约束）。

## 6. 测试要求

- 每个阶段交付前：`assembleDebug` + `testDebugUnitTest` 必须真跑过，并把输出摘要（成功/失败、耗时、任务列表）追加到 `docs/testing.md` §4。
- 新增统计/字幕/切句逻辑必须带 JVM 单测（假时钟、无 Android 依赖）；Room/SAF/播放相关逻辑必须带仪器化测试。
- 测试不许用 `Thread.sleep` 等待真实时间；用注入的假时钟。
- 修 bug 先写能复现它的测试。

## 7. 文档同步

- 改了行为就要改对应文档：需求 → `requirements.md`（FR 编号）、架构 → `architecture.md`、表结构 → `database-design.md`、口径 → `playback-design.md`、模型 → `asr-design.md`、阶段状态 → `roadmap.md`、验证记录 → `testing.md`。
- 文档里不允许出现代码中不存在的能力描述。状态词只用 `DONE` / `PARTIAL` / `TODO`。
- 重要决策（为什么选 X 不选 Y）写进 `architecture.md` §10 的决策表，一条一行，附取舍。

## 8. Git 与协作

- **只在用户明确要求时 commit / push**；不 `--no-verify`，不 `git stash` 用户的工作（本仓库与用户可能在同一棵树里并行改动，暂存前先 `git status`）。
- 不提交：`local.properties`、`app/libs/*.aar`、模型文件、任何密钥。
- 提交信息用中文、说清「为什么」。

## 9. 语言

- UI 文案与文档用中文（简体）；代码、标识符、日志 tag 用英文。
- 用户是 6 年证券风控系统开发者、Android 与本地推理是本项目现学的：解释技术选择时可以直接用工程术语，但不要假设其熟悉 Android 专有 API 名称——涉及 Media3/SAF/KSP 等专有名词时给一句它干什么。
