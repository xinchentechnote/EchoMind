# 开发阶段与任务状态（roadmap）

状态口径只有三种，**不允许「大概做完了」**：
`DONE`（已构建并实测通过，见 `testing.md` 的对应记录） / `PARTIAL`（部分完成，缺口写明） / `TODO`。

## 阶段总览

| Phase | 内容 | 状态 | 实测凭据 |
| --- | --- | --- | --- |
| 0 | 环境与现状核查 | **DONE** | `docs/environment.md`（工具链、网络、依赖版本全部实测） |
| 1 | 需求与设计文档 | **DONE** | `requirements.md` / `architecture.md` / `database-design.md` / `playback-design.md` / `asr-design.md` / 本文件 |
| 2 | 可运行工程骨架 | **DONE** | `assembleDebug` 成功（APK 16.1 MB）；`kspDebugKotlin` 生成 Room Impl；安装到 `emulator-5554` 并启动，截图确认导航与主题渲染 |
| 3 | 文件夹导入 + 书架 | **PARTIAL** | `assembleDebug` + 31 项 JVM 单测 + 20 项仪器化测试全绿；`emulator-5554` 真实 SAF 导入 6 首、连续重扫不重复建记录、移走→MISSING、原样移回→PRESENT、手改标题不被覆盖、force-stop 后书架不丢，DB 查询与截图为证（`testing.md` §4）。缺口见下「Phase 3 遗留」 |
| 4 | Media3 播放 + 播放记忆与统计 | **PARTIAL** | `assembleDebug` + 59 项 JVM 单测 + 41 项仪器化测试全绿（2026-10-15）；`emulator-5554` 实测：前台中媒体通知、通知栏控制、媒体键、瞬时焦点丢失暂停且不自动续播、自动切歌逐首分账、崩溃恢复标 `CRASHED`、重算==增量、统计页与 DB 对账一致。缺口见下「Phase 4 遗留」 |
| 5 | 字幕基础设施与七句窗口 | **PARTIAL** | `assembleDebug` + 83 项 JVM 单测 + 48 项仪器化测试全绿（2026-10-15）；`emulator-5554` 实测：v2 建表、七句窗口 3+1+3、末句不补位、当前句高亮、点句跳 `start_ms`（DB 复核 12000/15000/24000）、拖动与倍速后跟手、切歌后换成另一套字幕、测试字幕常驻标注「非 ASR 产物」。缺口见下「Phase 5 遗留」 |
| 6 | 本地 ASR（sherpa-onnx） | **PARTIAL**（**端到端仍未完成**） | 已实装并在 `emulator-5554` 上真跑过：模型清单/下载/校验（35 JVM + 4 仪器化）、PCM 解码（4 仪器化，148 s 真素材）、VAD 分段（6 仪器化，RTF 0.017~0.020）、SherpaOnnx 推理（8 仪器化，装载 ~4.9 s，识别 RTF 0.083~0.143 —— 模拟器两轮差 1.6 倍）、逐字时间换算（14 JVM）。JVM 169 / 仪器化 70 全绿。**缺口**：`work/` 包还是空的，没有 `TranscriptCoordinator`/任务表/WorkManager，**模型识别出的字句一次都没进过数据库**；当前播放页能看到的字幕全部来自 debug 通道导入的测试字幕文件。模型是 `adb push` 搬进设备的，App 自己下载 243 MB 那条路还没验过。见下「Phase 6 待办」 |
| 7 | UI 完整化与端到端验收 | **TODO** | — |
| 8 | 交付文档与总结 | **TODO** | — |

## Phase 2 已完成的具体内容

- Gradle 工程：wrapper 9.6.0、AGP 9.4.1（built-in Kotlin）、KSP 2.3.12、Compose BOM 2026.09.00、Material 3。
- `libs.versions.toml` 统一版本；阿里云镜像优先的仓库配置。
- Room v1 基线：`audio_book` 表 + `AudioBookDao` + `EchoMindDatabase`（`exportSchema=true`，schema 输出到 `app/schemas`）。
- 单 Activity + Compose 导航：4 个底部目的地 + 播放页路由，未实现的页面**如实显示「该页面将在 Phase N 实现」**而不是假数据。
- 单元测试基础设施已配置（JUnit4 + coroutines-test + arch-core-testing），尚无测试用例 → 随 Phase 3/4 的 domain 逻辑一起补。

## Phase 3 已完成的具体内容

- `imported_folder` 表（`tree_uri` 唯一索引 + `permission_state`）与 `playback_state` 表（书架进度需要，统计写入在 Phase 4）随 `audio_book` 一起进 v1 schema。
- 授权链路：自定义 `OpenPersistableDocumentTree` 契约（请求意图里就带上 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`）+ `SafTreeGrantStore.persistRead()` 只传模式位。设备侧 `dumpsys activity permissions` 实测 `persistable=0x3 persisted=0x1`。
- `SafTreeBrowser`（`DocumentFile` 递归）+ 纯 Kotlin 的 `AudioTreeWalker`（深度 ≤12、文件 ≤20000，超限标 `truncated`）+ `SystemAudioMetadataReader`（Media3 `MetadataRetriever` 取时长，失败记 `duration_read_failed`）。
- 差量判定 `AudioImportPlanner`：`NEW / CHANGED / UNCHANGED / VANISHED / REVIVED`，去重键是 URI；`@Upsert` 批量写入（生成的 Impl 走 `performSuspending`，同事务）。
- `TreeGrantStore` 接缝：ContentResolver 的授权无法在用例里授予/撤销，抽出来才能测 FR-110 的两条路径。
- 书架 UI：三 tab（全部/最近播放/收藏）、即时搜索、`继续上次收听`卡片、★/☆ 收藏、重命名对话框、从书架移除、导入/重扫顶栏动作、MISSING 灰显且不可点、按 tab 区分的空态文案。
- 测试：31 项 JVM 单测（walker/planner/title/format/ViewModel）+ 20 项仪器化测试（DAO 11 + 导入编排 9）。

## Phase 3 遗留（不得当作已完成）

1. **重命名是对话框，不是行内编辑**（用户偏好行内编辑）→ Phase 7 换。
2. **扫描不可取消、也没有逐项进度**（FR-111 只做到「跑在 `Dispatchers.IO` + 顶栏转圈忙态」）→ Phase 4 与转录任务一起做。
3. **文件夹管理页缺失**：FR-102/FR-904 要求的「已授权文件夹列表 + 单独重新授权 + 单独重扫」还没有 UI，目前只有全局重扫与文件夹级 `Revoked` 状态。→ Phase 7。
4. **真实撤销授权未实测**：`pm revoke` / 系统设置里撤销 SAF 授权这条路没走过，只验证了「授权缺失时导入抛 `PermissionRevokedException`、重扫跳过该文件夹不拖垮整批」（仪器化测试）。→ 记在 `testing.md` §6。
5. `last_scanned_at_ms` 只对新增/变更行刷新，UNCHANGED 行不刷新（该列暂无 UI 依赖）→ 需要时补一条按文件夹的批量刷新。
6. 书架搜索用 `LIKE '%'||:q||'%'` 且未转义 `%`/`_`，用户输入下划线会当通配符 → Phase 7 一并处理。
7. `importFolder` 的多步写入（插新/改变更/标状态/markScanned）**不是同一个事务**：只有 `upsertAll` 自带事务。中途失败会留下半套状态 → Phase 4 收进 `withTransaction`（`database-design.md` §9）。

## Phase 4 已完成的具体内容

- 口径实现：`PlaybackPolicy` 成为唯一定义处（含新增的 `MAX_COUNTED_POSITION_STEP_MS`）；`SessionTracker` 是纯 Kotlin 状态机，22 项假时钟 JVM 单测覆盖 4.9s/5.0s 边界、倍速只放大消耗量、墙钟跳变不影响时长、缓冲/暂停不计时、拖到末尾不完播、两次完播计 2、切歌先结算上一场。
- 播放层：`PlaybackController` 接口 + `QueueItem` + `PlaybackUiState` + `resumePositionFor`（`domain/playback/`，无 android import）；`PlayerHolder` 全 App 唯一 ExoPlayer；`Media3PlaybackController` 实现 `Player.Listener`，把播放器事件翻成 tracker 事件，落库走一条 `UNLIMITED` 通道串行。
- 数据层：`listening_session` 进 v1 schema（16 列 + 3 索引）；`PlaybackRepository` 的 `openSession/flushProgress/settleSession/saveSpeed/recoverCrashedSessions/recomputeAggregates/stats/recentSessions`，写路径全部在事务里；增量与全量重算两条路等价（仪器化对账）。21 项仪器化测试。
- 服务：`EchoMindMediaService : MediaSessionService` + `addSession` + `DefaultMediaNotificationProvider`（自建低重要性渠道）+ `onTaskRemoved` 保持后台播放；`MediaItem` 带 `MediaMetadata`（否则通知只显示「EchoMind is running」）。
- UI：播放页替换 placeholder（播放/暂停、±15s、拖动、5 档倍速、上下曲、收藏、停止、本首累计/次数/完播、字幕区占位）；统计页（总时长/次数/完播/最近收听 + 规则说明由常量渲染）；页面跟随队列自动换书（`PlayerViewModelTest` 6 项）。
- 设备实测记录见 `testing.md` §4「Phase 4」，其中 6 个 bug 是**只看代码看不出来**的（A~F），已全部修复并复测。

## Phase 4 遗留（不得当作已完成）

1. **耳机拔出 `BECOMING_NOISY` 没实测**：受保护广播 + `adb emu` 无耳机命令，模拟器造不出这个场景。代码路径存在（`handleAudioBecomingNoisy=true` + `REASON_AUDIO_BECOMING_NOISY` 结算），但按 §5 的口径只能算 PARTIAL。→ 真机（`testing.md` §6）。
2. ~~**FR-309 的「冷启动位置与退出前差 ≤10s」没做设备实测**~~ → **已补（2026-10-15，Phase 5 期间）**：重新导入文件夹后真 `am force-stop` + 冷启动，差 3394ms ≤ 10s，数据在 `testing.md` §4 Phase 5。同一次实测抓到并修掉一个真 bug：**自动切歌不换倍速**（`onMediaItemTransition` 没调 `player.setPlaybackSpeed`，播的是上一首的速率、记的是自己那本的账）。
3. **设置页「重算统计」入口（FR-410/905）没有 UI**：`recomputeAggregates()` 已实现并被测试对账，用户点不到。→ Phase 7。
4. **统计落库失败会静默丢一笔**：控制器对每个写操作 `runCatching` + `Log.w(tag=EchoMindPlayback)`，不重试。取舍见 `architecture.md` D17。
5. **播放器不提供 `release()`**：进程活着 ExoPlayer 就活着。换来的是回前台不会有 IDLE 空壳（实测踩过），代价是内存不回收、且 `onDestroy` 只 `removeSession`。
6. **Phase 3 遗留 7（`importFolder` 整体不是一个事务）没做**：原计划 Phase 4 收进 `withTransaction`，本轮没动。中途失败仍会留半套状态。
7. **Phase 3 遗留 2（扫描取消/逐项进度）没做**。
8. `session_status` 只有 5 个值，`FINISHED` 同时表示「播到末尾」和「用户中途停止」（`completed` 列才区分）。口径已写进 `playback-design.md` §3.3 与 `database-design.md` §10，但命名确实有歧义，后续要拆需走 Migration。

## Phase 5 已完成的具体内容

- **表与迁移**：`subtitle_segment` 进 `version = 2`（9 列 + 2 索引 + 指向 `audio_book` 的 CASCADE 外键）；`app/schemas/` 下 1.json/2.json 都已提交。首条**手写** Migration `Migrations.MIGRATION_1_2`（建表 + 唯一索引 + 时间索引），`EchoMindApp` 只注册 `Migrations.ALL`，没有 destructive 兜底。`Migration_1_2_Test` 3 项在设备上跑通：结构对得上导出的 `2.json`、已有书籍与收听历史原样保留、`PRAGMA foreign_key_list` 确认外键存在。
- **算法**：`domain/subtitle/SubtitleSynchronizer.kt`（纯 Kotlin、无状态）——`startMs` 数组二分 + `GAP_ENTER_MARGIN_MS` 迟滞 + 首句之前/末句之后不补位；11 项 JVM 单测，含 5000 句的查找正确性与 gap 序列稳定性（FR-701/703/704）。
- **数据层**：`SubtitleRepository`（`observeSegments` / `observeSummary` / `replace` / `clear`），整本替换在 `db.withTransaction` 里删旧 + 插新（FR-605）；`source` 列区分 `ASR` 与 `MANUAL_TEST`。4 项仪器化测试：索引连续、重复导入是替换不是追加、摘要能分辨数量与来源、**删书连带删字幕但收听历史留着**（NFR-04 两半都测）。
- **UI**：播放页字幕卡片接七句窗口（当前句加粗 + 底色，FR-702）、点一句 `seekTo(start_ms)`（FR-706）、`distinctUntilChanged` 节流（FR-707）、无字幕时如实说明而不是放假数据；位置只有控制器一份，换曲靠 `flatMapLatest` 重挂字幕流。4 项 ViewModel 测试覆盖窗口跟随位置、切歌换套、导入写正在播的那本、同一句内部推进不重发窗口（FR-707）。
- **测试字幕导入通道**（FR-604 的管线验证）：`domain/subtitle/TestSubtitleParser.kt` 支持 SRT / WebVTT / `ms<TAB>ms<TAB>文本`，时间戳**只从文件读**，解析不出来就连行号一起报错、绝不补零或按字数均分；9 项 JVM 单测。入口按钮只在 `FLAG_DEBUGGABLE` 下渲染，落库标 `MANUAL_TEST`，页头常驻「9 句 · 测试字幕，非 ASR 产物」。
- **设备实测**（`testing.md` §4 Phase 5）：真实 SAF 选文件 → 落库 9 行 → 窗口渲染 → 点句跳转后 `playback_state.last_position_ms` 精确等于该句 `start_ms`。

## Phase 5 遗留（不得当作已完成）

1. **字幕内容全部来自手工导入的测试文件**：没有模型、没有 ASR 产物，`transcription_state` 一条都没变过。FR-6xx 里「字幕从哪来」这件事仍然 0 分，属 Phase 6。
2. **FR-602 中文标点切句未实现**：切句的对象是模型输出的整段文本，`SentenceSplitter` 连文件都没有。测试字幕自带时间，绕过了这一步，所以**不能拿它当切句已验证**。
3. **FR-603 只做了一半**：导入解析器会拒绝 `end ≤ start` 并按时间重排重编号，但模型输出的负值/重叠/零长度清洗未实现。
4. **真实用户升级路径没走过**：v1→v2 迁移是在仪器化测试里造 v1 文件再迁移验证的；本机从未安装过 v1 版本的 App，所以「旧包覆盖安装」这条路径没有实测数据（`MigrationTestHelper` 已覆盖 SQL 层面）。
5. **书架那一格仍显示「未转录」**：它描述的是转录任务状态，有测试字幕的书也不会变（D21）。Phase 6 任务表落地后这一格才有真意义。
6. **`job_id` 可空且暂无外键**：Phase 6 建 `transcript_job` 时要走一次 v2→v3 整表重建补上 FK（`database-design.md` §5.1）。
7. 长句换行时的高亮可辨识性（FR-708）只做了目视，字号调节（FR-902）在 Phase 7。

## 远端 CI（2026-10-15 建好）

私有库 `https://github.com/xinchentechnote/EchoMind`（`main`），工作流 `.github/workflows/ci.yml` 在 push/PR/workflow_dispatch 上跑
`:app:assembleDebug` + `:app:testDebugUnitTest`，并把调试 APK 作为 artifact 上传。**第三次跑才绿**（run 38632644725，83 项单测，`BUILD SUCCESSFUL in 5m36s`）；
前两次的真实死因与排查过程记在 `docs/environment.md` §8 与 `docs/testing.md` §4——其中「阿里云镜像在 runner 上 502」这条只有远端测得出来。

CI **不覆盖**：仪器化测试（没有模拟器）、真机验证、ASR 端到端。这三项仍然按老规矩在本地/设备上做，绿 CI 不等于它们过了。

## Phase 6 待办（下一步）

1. ~~sherpa-onnx AAR 取回与 ABI 收敛~~ → **已完成（2026-10-15）**：静态链接版 AAR（38,691,998 B，官方 `digest` 已核对）放进 `app/libs/`（不入库），
   `app/build.gradle.kts` 缺文件时抛异常打印 URL/大小/SHA-256，CI 按同一份哈希取回；`abiFilters` 收窄为只有 `arm64-v8a`，
   实测 `assembleDebug` 通过、APK 40,794,372 B、包内只有一份 `libsherpa-onnx-jni.so`。**注意：只做到「链接进包」，一次推理都没跑过。**
2. ~~`ModelManager`：下载/导入、SHA-256 校验、删除；`ModelCatalog` 每个值都要有来源 URL，无官方校验值的要标注并本机钉首次哈希~~ →
   **PARTIAL（2026-10-15）**：**下载 + 校验 + 删除已完成并在设备上打到真实 URL**——`asr/` 四个文件（`ModelCatalog` / `ModelVerifier` / `ModelFetcher`+`HttpFetcher` / `ModelManager`）
   挂进 `AppGraph`（`modelsRoot = filesDir/models`），35 条 JVM 单测 + 4 条仪器化用例（`ModelManagerNetworkTest` 用清单里真实的 hf-mirror URL 取回 `tokens.txt` 并独立复算 sha256）；
   清单三个文件的字节数与 SHA-256 全部实测过、`tokens.txt` 标了「无官方校验值 + 本机钉住」；顺带补上被漏掉的 `INTERNET` 权限（漏它时 35 条单测一条都不红）。
   **仍缺三块**：① 本地导入（SAF 选目录）未实现；② 243 MB 的 int8 与 GitHub 单源的 VAD 都没从设备取回过（`github.com:443` 本机与模拟器同时超时，实测记录见 `docs/testing.md` §4）；③ 没有任何 UI 消费 `ModelManager.statuses`，所以「点一下就能下模型」还没交付 —— 归本清单第 6 项。
3. ~~PCM → VAD → 引擎~~ → **DONE（2026-10-15，三层都在设备上真跑过；端到端「入库」归第 4 项）**：
   `domain/asr/PcmConversion`（16-bit LE → 单声道 float，均值下混）、
   `domain/asr/SincResampler`（源采样率 → 16k 抗混叠 FIR；**必须自己写**——实测 Media3 1.11.1 里没有 `AudioResamplingAudioProcessor`，
   `media3-decoder` 里也没有 `MediaDecoder`，所以本条原计划的「Media3 `MediaDecoder`/`ExoPlayer` 抽 PCM」这条路**不成立**，改用框架 `MediaExtractor` + `MediaCodec`）、
   `domain/asr/AsrChunkPlanner` + `domain/model/AsrModels`（>20s 均分成 ≤20s 块、绝对毫秒偏移，FR-505 零漂移单测）、
   `domain/asr/TokenTimingMapper`（逐字「秒」→ 绝对毫秒；数量不符/越界/倒序抛 `Mismatch`，不猜）——共 51 条 JVM 单测；
   **`asr/PcmDecoder`（✅）**：同步模式贪心解码，真解 148 s 立体声 MP3，样本数与容器时长对账偏差 **0.22%**、581 块首尾相接、`peak=0.659`、
   单缓冲上界 4,608 B（= 文件 0.002 倍，NFR-05 第一次有数），4 条仪器化；
   **`asr/VadSegmenter`（✅）**：流式 512 样本窗 + 段带样本回来，6 条仪器化。60 s 真素材出 9 段、最长 7.4 s（`maxSpeechDuration=20s` 从未被迫强切）、
   纯 VAD **RTF 0.017~0.020**（1,021 / 1,211 ms 两轮，同一素材）；不同喂入块长（4096/1024/3000/整串）分段结果逐项相同；
   **`asr/SherpaOnnxEngine`（✅）**：装载前按清单核对，单段 >20s 或样本数与块不符直接抛，主线程构造直接拒，`release()` 幂等，8 条仪器化。
   真模型第一次开口：单块 19.2 s → 13 个逐字时间、「那片笑声让我想起我的那些年」、**RTF 0.143**；端到端 6 块 → **58 个 token**、
   跨块时间单调、**RTF 0.131**、引擎装载 4,906 ms（后台线程）。**第二轮复跑（同一天 07:19）证明结果完全可复现**（58 个 token、跨度 14,134~49,446 ms、
   9 段的 startList/lenList 逐项相同），但**耗时抖动大**：端到端 RTF 0.083、单块 0.134、装载 4,914 ms → RTF 在模拟器上只当量级看。
   APK ~40.9 MB（本机同一天三次构建 40,859,995~40,951,147 B，尺寸不当判据，见 `docs/testing.md` §4），JVM 169 / 仪器化 70 全绿。
   ⚠ **本轮挖出的新事实**：Phase 3~5 一直在用的 `/sdcard/Music/EchoMind/` 那批「《三体》」素材**是静音占位 MP3**（帧体 99% 为 0x00，宿主机逐字节核对）。
   长度类结论不受影响，但**音频内容此前从未被验证过**，转录精度必须换真人语音素材（本机无 MP3 编码器，得用户提供）。
   ~~⚠ 两个必须实测、不许猜的单位问题仍是空白~~ → **已用真数据答掉（不是猜的）**：`SpeechSegment.start` 是 **16k 样本下标**（全部落在 1 ms=16 样本的网格上；
   原先假设的「必是 512 窗整数倍」被真数据**否掉**了），`OfflineRecognizerResult.timestamps` 是**秒**且相对本块起点，`durations` 长度 **0**（所以末字只能收到块末尾）。
   三条判据都做成了仪器化断言，另有一条反向对照用例（把毫秒当秒喂 → 必须抛）。
   ⚠ **另一条实测到的坏消息**：块边界不等于句子边界——同一段歌词整块 20 s 识别出「那片笑声让我想起我的那些年」，被 VAD 切成两块后给出「偏笑声让」+「想起我的那些话」。
   逐字时间仍有效，但**块文本不能直接当字幕**，这是第 5 项 `SentenceSplitter` 的活。
   **还缺**：把这三层接进生产编排（现在只有测试在串它）、`transcript_job` 表与 WorkManager、入库事务、模型的设备端 HTTP 取回（现在这两个模型是 `adb push` + `run-as` 搬进 `filesDir/models/` 的，
   与「App 能自己下载」是两件事）。
4. `transcript_job` 表（v2→v3，顺带给 `subtitle_segment.job_id` 补 FK）+ `TranscriptCoordinator` + WorkManager 任务与重启恢复。
5. FR-602/603 的切句与时间戳清洗（这次才是真需要 `SentenceSplitter` 的地方）。
6. 转录页（现在是 placeholder）+ 失败降级文案。

## Phase 7 待办要点

- **7**：设置页全量（字号/深浅色/重算统计/按文件夹重新授权）、错误与降级文案、离线（飞行模式）验收、性能与内存实测、迷你播放条、行内重命名、文档回填。

## 后续（MVP 之后，按价值排序）

1. 标点恢复模型（提升字幕可读性；sherpa-onnx 无现成 Kotlin API，需自行接 `punc_ct-transformer`）。
2. 每日收听时长与连续天数（需要按天分桶的会话聚合，注意时区口径）。
3. 字幕导出（SRT/TXT）与导入（跳过重复转录）。
4. 定时关闭 / 睡眠计时、跳过静音、章节（多文件合并成一本书）。
5. 其他音频格式（m4a/flac）——先实测 `MediaExtractor` 兼容性再放开白名单。
6. 备份与迁移（导出 DB + 字幕，导入到新设备）。
7. release 签名与 R8 评估（当前 `isMinifyEnabled=false`，理由见 `app/build.gradle.kts` 注释）。

## 已知技术债 / 风险跟踪

| 项 | 说明 | 计划处理 |
| --- | --- | --- |
| 占位页面 | 书架/播放/统计已实装；**转录页与设置页仍是 placeholder** | Phase 6 / 7 逐个替换，替换前不宣称完成 |
| 文件夹管理 UI | 只有全局重扫；FR-102/904 要求的按文件夹授权/重扫没有入口 | Phase 7 |
| `connectedDebugAndroidTest` 会清掉被测 App 的数据 | 实测：跑完仪器化测试后 `dumpsys package` 显示 `notLaunched=true`、databases 目录消失、持久化授权一并失效，需要重装 APK 并重新走一遍 SAF 导入 | 设备验证放在仪器化测试**之后**做，或接受重导入成本 |
| v1 schema 曾在开发期就地扩表 | Phase 3/4 往 `version = 1` 里直接加过表（未发版、数据可丢）。**Phase 5 起已改为显式 Migration**：`Migrations.MIGRATION_1_2` + `Migration_1_2_Test`，`EchoMindApp` 只注册 `Migrations.ALL`，没有 destructive 兜底 | Phase 6 建 `transcript_job` 时写 v2→v3，并顺带给 `subtitle_segment.job_id` 补外键（要整表重建） |
| x86_64 ABI | ~~目前 `abiFilters` 含 arm64-v8a + x86_64~~ → **已收窄为只打 arm64-v8a**（2026-10-15，接 AAR 的同时做的；本机 AVD `ro.product.cpu.abi` 实测 `arm64-v8a`，x86_64 那份 sherpa so 未压缩 27.4 MB 纯属浪费） | 已处理；x86 真机/模拟器如需用，再单独放开并实测体积 |
| 手工 DI | `AppGraph` 会随阶段变胖 | 若超过 ~15 个装配项或出现循环依赖再评估拆模块 |
