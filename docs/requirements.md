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
7. 转录任务的基础设施：`AsrEngine` 接口、模型管理、任务队列/进度/取消/重试/重启恢复，以及一个可跑的测试替身引擎。
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
| FR-103 | 递归扫描子目录，识别 `.mp3`（大小写不敏感）；扩展名白名单集中配置 | 三层子目录里的 mp3 都能入库；非 mp3 不入库 | **DONE**（设备：3 层目录 + `5.MP3` 入库、`notes.txt` 不入库；`AudioTreeWalkerTest` 8 项） |
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
| FR-202 | 入口：全部音频 / 最近播放 / 收藏 / 转录任务 / 统计 / 设置 | 导航可达，返回栈正确 | **PARTIAL**（4 个底部目的地 + 播放页路由可达，返回栈实测正常；统计页 Phase 4 已实装并与 DB 对账；**转录页与设置页仍是 placeholder**） |
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
| FR-501 | 只用本地模型，不调在线 ASR API | 网络权限层面：转录路径无任何 HTTP 客户端 | PARTIAL：`INTERNET` 权限只服务「把模型取回本机」这一件事（`asr/HttpFetcher`），转录路径仍然没有任何网络调用（引擎本身还没实现，Phase 6 后续项落地时不许往里加）。边界要说清：**模型准备需要网络，准备完之后飞行模式可用**（需求「离线可用」的前提是「首次准备」） |
| FR-502 | 通过 `AsrEngine` 接口封装加载与推理；sherpa-onnx 实现与测试替身实现并列 | 单测跑替身引擎，无需模型 | |
| FR-503 | 中文书解优先验证 Paraformer 中文离线模型；时间戳能力按 `asr-design.md` 的确证结论落地（若模型不给可靠句级时间戳，用其确证的替代路线并记录误差） | 设计文档有真实来源 URL，不虚构 | |
| FR-504 | 转录流水线：MP3 解码 → 16kHz 单声道 PCM → 分段（不整本载入内存，峰值受控）→ 逐段识别 → 结果合并 → 切句 | 单测：分段边界与偏移；集成测试：真实 MP3 | PARTIAL：**中间三段已有代码**——`domain/asr/PcmConversion`（16-bit LE → 单声道 float，均值下混）、`domain/asr/SincResampler`（源采样率 → 16k 的抗混叠 FIR，流式、跨块只留 33 个样本）、`domain/asr/AsrChunkPlanner`（>20s 均分成 ≤20s 块）共 36 条 JVM 单测；**MP3 解码（`PcmDecoder`）、VAD、识别、合并、切句还没有**，所以整条流水线一次都没跑过，且这些新类目前无任何调用方 |
| FR-505 | 分段的时间偏移必须累加，**每段字幕时间不得从 0 重新开始** | 单测：第 N 段首句 start_ms = 段起点 | PARTIAL：换算只有一处（`AsrChunkPlanner.msOf`，绝对样本下标 → 毫秒），14 条单测含「180 段连续 20s 音频末块 `endMs == 3_600_000`」的零漂移断言与「每块起点 = 上一块终点、互不重叠」；**但「首句 start_ms」要等真实 token 时间戳**（模型 `timestamps` 的单位仍未在设备上确证），故不写 DONE |
| FR-506 | 结果入库缓存，同一音频不重复识别（除非文件变更或用户强制重转） | 二次进转录直接 READY | |
| FR-507 | 任务支持排队/取消/重试/进度显示；前台服务 + 通知；应用重启后未完成任务可恢复（不自动重跑已完成部分则记录为设计取舍） | 集成测试：取消后状态 CANCELLED，重试回到 QUEUED | |
| FR-508 | 模型不可用/文件损坏/内存不足 → 明确错误文案与可执行动作（重新准备模型/重试），App 不崩溃且仍可播放 | 设备验证 | |
| FR-509 | 转录与播放互不阻塞（不同线程/不同资源；模型加载不在主线程） | 设备验证：播放中启动转录不卡顿 | |
| FR-510 | App 本体与模型分离：首次使用时下载**或**本地文件导入模型包；校验通过后入库；模型信息（名称/版本/文件清单/大小/SHA-256/来源）集中在一个清单里 | 清单里没有虚构值；来源标注清楚 | PARTIAL：清单在 `asr/ModelCatalog.kt`（数值由 `ModelCatalogTest` 钉住，`tokens.txt` 标 `officialSha256=null`）；在线下载路径 `ModelManager.download` 已实现「`.part` → 比大小 → 整份 SHA-256 → 改名」并写 `download-provenance.properties`，删除走 `ModelManager.delete`；真实 hf-mirror URL 已在设备上由 App 自己的代码取回并核对（`androidTest/asr/ModelManagerNetworkTest`，只到 75 KB 的 `tokens.txt`，243 MB 那份与 GitHub 单源的 VAD 仍未在设备取回过）；**本地导入（SAF 选目录）未实现**；没有 UI 入口用它（`docs/testing.md` §4） |
| FR-511 | 不把模型塞进 APK（体积已评估，见 `asr-design.md`） | `assets/` 无模型 | DONE（`unzip -l app-debug.apk` 里没有任何 `assets/` 条目与 `.onnx`；模型只在运行时落到 `filesDir/models/`） |

## 6. 字幕切句与时间戳

`SubtitleSegment`（domain 类型）：`index`、`start_ms`、`end_ms`、`text`。落库为 `subtitle_segment`（v2 起），另有 `source`（`ASR`/`MANUAL_TEST`）与可空且暂无外键的 `job_id`，理由见 `database-design.md` §5.1。

| ID | 需求 | 验收标准 |
| --- | --- | --- |
| FR-601 | 按 `start_ms` 时间序保存，`segment_index` 单调 | 查询按 `(audio_id, segment_index)` |
| FR-602 | 中文标点切句：`。！？；!?;` 优先，`，、` 作为长句兜底切分；单句最大长度受限（默认 60 字，超出按标点或空格回退切分） | 单测含无标点长文本 —— **未实现，属 Phase 6**（切句对象是模型输出的整段文本；Phase 5 的测试字幕通道直接读现成带时间的字幕文件，不需要切句） |
| FR-603 | 处理异常时间戳：负值、end<start、与上一段重叠、零长度 | 单测：清洗后单调不减、时长 > 0 —— **部分**：导入解析器 `TestSubtitleParser` 会拒绝 `end ≤ start`（整份失败，不补零）并按时间重排重编号；对模型输出的负值/重叠清洗属 Phase 6 |
| FR-604 | 句子起止时间必须来自模型时间信息或实际对齐结果，**禁止按字数均分时间伪造精准字幕** | 代码审查；若为对齐结果，`asr-design.md` 记录误差 |
| FR-605 | 重新转录时安全替换旧字幕：单事务删旧插新 | 集成测试：替换后无残留、无半套字幕 |
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
| FR-901 | 模型状态与版本、准备/更新/删除模型、本地导入模型包 | 设置页可见真实状态；删除后播放仍正常 | TODO（设置页一行代码都还没有；底下的 `asr/ModelManager.statuses` / `download` / `delete` 与 `ModelCatalog` 已实现并有单测，UI 只许读它们，不许再算一遍大小或哈希——见 FR-510） |
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
