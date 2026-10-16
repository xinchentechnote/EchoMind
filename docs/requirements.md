# 需求说明（requirements）

项目名 **EchoMind**（工程与应用标识 `com.xinchen.echomind`；早期方案文档里的暂定名 OfflineAudioReader 指同一应用）。

定位：完全本地运行的 Android 听书 App。导入本地 MP3 书解 → 书架 → 播放并记住进度 → 本地 ASR 生成带时间戳字幕 → 播放时七句字幕跟随高亮。不依赖服务器、账号、云 ASR。

---

## 0. MVP 边界

**MVP 必做**（对应验收标准 1–12）：
1. SAF 文件夹导入 + 递归扫描 + 去重 + 元数据提取。
2. 书架（列表、进度、最近播放、收藏、搜索）。
3. Media3 播放：播放/暂停、±15s、拖动、倍速、上一首/下一首、后台播放、音频焦点、断电恢复位置。
4. 播放记忆与统计（会话表 + 累计口径 + 完播口径），统计页。
5. 字幕数据模型 + 索引 + 七句跟随窗口 + 点击跳转（用导入的测试字幕驱动，**不代表 ASR 完成**）。
6. 本地数据库全部落地，含 Migration 基础设施。
7. 转录任务的基础设施：模型管理、任务队列/进度/取消/重试/重启恢复，以及引擎的封装层。
   （原文还写了「`AsrEngine` 接口 + 测试替身引擎」——**2026-10-15 主动不做**：只有一个实现、拿不出第二个替身，套接口是为不存在的需要付费，代价是推理层无法在 JVM 里测、必须上设备。取舍记在 `docs/architecture.md` §10 D30，这条改动的连带影响见 FR-502。）
8. 核心功能离线可用；模型缺失时可降级为「只播放、无字幕」。

**MVP 明确不做**（避免自我膨胀，留 roadmap）：
- 在线书籍信息、云同步、社区、推荐、广告、账号。
- 每日统计 / 连续收听天数 / 图表。
- 字幕文件导出导入、多说话人、英文/方言模型。
- FFmpeg 解码（先用系统 `MediaExtractor`+`MediaCodec`，只有确认格式兼容性不足才引入）。
- 自动跳过静音/定时关闭/睡眠计时（roadmap 里作为下一批）。
- x86_64 之外的模拟器适配（首版优先 arm64-v8a；模拟器已在 API 36 arm64 上验证）。

---

## 1. 本地文件夹导入

| ID | 需求 | 验收标准 | 状态 |
| --- | --- | --- | --- |
| FR-101 | 用 `ACTION_OPEN_DOCUMENT_TREE` 选文件夹，`takePersistableUriPermission` 持久化授权 | 杀进程重开仍不需重新授权；书架不丢 | **DONE**（`dumpsys activity permissions` 实测 `persistable=0x3 persisted=0x1`；force-stop 后重启书架 6 首仍在。`testing.md` §4） |
| FR-102 | 支持多个文件夹；已授权文件夹列表在设置页可见 | 列表显示 URI 的显示名、上次扫描时间 | **PARTIAL**（多文件夹导入/去重/跨文件夹归属已实现并有仪器化测试；`displayName`+`lastScanAtMs` 已入库；设置页的文件夹列表 UI 未做 → Phase 7） |
| FR-103 | 递归扫描子目录，识别音频文件（大小写不敏感）；扩展名白名单集中配置（`core/conf/AudioFormats`，**只列实测过的格式**） | 白名单内的格式都能入库；白名单外的不入库 | **DONE（2026-10-16 扩展）**：白名单 = `mp3 / m4a / aac / flac / wav`。m4a/flac/wav 各造一份真语音素材（macOS `afconvert` 转自同一份 MP3）在 `emulator-5554` 上端到端实测：重扫入库（时长 148.6~148.7 s 全部读出）→ 播放 → 首播自动转录 → 任务 SUCCESS、38 句 `source=ASR` 落库、书 READY（三种格式同一天全绿）。**刻意不放行**：`ogg`/`opus`（探针证实解码器存在，缺本机测试素材）、`wma`（`DeviceCodecProbeTest` 在设备上枚举 39 个解码器证实 AOSP 无 WMA 解码器——支持它必须引入 FFmpeg 量级框架，见 `docs/roadmap.md`「音频格式支持」）。walker 8 项 + 新格式白名单用例（大写扩展照收、wma/ogg 照丢）在 `AudioTreeWalkerTest` |
| FR-104 | 提取文件名、URI、size、lastModified，并用 Media3 `MetadataRetriever`/`MediaExtractor` 读取时长 | 时长与系统播放器读数误差 < 1s | **PARTIAL**（四个字段均入库，时长读取失败标 `duration_read_failed` 不阻塞导入；「与系统播放器对比误差」未实测 → Phase 7） |
| FR-105 | 标题默认从文件名生成（去扩展名、去前导集数 `03_`、下划线转空格），用户可改标题且不被重扫描覆盖 | 改名后重新扫描仍保留用户标题 | **DONE**（设备：改成 `ManualRename` 后重扫仍保留，`title_is_manual=1`；`TitleDeriverTest` 7 项） |
| FR-106 | 首次导入、手动重新扫描、新增文件识别；**同一 URI 不重复建记录** | 连续扫描 3 次，记录数不变 | **DONE**（设备连续重扫 `count(*)` 恒为 6；planner + 仓库测试各覆盖） |
| FR-107 | 文件变更检测：同一 URI 的 size 或 lastModified 变化 → 标记需要重新转录，进度保留 | 替换同名文件后，字幕状态转为「已过期」 | **PARTIAL**（变更识别 + `Ready→Expired` + 手动标题不被覆盖有仪器化测试；「进度保留」要等 Phase 4 有进度可保留后才能验） |
| FR-108 | 文件消失（删除/移走）→ 标记 `MISSING`，不删除历史统计；UI 上灰显且不可播放 | 移走文件后重扫，统计页历史仍在 | **PARTIAL**（MISSING 灰显 + 行不可点 + 不删行，设备与测试均确认；「统计页历史仍在」依赖 Phase 4 会话表。反向的「文件回来 → PRESENT」也已实测） |
| FR-109 | 不复制音频到 App 私有目录（默认直接读 SAF URI） | `filesDir` 不出现音频副本 | **DONE**（`run-as ls files` 只有 `profileInstalled`，代码里也没有任何复制路径） |
| FR-110 | 授权撤销（`SecurityException`）→ 播放/扫描给出「重新授权」入口，历史保留 | 撤销授权后进入 App 显示可操作的提示而不是崩溃 | **PARTIAL**（扫描侧：`PermissionRevokedException` → 提示「文件夹授权已失效，请重新授权」，重扫只跳过失效文件夹；系统侧真实撤销未实测，播放侧入口待 Phase 4） |
| FR-111 | 扫描在后台线程/协程调度器执行，UI 有进度与可取消 | 大目录扫描不卡主线程（`Dispatchers.IO`） | **PARTIAL**（`withContext(Dispatchers.IO)` + 顶栏转圈忙态；**没有取消**，也没有逐项进度 → Phase 4 与转录任务一起做） |

## 2. 本地书架

| ID | 需求 | 验收标准 | 状态 |
| --- | --- | --- | --- |
| FR-201 | Material 3 列表：标题、总时长、进度百分比、最近播放时间、转录状态、收藏标记 | 目视核对 + 数据来自 Room 而非缓存常量 | **DONE**（字段全部来自 `ShelfDao` 的 `LEFT JOIN` Flow；截图核对。进度轨道只在真播过之后出现） |
| FR-202 | 入口：全部音频 / 最近播放 / 收藏 / 转录任务 / 统计 / 设置 | 导航可达，返回栈正确 | **PARTIAL**（4 个底部目的地 + 播放页路由可达；**转录页已实装**（任务列表 + 带 `audioId` 的确认卡片），返回栈在 emulator 上按 navA→navE 逐步实测正确——顺带修掉一个真 bug：`popUpTo(saveState)+restoreState` 会把带参数的转录页连同卡片一起复活，导致点底部标签切不走，见 `architecture.md` D38；统计页 Phase 4 已实装并与 DB 对账；**只剩设置页是 placeholder**） |
| FR-203 | 标题搜索（即时过滤），按最近播放时间排序 | 输入关键词列表即时变化 | **DONE**（设备输入 `DEEP` 即时过滤；「最近播放」tab 按 `last_played_at_ms DESC`，中文关键词由 DAO 仪器化测试覆盖） |
| FR-204 | 「继续上次收听」：一键恢复上次音频与位置 | 冷启动后顶部按钮能续播 | **PARTIAL**（卡片显示上次音频与「已播到 x/y」，点击进入播放页；真正续播在 Phase 4） |
| FR-205 | 无在线书籍查询/推荐 | — | **DONE**（无网络权限，代码里没有任何 HTTP 客户端） |

## 3. 音频播放

| ID | 需求 | 验收标准 | 状态 |
| --- | --- | --- | --- |
| FR-301 | Media3 ExoPlayer，统一由 `PlaybackController` 暴露状态与命令；Compose 不持有播放器实例 | 代码审查：UI 层无 `ExoPlayer()` 构造 | DONE |
| FR-302 | 播放/暂停、±15s（阈值集中配置）、拖动进度条 | 拖动后位置正确、字幕同步 | PARTIAL：播放/暂停、±15s、拖动均在 `emulator-5554` 实测；「字幕同步」待 Phase 5 |
| FR-303 | 倍速 0.75/1/1.25/1.5/2（每首独立保存） | 重开该首仍为上次倍速 | DONE：档位来自 `PlaybackPolicy.SPEED_STEPS`，每首独立持久化由仪器化测试「倍速单独保存不影响进度与计数」覆盖；设备端实测「重开仍是上次倍速」与「自动切歌后换成新那本的倍速」（`dumpsys media_session` 的 `speed` 对齐 `playback_state.playback_speed`）。后者原本是个 bug，2026-10-15 修复，见 `testing.md` §4 Phase 5 |
| FR-304 | 后台播放：`MediaSessionService` + 通知控制；进程在前台切后台仍继续播 | 退到后台音频不断，通知可暂停 | DONE：`dumpsys` 显示前台通知 `foregroundId=1001 types=0x2`，通知上的暂停把位置冻住、播放能续；无 `AudioHardening` 静音（前提见 `playback-design.md` §1 的 `addSession`） |
| FR-305 | 音频焦点：瞬时丢失暂停、恢复不自动播放（`AUDIOFOCUS_LOSS_TRANSIENT`） | 来电/其他 App 播放时暂停 | DONE：`adb emu gsm call` → `dumpsys audio` 显示 `loss: LOSS_TRANSIENT`，会话结算、位置冻结；挂断后 13s 内没有自动续播，用户点播放才继续 |
| FR-306 | 拔出耳机（`ACTION_AUDIO_BECOMING_NOISY`）暂停 | ~~模拟器用 `adb` 广播验证~~ **模拟器测不了**：这是受保护广播，`adb shell am broadcast` 打不进来，`adb emu help` 也没有耳机开关命令 → 只能真机 | PARTIAL：代码路径（`handleAudioBecomingNoisy=true` + `REASON_AUDIO_BECOMING_NOISY` 结算）已实现，**未实测**（`testing.md` §6） |
| FR-307 | 上一首/下一首按书架顺序，切歌时结束当前会话并开启新会话 | 会话表出现两条记录 | DONE：手动上下曲与自动切播都实测，会话表逐首分行、各自的 `play_count` 不串 |
| FR-308 | 播放页显示当前音频信息（标题、进度、倍速、收藏） | — | DONE：页面跟随队列换标题/收藏/统计（`PlayerViewModelTest` + 设备实测） |
| FR-309 | 启动后恢复上次位置（含播放到接近末尾时的「已听完」处理：从头播还是停在末尾，见 FR-407） | 冷启动后位置与退出前差 ≤ 10s | DONE：位置读写与「听完再进来从头播」由 `PlayerViewModelTest` 覆盖、DB 侧由仪器化测试覆盖；**设备冷启动实测（2026-10-15）**：`am force-stop` 时播放器在 61853ms、最后 flush 落库 58459ms，冷启动后从 58459ms 续播，差 **3394ms ≤ 10s**（上界即 `POSITION_FLUSH_INTERVAL_MS`）。数据与做法见 `testing.md` §4 Phase 5 |

## 4. 播放记忆与统计（核心）

存储字段（`playback_state`，与音频 1:1）：`audio_id`、`last_position_ms`、`duration_ms`、`play_count`、`completed_count`、`listened_ms_total`、`last_played_at_ms`、`last_completed_at_ms`、`playback_speed`、`is_finished`、`created_at_ms`、`updated_at_ms`。
会话表（`listening_session`）：`id`、`audio_id`、`audio_title_snapshot`、`duration_ms_snapshot`、`started_at_ms`、`ended_at_ms`、`listened_ms`、`consumed_audio_ms`、`start_position_ms`、`end_position_ms`、`max_reached_position_ms`、`completed`、`session_status`、`is_counted`、`created_at_ms`、`updated_at_ms`。
（列名与类型的权威定义在 `database-design.md` §4，本节只说明需求覆盖哪些字段；`is_favorite` 属于 `audio_book`，不在播放记忆里。）

统计口径（**必须在文档与测试里同口径**，见 `playback-design.md`）：

| ID | 规则 | 可测验收 | 状态 |
| --- | --- | --- | --- |
| FR-401 | 一次**有效收听会话** = 该会话实际处于播放状态的累计墙上时间 ≥ `MIN_VALID_SESSION_LISTENED_MS`（默认 5000ms）。误触（<5s）不计次 | 单测：4.9s → 不计；5.0s → 计 1 次 | DONE |
| FR-402 | 累计收听时长只统计播放器真正处于播放状态的时间；暂停、缓冲（`STATE_BUFFERING`）、焦点丢失、停止期间不计时 | 单测：混合状态序列的时长对账 | DONE |
| FR-403 | 计时用**单调时钟**（`SystemClock.elapsedRealtime` 的可注入替身），不用墙钟差；不用系统时间戳相减 | 单测：墙钟跳变不影响结果 | DONE |
| FR-404 | 倍速播放时 `listened_ms` = 实际墙上播放时间（不乘倍速）；音频内位置单独记录 | 单测：2x 播 10min 墙上 → listened=600000，consumed=1200000 | DONE |
| FR-405 | 进程被杀 / 异常退出 / 重启：最多丢失一个 flush 周期（默认 10s）的进度；恢复时把 `ACTIVE` 会话按最后一次落盘数据结算为 **`CRASHED`**（原文写的是 `INTERRUPTED`，与实现不符：`INTERRUPTED` 表示「有明确的中断原因」，崩溃恢复只能信任最后一次 flush，两者的处置与信息量不同，见 `playback-design.md` §3.4），**不重复累加** | 集成测试：模拟崩溃后重启，聚合值不变大 | DONE（`force-stop` 后重启实测残留行标 `CRASHED`；仪器化测试覆盖幂等与短会话不计次） |
| FR-406 | 完播 = 播放头自行到达末尾（`end_position ≥ duration - END_TOLERANCE`）**且** 本会话 `consumed_audio_ms ≥ duration × COMPLETED_CONSUMED_RATIO`（默认 0.9） | 单测：拖到末尾 → 不完播；1.5x 听完 → 完播 | DONE |
| FR-407 | 完成率阈值默认 95%，`END_TOLERANCE`、`CONSUMED_RATIO` 均在 `PlaybackPolicy` 集中配置 | 改常量后单测结论随之变化 | DONE（另有 `MAX_COUNTED_POSITION_STEP_MS` 拦住「一次跳跃把完播骗过去」，`playback-design.md` §3.3 规则 7） |
| FR-408 | 重复听完整首可再次 `completed_count++` | 单测：两次完播会话 → 2 | DONE |
| FR-409 | 切换音频时先结算旧会话（`ended_at`、状态、累计）再开新会话 | 集成测试 | DONE |
| FR-410 | 聚合字段（`play_count`/`completed_count`/`listened_ms_total`）以 `ListeningSession` 为准可重算（崩溃恢复与设置页「重算统计」） | 集成测试：重算结果 == 增量维护结果 | PARTIAL：两条路等价由仪器化测试覆盖；设置页的「重算统计」入口 UI 在 Phase 7 |
| FR-411 | 统计页：总收听时长、总播放次数、完播次数、最近播放列表、单首累计收听时长与次数 | 目视 + 数据一致性 | DONE（设备目视 + `sqlite3` 对账一致） |
| FR-412 | 进度持久化频率 ≤ 每 10s 一次（或状态跃迁时立即 flush），**不得每 100ms 写库** | 代码审查 + 单测计数 | DONE（`POSITION_FLUSH_INTERVAL_MS` + `writes` 通道串行；500ms tick 只推 UI 不写库） |

## 5. 本地 ASR 语音转文字

| ID | 需求 | 验收标准 | 状态 |
| --- | --- | --- | --- |
| FR-501 | 只用本地模型，不调在线 ASR API | 网络权限层面：转录路径无任何 HTTP 客户端 | **DONE（2026-10-15，模型内置后权限层面可证）**：主清单**连 `INTERNET` 权限都没有了**（原「下载模型」路径随 FR-511 内置化退出产品，`HttpFetcher` 仅存于测试），App 进程在系统层面发不出任何网络请求，比「代码里没有调用」更强；「飞行模式 + 删光模型 → 冷启动就位 → 播放/恢复」已在 `emulator-5554` 实测（`docs/testing.md` §4 Phase 6f）。原「模型准备需要网络」的边界作废——模型随安装包内置，准备也不需要网络 |
| FR-502 | 通过 `AsrEngine` 接口封装加载与推理；sherpa-onnx 实现与测试替身实现并列 | 单测跑替身引擎，无需模型 | **偏离（主动不做，2026-10-15）**：没有 `AsrEngine` 接口，也没有测试替身——推理是 `asr/SherpaOnnxEngine` 一个类，`open()` 前按 `ModelCatalog` 核对、`recognize()` 只吃 `PcmChunkPlan` + 16k float 样本、`release()` 幂等。理由与代价记在 `docs/architecture.md` §10 D30：只有一个实现、拿不出第二个替身，套接口等于为「以后可能换引擎」预付费用。**代价是这条需求原本的验收方式（JVM 里跑假引擎）不成立**，推理只能在设备上用真模型测（`SherpaOnnxEngineTest` 9 条仪器化全绿，含「主线程装载直接抛」）。需求另一半「封装加载与推理」本身是做到了的 |
| FR-503 | 中文书解优先验证 Paraformer 中文离线模型；时间戳能力按 `asr-design.md` 的确证结论落地（若模型不给可靠句级时间戳，用其确证的替代路线并记录误差） | 设计文档有真实来源 URL，不虚构 | **已实测（2026-10-15）**：装载的正是清单里那个 `paraformer-zh-2023-09-14-int8`（243,371,218 B，sha256 与大小在**设备上**由 `ModelVerifier` 复核通过）。真模型在设备上给了真文本：19.2 s 块 → 「那片笑声让我想起我的那些年」+ 13 个逐字时间戳。**时间戳能力的确证结论**（原来是设计稿里按官方文档的推测）：模型给的是 **token（逐字）级**时间、单位**秒**、相对本块起点；`durations` 数组长度为 **0**。所以句级时间只能由逐字时间派生（首字 `startMs`、末字 `endMs`），单句末字 `endMs` 收到块末尾——已写进 `docs/asr-design.md` §5 并做成设备断言 |
| FR-504 | 转录流水线：MP3 解码 → 16kHz 单声道 PCM → 分段（不整本载入内存，峰值受控）→ 逐段识别 → 结果合并 → 切句 | 单测：分段边界与偏移；集成测试：真实 MP3 | **PARTIAL（2026-10-15，六环全接进生产，且「从界面点一下」这条路已跑通）**：`asr/PcmDecoder`（框架 `MediaExtractor`+`MediaCodec` 同步贪心）→ `domain/asr/PcmConversion`（均值下混）→ `domain/asr/SincResampler`（抗混叠 FIR）→ `asr/VadSegmenter`（silero VAD 流式，段带样本回来）→ `domain/asr/AsrChunkPlanner`（≤20s 分块、绝对毫秒偏移）→ `asr/SherpaOnnxEngine.recognize` + `domain/asr/TokenTimingMapper`（逐字绝对毫秒）→ `domain/asr/SentenceSplitter`（切句）→ `domain/asr/TranscriptQuality`（质量闸门）→ `TranscriptRepository.commitSubtitles`，串它的是 `asr/TranscriptCoordinator`（**不是测试**，已挂进 `AppGraph`），入口是转录页（书架 ⋮ →「转录这本书」→「开始转录」）。**「不整本载入内存」有数**（这是**结构论证**，不是实测结论——转录期间的内存采样证明不了它，整本 float 也只有 9.5 MB，见 `docs/testing.md` §7）：VAD 流式、单个 codec 缓冲 ≤4,608 B、同时驻留的只有当前那一段（≤20 s ≈ 1.28 MB）。集成测试用真 MP3：148 s 整首歌在 emulator-5554 上跑完 → 2,378,188 样本 / 24 段 / 24 块 / **丢弃 0** / **35 句**（硬切 0），全部落库（`TranscriptCoordinatorTest`，原始日志见 `docs/asr-design.md` §10）；**同一条链从界面走也在用户库（不是 in-memory）里留下了 35 行 `source=ASR` 字幕**（`docs/testing.md` §4 Phase 6d）。**为什么不是 DONE**：①结果合并只做完了「跨块拼字 + 跨段并句」，**同一段被切成两块时文本会变**（整块 20 s 给「那片笑声让我想起我的那些年」，切两块给「偏笑声让」+「想起我的那些话」）——切句器能收拾句子但收不回错字，这一条如实留在 `asr-design.md` §5 第 5 步；②`work/TranscriptWorker.doWork` 主体、`isStopped` → 取消位、意外异常补 `FAILED(UNKNOWN)`、`cancelWorkById` 撤销**仍无自动化用例**（端到端用例直接调协调器；这一轮新增的 2 条只测合并后清单里的前台服务类型），它们有的是人工点出来的设备证据 |
| FR-505 | 分段的时间偏移必须累加，**每段字幕时间不得从 0 重新开始** | 单测：第 N 段首句 start_ms = 段起点 | **PARTIAL（2026-10-15，真转录已入库，但验收动作的写法要如实说明）**：换算只有两处——`AsrChunkPlanner.msOf`（绝对样本下标 → 毫秒）与 `TokenTimingMapper`（模型的秒 → 块内毫秒 + 块起点）。14 条单测含「180 段连续 20s 音频末块 `endMs == 3_600_000`」的零漂移断言与「每块起点 = 上一块终点、互不重叠」。设备侧现在有三层证据：6 块 58 token 跨度 **14,134~49,446 ms 严格单调**（`VAD段逐块识别出来的时间是全局单调的`）；**整本书真的跑完并入库**（`TranscriptCoordinatorTest.真模型跑完整首歌后字幕第一次进了数据库`，那个库是用例进程里的 in-memory Room：35 句的 `segment_index` 连续从 0 起、时间升序、每句 `end_ms > start_ms` 且**不越出音频容器时长 148,303 ms**）；以及**用户机上那份 `echomind.db`**——从界面点出来的 35 行 `source=ASR` 字幕，`start_ms` 最小 14,134、`end_ms` 最大 145,766，同样落在容器时长 148,304 ms 之内（`docs/testing.md` §4 Phase 6d）。最后那条越界断言就是「不许从 0 重新开始、也不许把秒当毫秒」同时成立的唯一抓法——单位错 1000 倍必然越界。**为什么还不写 DONE**：验收标准原文写的是「第 N 段首句 `start_ms` = 段起点」这个**逐段对账**动作，端到端用例只断了全局升序 + 不越界，没有把每一句的 `start_ms` 与它所属 VAD 段的起点逐个比过（协调器里 `RecognizedSegment.startMs` 就是段起点，句首取的是段内第一个 token 的模型原值，两者并不要求相等——所以那条断言的字面写法本身需要先改成能被满足的形式，这属于定验收口径，不是补代码） |
| FR-506 | 结果入库缓存，同一音频不重复识别（除非文件变更或用户强制重转）；**2026-10-16 起新增：首次播放无字幕的书自动发起转录并实时显示**（见 FR-701 的实时预览注记与 `docs/roadmap.md` 6g） | 二次进转录直接 READY；首次进播放页自动开始、句子边转边出现 | **PARTIAL（2026-10-16 更新）**：入库与「不重复识别」的逻辑都在——`TranscriptRepository.commitSubtitles()` 单事务删旧+插新（`source=ASR`、`job_id` 指向这一行）+ 任务 SUCCESS + `transcription_state=READY`；`enqueue()` 有两道守卫（同一本书已有 QUEUED/RUNNING 任务 → `AlreadyActive` 返回同一行；READY 且库里真有 ASR 字幕 → `AlreadyTranscribed`，`force` 放行），各有仪器化用例。**首次播放自动转录已在设备上闭环实测**（2026-10-16，`docs/testing.md` §4 Phase 6g）：SAF 文件夹里新增一份真语音 → 重扫出「三无」新书 → 进播放页自动入队并开跑 → 字幕区实时出现「正在转录：已识别 N 段 · 已出 M 句」与流出的歌词 → 24 段全部识别、35 句 `source=ASR` 落库、书变 READY、状态行消失、正式字幕接管。**为什么不是 DONE**：①「文件变更 ⇒ EXPIRED」这条**用户路径没有设备证据**（要改音频文件的 size/mtime，这一轮没做）；②`AlreadyTranscribed` 的文案只在状态陈旧时才够得着，没有用例之外的设备证据 |
| FR-507 | 任务支持排队/取消/重试/进度显示；前台服务 + 通知；应用重启后未完成任务可恢复（不自动重跑已完成部分则记录为设计取舍） | 集成测试：取消后状态 CANCELLED，重试回到 QUEUED | **PARTIAL（2026-10-15，功能全在界面上跑通了，缺的是自动化测试那一半）**：`QUEUED/RUNNING/SUCCESS/FAILED/CANCELLED` 状态机 + `cancel` + `retry`（新建一行，旧行留着当错误上下文）+ `updateProgress` + `recoverInterrupted()` 全有仪器化用例；**「排队」不再只是一行状态**——`TranscriptWorkScheduler` 用 `enqueueUniqueWork(UNIQUE_NAME, APPEND)`（唯一名 `transcript-series` **全局一个**，不是每本书一个）把它交给 WorkManager，`work_request_id` 写回活动任务（写不回就 `cancelWorkById` 撤销，用例 `按id取行并把工作请求id写回活动任务` / `已结束的任务写不回工作请求id`）；前台服务与通知在 `TranscriptWorker` 里（`setForeground` + `FGS_TYPE=dataSync` + 「已识别 N 段」文案，**分母未知时故意不给百分比**）；`EchoMindApp.onCreate` **已经调** `recoverInterrupted()`。**中断后不自动重跑**已定为取舍并由守卫实现：重启复活的 worker 撞在 `markRunning` 的 `WHERE status='QUEUED'` 上 → `Skipped`（用例 `重启后复活的worker不再覆盖中断结论`）。**设备侧这一轮补齐了**（`docs/testing.md` §4 Phase 6d，全部人工点出来）：同屏「转录中 + 排队中」两张卡片（后排没有进度条）、前台通知可见、真在跑的中途点取消 → `CANCELLED`（`5963bd56`）、重试 → 新建一行且旧行连错误码留在列表、进程被杀后重启那行翻成 `FAILED(INTERRUPTED)` 且 worker 日志打出「跳过：任务 … 的状态是 Failed，不该再跑推理」。**进度显示不许说谎**有 13 条 JVM 用例钉住（`TranscriptDisplayTest`：排队中 → 无进度；分母未知 → 「已识别 N 段（总段数要等解码跑完才知道）」且不含 `%`；成功 → 「共 N 段全部识别」）。**为什么不是 DONE**：`doWork` 主体、`isStopped` → 取消位、意外异常补 `FAILED(UNKNOWN)`、`cancelWorkById` 撤销路径**仍无自动化用例**（新增的 `TranscriptForegroundServiceTypeTest` 2 条只断言合并后清单里的前台服务类型与权限）；`APPEND` 排队语义同样无用例（`schedule()` 现在有生产调用方了，是转录页 ViewModel）。
⚠ **这条缺陷已修（2026-10-15，D36）**：唯一名原本是**每本书一个**（`transcript-$audioId`），同时给两本书入队会跑起**两个 worker**，
每个各自 `SherpaOnnxEngine.open()` → 同一进程两份 243 MB 模型并存，违反 AGENTS.md §3，2 GB RAM 上是 OOM 而不是慢。
现在的两处闸门：①`markRunning` 的 SQL 里 `AND NOT EXISTS (… status='RUNNING' AND id <> :id)`（全机只允许一行 RUNNING，
抢不到的那本停在 `QUEUED`、一行字幕都不写，用例 `别的任务正在跑时第二本书的任务抢不到运行中的行` /
`别的书正在跑时第二个协调器收手且不写字幕`，两条都对「去掉闸门」的构建证过伪）；②调度器改成全局单一唯一名 + `APPEND`，
由 WorkManager 自己排队（**这一半没有用例**，但界面上看得见：两本书先后入队时同屏「转录中 + 排队中」）。新闸门的代价如实写：
一行卡在 `RUNNING` 会把后续所有转录一起挡住，释放车道靠协调器 `settle` + worker 的 `FAILED(UNKNOWN)` 兜底 + 应用启动的 `recoverInterrupted()` |
| FR-508 | 模型不可用/文件损坏/内存不足 → 明确错误文案与可执行动作（重新准备模型/重试），App 不崩溃且仍可播放 | 设备验证 | **PARTIAL（2026-10-15，UI 文案已落地并在设备上出现过 4 条分支）**：`error_code` 枚举 + `error_message` 存异常原文，`TranscriptJobRow.failure` 透出给 UI，`ui/transcript/TranscriptDisplay.failureGuide` 把 8 个 `TranscriptFailure` 逐个翻成「发生了什么 + 你能做什么」，卡片还把 `error_message` 原文照登成「原始信息：…」（**不加工、不美化**）。**写入方**（协调器）四条分支在 emulator 上被真跑出来（`TranscriptCoordinatorTest`）：`MODEL_MISSING`（模型目录空 → 且断言一行字幕都没写）、`DECODE_FAILED`（文件不在原位置 → 且断言它**在装载引擎之前就返回**，不为注定失败的活儿付 5 s 装载）、`PERMISSION_REVOKED`、`OUTPUT_UNRELIABLE`（静音素材 → 不给 SUCCESS、不给空字幕）。**UI 侧这一轮在设备上见到的**：`INTERRUPTED`（进程被杀后重启）、`OUTPUT_UNRELIABLE`（静音素材，文案里「丢弃比例超过 **20%**」那个数由 `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT` 渲染，不是写死字符串，并明说「宁可失败也不按字数均分时间」）、`CANCELLED`（中途取消）；失败之后 App 没崩、书架与播放页照常可用。JVM 用例 `每种失败都给得出一个动作` 逐分支断言文案里有可执行动作。**为什么不是 DONE**：①`OUT_OF_MEMORY` 分支只有代码、没用例也没设备证据（要真把堆撑爆才走得到）；②~~`MODEL_MISSING` 文案里的「请先在模型页准备好」**指向还不存在的页面**~~ → **模型页已存在（FR-901，2026-10-15 设置页 v1）**，文案不再指空（「删模型 → 出文案 → 按指引恢复」的完整闭环还没连起来走）；③「转录失败的同时点播放」这个动作没专门做过（只验了失败后播放页可用）|
| FR-509 | 转录与播放互不阻塞（不同线程/不同资源；模型加载不在主线程） | 设备验证：播放中启动转录不卡顿 | **PARTIAL（2026-10-15）**：「模型加载不在主线程」已写成代码里的硬约束——`SherpaOnnxEngine.open()` 与 `VadSegmenter` 构造里各有一条 `check(Looper.myLooper() != Looper.getMainLooper())`（装载实测阻塞 3.0~4.9 s，放主线程就是 ANR），两条仪器化用例用 `runOnMainSync` 断言它当场抛 `IllegalStateException`；`TranscriptWorker` 用 `withContext(Dispatchers.IO)` 跑整轮，`EchoMindApp` 只有一个引擎句柄、用完即 `release()`（`try/finally`），不抢播放器资源（不同进程组件、不同解码器实例）。**「播放中启动转录不卡顿」仍未实测**：转录现在能从界面发起了（任务 #23 已落地），但验证转录时播放器没在放，也没采 `dumpsys gfxinfo`——能说的只有观察级证据（转录那 ~40 s 里底部标签还能切、卡片还能点取消、没有 ANR 弹窗）。且识别 RTF 只有模拟器数字，**两轮同一素材差 1.6 倍**（端到端 0.083/0.131，整首歌含装载三次独立跑 wall 42,965 / 33,453 / 63,714 ms，对 148 s 音频 ≈ RTF 0.29 / 0.23 / 0.43）。**内存峰值已在 AVD 上量到**（这台 AVD 总内存 2.42 GB，正好是目标机型量级）：转录期间峰值 PSS 442 MB、RSS 559 MB，曲线是「~260 MB 装载台阶 + 一条平线」（见 `docs/testing.md` §6b/§7）。**换真手机之后的 RTF 与是否被 lowmemorykiller 杀掉仍未测**，「播放 + 转录同时跑」的掉帧采样也仍是空白 |
| FR-510 | **（2026-10-15 用户决策改为内置，原「下载或本地导入」口径作废）**模型随安装包内置；删除/损坏后可从安装包离线恢复；模型信息（名称/版本/文件清单/大小/SHA-256/来源）集中在清单里 | 清单里没有虚构值；来源标注清楚 | **DONE（按新口径，2026-10-15）**：清单在 `asr/ModelCatalog.kt`（数值由 `ModelCatalogTest` 钉住，`tokens.txt` 标 `officialSha256=null`）；三个文件随 APK `assets/models/<specId>/` 发布（构建脚本查存在+字节数，CI 按钉住哈希取回并 `sha256sum -c`）；`ModelManager.ensureProvisioned` 启动时把缺文件从 assets 拷进 filesDir（同样的 `.part` → 比大小 → 整份 SHA-256 → 改名规矩），`provision` 供设置页按钮恢复；来源记录 `apk_asset=<asset路径>` 落进 `download-provenance.properties`。设备实测（飞行模式）：删光模型 → 冷启动自动就位两包「已就绪」；再删 `model.int8.onnx` → 设置页「不可用」→ 点「从安装包恢复（232 MB）」→ 20 s 内回「已就绪」（`docs/testing.md` §4 Phase 6f）。原 `download`/`importFile` 代码与 JVM 测试保留为非产品路径，UI 无入口，文档不宣称可下载 |
| FR-511 | ~~不把模型塞进 APK~~ → **2026-10-15 用户决策反转：模型直接内置，不依赖网络下载**（取舍见 `architecture.md` D39） | 模型文件在 `assets/` 里；APK 体积代价如实记录 | **DONE（2026-10-15）**：三个文件（243,371,218 + 75,756 + 643,854 B）随 `assets/models/` 发布（`.gitignore` 不入库，构建脚本缺失即失败，CI 按哈希取回）；调试 APK 40.9 MB → **267.5 MB**，安装后 `filesDir/models/` 再占一份 ~244 MB（内置方案的固有代价：assets 无法按路径打开，sherpa-onnx 只认文件路径，首次就位必须拷贝）。换来的是：无网络依赖（主清单无 `INTERNET`）、无首启下载等待、删除后可离线恢复 |

## 6. 字幕切句与时间戳

`SubtitleSegment`（domain 类型）：`index`、`start_ms`、`end_ms`、`text`。落库为 `subtitle_segment`（v2 起），另有 `source`（`ASR`/`MANUAL_TEST`）与可空的外键 `job_id`（指向 `transcript_job`，`ON DELETE SET NULL`，v3 起，理由见 `database-design.md` §5.1）。

| ID | 需求 | 验收标准 |
| --- | --- | --- |
| FR-601 | 按 `start_ms` 时间序保存，`segment_index` 单调 | 查询按 `(audio_id, segment_index)` —— **PARTIAL（2026-10-15，编排层已负责排序）**：`(audio_id, segment_index)` 是 UNIQUE 索引，`segment_index` 由写入方按传入顺序重编（连续、从 0 起），仪器化 `导入后按索引顺序读回且索引连续` 覆盖。**落库仍然不排序**（`replace()` / `commitSubtitles()` 信任调用方给的是时间升序——它是通用批量写，把排序藏进 DAO 等于让编排层的责任消失），而这一步现在**由 `TranscriptCoordinator` 承担**：提交前 `sortedBy { startMs }` + `mapIndexed` 编号（`docs/architecture.md` D35），端到端用例断言 35 句的 `segment_index == 0..n-1` **且** `start_ms` 升序，所以真转录这条路已经满足「索引序 = 时间序」。**为什么不写 DONE**：`SentenceSplitter` 的输出本来就是时间升序（JVM 有性质断言），所以这次排序**没有被任何用例证到「必要」**——要构造一条「协调器不排就会乱」的用例，得先让切句器交出乱序，而那在纯逻辑层已被排除。这条属于防御性代码，验收动作（真转录后查库对账）已做，机制本身未被抓现行 |
| FR-602 | 中文标点切句：`。！？；!?;` 优先，`，、` 作为长句兜底切分；单句最大长度受限（默认 60 字，超出按标点或空格回退切分） | 单测含无标点长文本 —— **PARTIAL（2026-10-15，已有生产调用方）**：`domain/asr/SentenceSplitter` 落地，四档回退（强标点断句 → 段内停顿断句 → 跨段合并 → 超长句「弱标点 → 空白 → 按 60 字硬切」），25 条 JVM 单测全绿，含「90 字无标点连续中文」这条指定用例。**调用方也补上了**：`TranscriptCoordinator` 在入库前调它，148 s 整首歌 → **35 句、硬切 0 句**全部落库。**为什么仍不是 DONE**：①停顿判据的两个阈值（500ms / 中位数×2）和合并窗口 700ms **都未标定**，只在一份歌唱素材上验过（`docs/asr-design.md` §6 第 2、3 条记录了为什么不是设计稿原值）；②朗读型真人语音还没有 fixture，而本书的目标内容是书解——**「切得对」这件事目前只对歌唱成立**；③超长句硬切这一档在设备上**从未被走到**（35 句最长没超 60 字），所以「按标点或空格回退」只有 JVM 用例撑着 |
| FR-603 | 处理异常时间戳：负值、end<start、与上一段重叠、零长度 | 单测：清洗后单调不减、时长 > 0 —— **DONE（2026-10-15）**：负值与 `end<start` 在 `TokenTiming` 构造期就被 `require` 挡住（`TokenTimingMapper` 那层也已抛过一轮 `Mismatch`）；**与上一段重叠**和**零长度**是 `SentenceSplitter` 自己管的——前者把上一字的 `endMs` 夹到下一字起点（`上一个字压到下一个字头上时以起点为准夹住`），后者三种收场：并进上一句 / 用下一句起点 / 谁都给不出就丢掉（`隔着一大段静音的零长字不并给上一句`、`整篇只有一个零长字就如实丢掉`），**不补一个看着顺眼的时长**。清洗后单调不减、时长 > 0 是不依赖具体用例的性质断言（`输出永远时间升序每句时长为正且互不重叠`）。导入解析器 `TestSubtitleParser` 那套拒绝逻辑仍在，两条路径互不替代 |
| FR-604 | 句子起止时间必须来自模型时间信息或实际对齐结果，**禁止按字数均分时间伪造精准字幕** | 代码审查；若为对齐结果，`asr-design.md` 记录误差 —— **DONE（2026-10-15，两层都有结构约束）**：①库里分得开来源——`subtitle_segment.job_id` 是指向 `transcript_job` 的外键，唯一写它的入口是 `TranscriptRepositoryImpl.commitSubtitles()`（文本与时间原样落库、不重算），手工导入走 `SubtitleRepositoryImpl.replace()`（`job_id` 恒 null、`source=MANUAL_TEST`）；②切句造不出时间——`SentenceSplitter` 的句首就是该句第一个 token 的 `startMs`（模型原值），句末只可能是**下一个句首**或**本 VAD 段语音末尾**，全类没有 `总时长 / 字数` 这种表达式，参数名 `均分` / 常量 `100ms` 一类都不存在。反而加了一条**写不出假绿**的用例：`起点差全都并列时按长度硬切并标注边界是猜的`（硬切必须 `boundaryGuess=true`）与设备侧 `平均每句 ≥3 字`（见 §6 第 2 条，那条断言就是为了堵住「全是单字字幕但每条时长都合法」这种自欺）。⚠ `boundaryGuess` **不落库**（2026-10-15 定稿，`docs/architecture.md` D35：加这一列要走 v3→v4 迁移，而 MVP 没有任何 UI 读它），句子的置信度在库里看不出来，只在收口日志与 `TranscriptOutcome.guessedBoundaries` 里给数量（`asr-design.md` §5 第 6 步已记）。另外这条需求现在多了一处**结构性**保证：不合格的字根本到不了字幕表——`Mismatch` 的块被整块丢弃，丢弃过多则整本 `FAILED(OUTPUT_UNRELIABLE)`，**没有任何一条路径用「均分」造过时间**（D34） |
| FR-605 | 重新转录时安全替换旧字幕：单事务删旧插新 | 集成测试：替换后无残留、无半套字幕 —— **DONE（2026-10-15）**：两条替换路径都是 `db.withTransaction` 里 `deleteByAudio` + 批量 `insertAll`。用例三条：`再次导入是整本替换而不是追加`（无残留）、`重转录整本替换旧字幕`（新一轮只留新句，旧行任务仍留历史）、`重复结算第二次整体失败并回滚字幕`（第二次提交在跃迁处失败 → 已插的字幕**整体回滚**，库里还是上一批）。「半套字幕」是靠回滚验的，不是靠断言顺序 |
| FR-606 | 字幕按时间范围查询，支持长音频（数千句）不进内存全表扫描 | DAO 查询 + 索引 |

## 7. 七句字幕窗口

| ID | 需求 | 验收标准 |
| --- | --- | --- |
| FR-701 | 显示当前句前 ≤3 句 + 当前句 + 后 ≤3 句（最多 7 句）；首尾不足正常处理 | 单测：index 0 / 中间 / 末句 |
| FR-702 | 当前句高亮且尽量位于窗口中间（不足时贴边） | 目视 |
| FR-703 | 定位用**二分查找**（`SubtitleSynchronizer`），不每次遍历全部字幕 | 单测 + 代码审查 |
| FR-704 | 句间停顿期保持当前句稳定，不来回跳 | 单测：落在 gap 内的位置序列 |
| FR-705 | 拖动进度、倍速播放、切歌后窗口同步 | 设备验证 |
| FR-706 | 点击某句 → 跳转到该句 `start_ms` | 设备验证 |
| FR-707 | 只在当前句索引变化时更新列表（`distinctUntilChanged`），避免 100ms 全量重建 | 单测 + 帧重组察 |
| FR-708 | 长句换行仍可辨识当前句（字号可调，见 FR-902） | 目视 —— **部分**：高亮行换行可辨识已实测；字号调节属 Phase 7（FR-902） |

## 8. 设置

| ID | 需求 | 验收标准 | 状态 |
| --- | --- | --- | --- |
| FR-901 | 模型状态与版本、准备/更新/删除模型（**2026-10-15 起准备=从安装包就位/恢复，无网络动作**） | 设置页可见真实状态；删除后播放仍正常 | PARTIAL（2026-10-15，设置页 v1 = 模型节，随 6f 改为恢复语义）：`ui/settings/`（`SettingsViewModel` 消费 `ModelManager.statuses`，文案层 `ModelDisplay` 15 JVM + VM 8 JVM，**页面不自算大小或哈希**）；`emulator-5554` 实测：两包真实状态、逐文件核对结论、校验值来源标注（官方 / 本机钉住两句话）、「从安装包恢复（232 MB）」按钮 + 恢复进度（`正在从安装包恢复 model.int8.onnx：…（10%）` 那套诚实进度规矩与转录页同源）、删除确认文案（说清删什么、不动什么、可离线恢复）；「删光 → 飞行模式冷启动自动就位」与「删单文件 → 页面按钮恢复」两条都在设备上真跑过（见 FR-510）。**仍缺设备实测**：删除按钮本体（确认对话框没在设备上按下过——它删的是刚恢复回来的 243 MB）；恢复中途的取消没有 UI（恢复很快，暂不做）。设置页其余条目（字号/深浅色/统计重算/按文件夹授权）未实装，页脚如实标注「将在后续阶段加入」 |
| FR-902 | 默认播放倍速、字幕字号 | 修改后新音频生效 | TODO（Phase 7；`settings` 表也未建。当前每首独立倍速已实现并可持久化，但没有「默认值」来源） |
| FR-903 | 播放次数统计规则说明（文案与实际 `PlaybackPolicy` 一致，由常量渲染而非另写死字符串） | 代码审查：文案来自同一常量源 | **DONE**：统计页三行说明全部由 `PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS` / `END_TOLERANCE_MS` / `COMPLETED_CONSUMED_RATIO` 插值渲染，页面上没有第二处写死的阈值字符串 |
| FR-904 | 文件夹授权状态列表 + 重新授权入口 + 重扫 | 设备验证 | PARTIAL（Phase 3 遗留：只有全局重扫与文件夹级 `REVOKED` 状态，没有列表和按文件夹的重新授权入口 → Phase 7） |
| FR-905 | 「重算统计」入口（FR-410） | 集成测试 | PARTIAL：`recomputeAggregates()` 已实现且与增量路径对账通过（仪器化测试），**用户点不到**——设置页还是 placeholder → Phase 7 |

---

## 9. 数据可靠性与非功能要求

| ID | 要求 |
| --- | --- |
| NFR-01 | 所有音频索引、播放记录、字幕、模型均在本地（Room/SQLite + 应用私有目录 + SAF URI 引用）。 |
| NFR-02 | 首次模型准备完成后，核心功能在无网络下全部可用（飞行模式验收）。 |
| NFR-03 | 数据库升级必须有显式 Migration；**正式版禁止 destructive migration**。 |
| NFR-04 | 删除音频文件不删除听书统计历史（FR-108）；用户在书架里主动删除某条记录时，才级联删除其字幕与任务，**统计会话历史默认保留**（详见 `database-design.md` 的取舍）。 |
| NFR-05 | 不将完整长音频一次性解码进内存；ASR 推理不在主线程；推理结束及时释放。 |
| NFR-06 | 大目录扫描、转录、聚合重算全在 IO 调度器。 |
| NFR-07 | 低内存（`largeHeap` 不启用）下分块大小受控，PCM 块峰值目标 ≤ 16MB/块。 |

## 10. 验收标准（MVP 出口）

1. `./gradlew :app:assembleDebug` 成功，App 能在 API 36 设备/模拟器安装并启动。
2. 能选择文件夹并批量导入多首 MP3。
3. 重新扫描不产生重复记录（DB 计数验证）。
4. 每首可独立播放，后台不断。
5. 切歌/重启 App 后恢复每首位置（误差 ≤ 一个 flush 周期）。
6. 播放次数与累计收听时长符合 FR-401~410 的口径（单测 + 仪器化测试对账）。
7. 书架、播放记忆、历史正确落库（可查 DB）。
8. 字幕窗口最多 7 句并跟随位置更新（测试字幕或真实字幕）。
9. 有真实可用的本地 ASR 接入路径：给定模型即可在设备上完成实际转录（若受环境阻塞，须给出可复现步骤与阻塞记录，不得谎报完成）。
10. 关网络后，播放与已有字幕阅读全部正常。
11. 统计、字幕同步、数据库逻辑有自动化测试覆盖。
12. 文档、代码、测试结果一致（文档里不承诺未实现的东西）。
