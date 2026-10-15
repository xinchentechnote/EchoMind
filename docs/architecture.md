# 架构设计（architecture）

## 1. 模块决策：先单 Gradle 模块 + 包分层

首版用**一个 Android Library/Application 模块 `:app`**，靠包结构分层。理由（也写进了 `environment.md` 的约束）：

- AGP 9.4.1 + Gradle 9.6.0 在本机已缓存，单模块构建最快、离线风险面最小；多模块会把 `lint`、`checkReleaseBuilds` 之类的坑放大。
- sherpa-onnx 已确证是**本地 AAR**（`app/libs/*.aar` 不入库，CI 按钉住的 SHA-256 从官方 release 取回，见 §10 D23），不是 Maven 坐标。所以「拆 `:asr` 会让构建脚本返工」这条依然成立，只是原因变成了 ABI 过滤与 AAR 归属；当前推理只有两个类，仍按单模块走。
- 分层靠接口约束，不靠 Gradle 边界。

**拆模块的触发条件**（记录，避免无动机拆分）：
1. `:asr` 需要独立 AAR 依赖与 ABI 过滤 → 拆出 `:asr`（含 sherpa-onnx 依赖）。
2. UI 模块数量 ≥ 3 且出现构建时间明显退化（>60s 增量构建）。
3. 需要把 `domain` 作为纯 JVM 模块强制编译期隔离（当前用「domain 包不 import Android 类型」+ 单测约束）。

## 2. 包结构

```
app/src/main/java/com/xinchen/echomind/
├── EchoMindApp.kt                 // Application：手动依赖组装（AppGraph）
├── MainActivity.kt                // 单 Activity + Compose 导航
├── core/
│   ├── time/TimeSource.kt         // ✅ 单调时钟抽象（可注入，测试替换）
│   ├── time/AndroidTimeSource.kt  // ✅ elapsedRealtime / currentTimeMillis 的实现
│   ├── conf/PlaybackPolicy.kt     // ✅ 全部统计/播放阈值集中处
│   └── conf/AudioFormats.kt       // ✅ 扩展名白名单、采样率、分块大小 + VAD/识别器参数（唯一出处）
├── domain/                        // 纯 Kotlin，不 import android.*
│   ├── model/ImportModels.kt      // ✅
│   ├── model/PlaybackModels.kt    // ✅ ListeningSessionData / PlaybackProgress / SessionEndReason / SessionStatus
│   ├── model/SubtitleModels.kt    // ✅ SubtitleSegment（index/startMs/endMs/text）
│   ├── model/TranscriptModels.kt  // ✅ TranscriptJobRow / TranscriptJobStatus / TranscriptFailure / TranscriptModelRef / EnqueueOutcome
│   ├── transcript/TranscriptRepository.kt // ✅ 任务状态机入口接口（实现在 data/repo）
│   ├── importing/AudioTreeWalker.kt   // ✅ 递归遍历（TreeBrowser 抽象）
│   ├── importing/AudioImportPlanner.kt// ✅ 差量判定 NEW/CHANGED/UNCHANGED/VANISHED/REVIVED
│   ├── playback/PlaybackController.kt // ✅ 接口 + QueueItem + PlaybackUiState + resumePositionFor
│   ├── playback/SessionTracker.kt     // ✅ 会话状态机：计时、有效次、完播判定
│   ├── text/TitleDeriver.kt       // ✅ 文件名→默认标题
│   ├── subtitle/SubtitleSynchronizer.kt // ✅ 当前句二分查找 + 七句窗口 + gap 迟滞
│   ├── subtitle/TestSubtitleParser.kt   // ✅ SRT/VTT/TSV → 带时间的句（只服务调试导入通道）
│   ├── text/SentenceSplitter.kt     // — Phase 6（切句对象是模型输出的整段文本）
│   ├── asr/PcmConversion.kt         // ✅ 16-bit LE 字节 → 单声道 float（均值下混），纯 Kotlin
│   ├── asr/SincResampler.kt         // ✅ 源采样率 → 16k 的抗混叠流式重采样（窗口 sinc，33 抽头）
│   ├── asr/AsrChunkPlanner.kt       // ✅ >20s 语音段均分成 ≤20s 块 + 绝对毫秒偏移（FR-505）
│   ├── asr/TokenTimingMapper.kt     // ✅ 模型逐字「秒」→ 绝对毫秒；数量不符/越界/倒序一律抛 Mismatch，不猜
│   └── model/AsrModels.kt           // ✅ SpeechSpan / PcmChunkPlan / TokenTiming / ChunkRecognition（不暴露任何 native 类型）
├── data/
│   ├── db/                        // ✅ Entity / DAO / EchoMindDatabase / Migrations
│   │   └──（v3 实到 6 表：imported_folder、audio_book、playback_state、listening_session、subtitle_segment、transcript_job；`Migrations.ALL` = 1→2、2→3 两条）
│   ├── repo/                      // ✅ AudioRepository、PlaybackRepository、SubtitleRepository、TranscriptRepository、TreeGrantStore
│   │   └── SettingsRepository — Phase 7
│   └── scan/                      // ✅ SafTreeBrowser（DocumentFile→TreeBrowser）、AudioMetadataReader（Media3 时长）
├── playback/
│   ├── Media3PlaybackController.kt// ✅ 实现 + Player.Listener + 统计事件翻译 + 串行落库通道
│   ├── PlayerHolder.kt            // ✅ 单例 ExoPlayer 持有（同进程共享给 Service；不提供 release()）
│   └── EchoMindMediaService.kt    // ✅ MediaSessionService + addSession + 前台通知 + onTaskRemoved
├── asr/                           // 模型准备 ✅ + 解码 ✅ + VAD ✅ + 推理 ✅（端到端串联还没有；入库的状态机与事务在 data/repo）
│   ├── ModelCatalog.kt            // ✅ 清单唯一出处：URL/字节数/SHA-256 全部实测，无官方校验值标 null 并本机钉住
│   ├── ModelVerifier.kt           // ✅ 存在 → 大小 → SHA-256 三步核对 + 流式哈希（纯 JVM，可单测）
│   ├── ModelFetcher.kt            // ✅ `fun interface ModelFetcher`（测试接缝）+ `HttpFetcher`（HttpURLConnection，手工跟 Location）
│   ├── ModelManager.kt            // ✅ statuses/download/delete/refresh：`.part` → 校验 → 改名 → 记来源，多源回退、文件级跳过、250ms 进度限流
│   ├── PcmDecoder.kt              // ✅ MediaExtractor+MediaCodec 同步模式流式解码 → 16k 单声道定长块；4 条仪器化在真 MP3 上跑过
│   ├── VadSegmenter.kt            // ✅ silero VAD 流式封装：push 块 → 出段（绝对样本下标 + 段内样本），6 条仪器化
│   ├── SherpaOnnxEngine.kt        // ✅ 离线 Paraformer 封装：open 前按清单核对，recognize(块) → 逐字绝对毫秒；8 条仪器化
│   └── TranscriptCoordinator      // — Phase 6 第 4 项（把上面三层串起来；取消/重试/入库的仓储接口已经就绪，等它来调）
│       （`PcmResampler` 这个名字不再需要：采样率转换落在 `domain/asr/SincResampler`）
├── work/                          // TranscriptWorker / FolderRescanWorker — Phase 6
│   └──（当前无 WorkManager：重扫是 appScope 里的 `Dispatchers.IO` 协程）
└── ui/
    ├── theme/Theme.kt             // ✅ Material 3
    ├── nav/Destinations.kt        // ✅ 路由
    ├── common/Format.kt           // ✅ 时长/相对时间（JVM 可测）
    ├── common/SafContracts.kt     // ✅ 带授权位的文件夹选择契约
    ├── shelf/                     // ✅ ShelfScreen + ShelfViewModel
    ├── player/                    // ✅ PlayerScreen + PlayerViewModel
    ├── stats/                     // ✅ StatsScreen + StatsViewModel
    └── settings/ transcript/      // — Phase 7 / 6（底部导航当前是「书 / 转 / 统 / 设」四个入口）
```

测试目录同结构镜像（`app/src/test/...` 纯 JVM，`app/src/androidTest/...` 仪器化）。

## 3. 分层规则（谁可以依赖谁）

```
ui  ──►  domain  ◄──  playback / asr / data
 └────────►  data(repo)
```

- `domain` 不依赖 Android、不依赖 Room、不依赖 Media3；输入是数字与数据类，输出是判定结果。**统计口径与字幕算法全在这里**，因此可纯 JVM 单测。
- `data` 只做持久化与文件系统，不含业务判定（除明确的 SQL 聚合）。
- `playback` 与 `asr` 是系统能力适配层：把 Media3 / MediaCodec / sherpa-onnx 的回调翻译成 domain 的输入。
- `ui` 只与 `PlaybackController` 接口和 ViewModel 暴露的 StateFlow 交互；**禁止**在 Composable/Activity 里构造 `ExoPlayer`、写数据库、跑推理。
- 依赖组装在 `AppGraph`（`EchoMindApp` 内），手工 new；不用 Hilt/Koin（见 §7）。

## 4. 关键接口

```kotlin
interface PlaybackController {                       // UI 唯一播放入口（实现：Media3PlaybackController）
    val state: StateFlow<PlaybackUiState>            // audioId,title,isPlaying,buffering,positionMs,durationMs,speed,queue,index,error
    fun play(queue: List<QueueItem>, index: Int, startPosMs: Long)
    fun togglePlayPause(); fun seekTo(ms: Long); fun skip(byMs: Long)
    fun setSpeed(rate: Float); fun next(); fun previous(); fun stop()
}

interface AudioRepository {                       // Phase 3 已实现（AudioRepositoryImpl）
    fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>>
    fun lastPlayed(): Flow<ShelfRow?>
    fun folders(): Flow<List<ImportedFolderEntity>>
    suspend fun importFolder(treeUri: Uri): ImportOutcome
    suspend fun rescanAll(): List<ImportOutcome>
    suspend fun rename(audioId: String, title: String)
    suspend fun setFavorite(audioId: String, favorite: Boolean)
    suspend fun removeBook(audioId: String)
    suspend fun removeFolder(folderId: String)
    suspend fun markPermissionRevoked(folderId: String)
}

/** 目录能力抽象：SAF 实现 + 测试替身，让递归遍历留在纯 Kotlin 的 AudioTreeWalker 里 */
interface TreeBrowser {
    fun children(dirUri: String): List<TreeEntry>
    fun treeDisplayName(treeUri: String): String?
}

/** 授权接缝：ContentResolver 的持久化授权无法在测试里授予/撤销，不抽出来就测不了 FR-110 */
interface TreeGrantStore {
    fun persistRead(treeUri: String): String?      // null=成功，否则返回失败原因
    fun hasReadGrant(treeUri: String): Boolean
    fun grantedTreeUris(): List<String>
}

interface PlaybackRepository {                        // 播放记忆 + 统计（Phase 4 已实现，PlaybackRepositoryImpl）
    fun snapshot(audioId: String): Flow<PlaybackStateSnapshot>
    fun stats(): Flow<PlaybackStats>                  // 统计页全局数字，从 listening_session 真源算，不读聚合缓存
    fun recentSessions(limit: Int): Flow<List<RecentSessionRow>>
    suspend fun openSession(audioId: String, title: String, startPositionMs: Long, durationMs: Long)
    suspend fun flushProgress(progress: PlaybackProgress, speed: Float)
    suspend fun settleSession(session: ListeningSessionData, speed: Float)
    suspend fun saveSpeed(audioId: String, speed: Float)
    suspend fun recoverCrashedSessions(): Int         // FR-405，幂等，任何写之前自动先跑
    suspend fun recomputeAggregates()                 // FR-410/905
}

// ✅ Phase 6 实现（asr/SherpaOnnxEngine.kt）。**没有 AsrEngine 接口**：只有一个实现、没有第二个替身，
//   套接口等于给「未来可能换引擎」预付费用。见 `docs/asr-design.md` §4 末的取舍。
class SherpaOnnxEngine private constructor(...) {
    companion object { fun open(modelsRoot: File): SherpaOnnxEngine }   // 主线程调用直接抛；按 ModelCatalog 核对后才加载；实测装载 4,906~4,914 ms
    fun recognize(chunk: PcmChunkPlan, samples: FloatArray): ChunkRecognition  // 样本数必须与块一致、≤20s，否则 AsrInputException
    fun release()                                                        // 幂等；之后 recognize 立刻抛，不会 use-after-free
}
// ✅ Phase 6 实现（asr/VadSegmenter.kt）：流式喂块，段带样本回来（整章音频从不驻留内存）
class VadSegmenter(modelFile: File, sampleRate: Int = 16_000) {
    fun push(samples: FloatArray): List<VadUtterance>   // 攒满 512 样本的窗才推进模型；尾巴留住
    fun finish(): List<VadUtterance>                    // 补最后一窗 + vad.flush() + 排空
    fun release()
}
// VadUtterance(startSample: Long, samples: FloatArray)：startSample 是 16k 下的**样本下标**（实测落在 1 ms=16 样本网格上），
// span 属性直接给出 domain 的 SpeechSpan，所以分段结果不经过任何 native 类型就进了纯 Kotlin 的 AsrChunkPlanner。

class ModelManager(modelsRoot: File, timeSource: TimeSource, specs: List<ModelSpec>, fetcher: ModelFetcher, ioDispatcher: CoroutineDispatcher) {
    val statuses: StateFlow<List<ModelStatus>>        // UI 只读这一个：verification / isDownloading / download / error
    suspend fun refresh(): List<ModelStatus>          // 核对本机文件，不做任何网络调用
    suspend fun download(spec: ModelSpec): ModelVerification   // 失败抛 ModelDownloadException，原因同时写进 statuses 的 error
    suspend fun delete(spec: ModelSpec): ModelVerification     // 只动 filesDir/models/<id>/，字幕与收听历史不碰
}
// 取舍：`ModelManager` 是类不是接口。它的测试接缝放在**依赖**上——`fun interface ModelFetcher` 用假实现注入，
// 于是「下载状态机」在 JVM 单测里可测，不需要再给 ModelManager 套一层接口。本地导入（`importFrom(uri)`）还没实现，等它落地时再谈签名。
// ✅ Phase 6 实现（domain/transcript/TranscriptRepository.kt + data/repo/TranscriptRepositoryImpl.kt）
interface TranscriptRepository {
    fun jobs(): Flow<List<TranscriptJobRow>>                       // 转录页列表，新建在前
    fun observeActiveJob(audioId: String): Flow<TranscriptJobRow?>  // 书架那一格与「重试」按钮读它
    suspend fun enqueue(audioId: String, model: TranscriptModelRef, force: Boolean = false): EnqueueOutcome
    suspend fun cancel(jobId: String): Boolean                      // false = 那行已经结束了，取消晚了
    suspend fun retry(jobId: String, model: TranscriptModelRef): EnqueueOutcome   // 新建一行，旧行留着当错误上下文
    suspend fun markRunning(jobId: String, totalSegments: Int): Boolean
    suspend fun updateProgress(jobId: String, doneSegments: Int, totalSegments: Int): Boolean
    suspend fun commitSubtitles(jobId: String, segments: List<SubtitleSegment>): Int   // 单事务：删旧+插新+SUCCESS+READY
    suspend fun markFailed(jobId: String, failure: TranscriptFailure, message: String?): Boolean
    suspend fun recoverInterrupted(): Int                            // FR-507，幂等
}
// 状态跃迁一律带 `WHERE status IN (...)` 守卫并返回受影响行数 → 非法跃迁/重复结算是 `false`，不是异常。
// 唯一例外是 `commitSubtitles`：那条只有成功分支，跃迁失败就抛、事务整体回滚（字幕与任务状态必须同生同死）。
// `TranscriptModelRef` 由 `asr/ModelCatalog.transcriptRef` 给出（模型 id + 权重 SHA-256 前 12 位），
// 调用方传进来而不是让 domain 反向 import `asr`。
interface SubtitleRepository {                         // ✅ Phase 5 实现（data/repo/SubtitleRepository.kt）
    fun observeSegments(audioId: String): Flow<List<SubtitleSegment>>   // 整本按 segment_index 升序
    fun observeSummary(audioId: String): Flow<SubtitleSummary>          // 句数 + 是否 ASR 产物
    suspend fun replace(audioId: String, segments: List<SubtitleSegment>, source: SubtitleSource): Int  // 单事务整本替换
    suspend fun clear(audioId: String): Int
}

// ✅ Phase 5 实现：**无状态 class**（domain/subtitle，只 import 数据类），不持有 Flow、也不自带节流
class SubtitleSynchronizer(segments: List<SubtitleSegment>, radius: Int, gapEnterMarginMs: Long) {
    fun windowAt(positionMs: Long): SubtitleWindow     // 二分当前句 + 七句窗口
    fun currentIndexAt(positionMs: Long): Int          // -1 = 还没有句开始
}
// 节流由调用方做：PlayerViewModel 对 windowAt 的结果套 distinctUntilChanged()（FR-707）
```

## 5. 数据流

### 5.1 导入（Phase 3 已实现，名字与代码一致）
```
用户选树 Uri（OpenPersistableDocumentTree：请求意图带 READ|PERSISTABLE）
 ─► TreeGrantStore.persistRead(uri)        只传模式位，失败原因进异常消息
 ─► withContext(Dispatchers.IO)：
     AudioTreeWalker(TreeBrowser) 递归，扩展名白名单来自 AudioFormats，深度/数量超限标 truncated
  ─► AudioMetadataReader 逐个取时长（失败 → durationMs=0 + duration_read_failed，不阻塞）
  ─► AudioImportPlanner：按 URI 唯一键判 NEW / CHANGED(size|mtime) / UNCHANGED / VANISHED / REVIVED
     （REVIVED = 之前 MISSING、这次原样回来：内容没变也必须翻回 PRESENT，否则书架永远灰着）
  ─► @Upsert 批量写 + setFileStatus 批量标 MISSING/PRESENT + folderDao.markScanned
 ─► Room Flow 驱动书架刷新（UI 不轮询）
```

### 5.2 播放与统计（口径实现见 `playback-design.md`）
```
UI 命令 ─► PlaybackController(= Media3PlaybackController) ─► ExoPlayer（PlayerHolder 里全 App 唯一实例）
Media3PlaybackController 自己实现 Player.Listener
   ├─ onPlayWhenReadyChanged / onPlaybackStateChanged / onIsPlayingChanged / onPlaybackSuppressionReasonChanged
   │        ─► SessionTracker.onPlayStarted/onPlayStopped（domain，单调时钟，不读 ExoPlayer）
   ├─ 500ms tick（UI_TICK_INTERVAL_MS）─► SessionTracker.onPosition + pushState（只推 StateFlow，不写库）
   ├─ 每 10s 或状态跃迁 ─► PlaybackRepository.flushProgress（事务）
   └─ 会话结束（自然播完/用户停止/切歌/焦点丢失/耳机拔出）
            ─► SessionTracker.endSession(reason) 得 ListeningSessionData
            ─► PlaybackRepository.settleSession（同一事务：会话行 + 聚合缓存增量）
所有落库经一条 `Channel`（`writes`）串行：Media3 回调是并发进来的，
不串行就会有两个协程同时对同一行「读-改-写」，聚合值各自更新一半。
聚合值 = 增量维护；recomputeAggregates 从会话表全量覆盖，用作崩溃后的对账（FR-410）
EchoMindMediaService(MediaSessionService) ── addSession(MediaSession.Builder(this, PlayerHolder.get())) ──► 通知栏/媒体键
```

### 5.3 转录
```
TranscriptRepository.enqueue ✅ ─► WorkManager(前台类型 mediaPlayback? 见下) 
  ─► TranscriptCoordinator(代码里还没有，Phase 6 第 4 项):
       PcmDecoder ✅(MediaExtractor+MediaCodec 同步流式 → PcmConversion 均值下混 → SincResampler 到 16k → 定长块回调)
       ─► VadSegmenter ✅(silero VAD，float 块 → VadUtterance(绝对样本下标 + 段内样本)，段与段不重叠，≤20 s 强制切段)
       ─► AsrChunkPlanner ✅(>20s 段均分成 ≤20s 块，用全局样本下标算绝对 startMs/endMs)
       ─► SherpaOnnxEngine ✅.recognize(块) ─► ChunkRecognition(text + tokens[绝对毫秒])
       ─► SentenceSplitter(标点切句 + 时间清洗 — 还没有，Phase 6 第 5 项)
       ─► TranscriptRepository.commitSubtitles ✅(Room 单事务：删旧字幕 + 插新字幕(source=ASR, job_id) + job=SUCCESS + audio.transcriptionState=READY)
  ─► 进度每块更新一次（内存节流 ≥1s 写库）
```
前三步已在 emulator-5554 上真串起来跑过（`VAD段逐块识别出来的时间是全局单调的`：60 s 素材 → 9 段 → 6 块 → 58 个逐字时间，
跨块单调、全部落块内），但**串联代码在测试里，不在生产代码里**：`TranscriptCoordinator` 与 WorkManager 还没写。
任务这一侧 v3 已经落地（`transcript_job` 表 + `TranscriptRepository` 全套跃迁 + 16 条仪器化用例），
也就是说图上最后那两格是**能跑的、有人测过的**，只是现在还没有调用方——模型识别出的字句至今一次都没进过生产数据库。
一条实测到的坑：块的边界不等于句子的边界——同一段歌词整块 20 s 识别出「那片笑声让我想起我的那些年」，
被 VAD 切成两块后给出「偏笑声让」+「想起我的那些话」。逐字时间依然有效，但文本要靠 `SentenceSplitter` 收拾，不能拿块文本直接当字幕。
前台服务用于长任务通知：转录用 `ForegroundInfo`（WorkManager 支持在 worker 返回 `Result` 前 `setForeground`），不额外起 Service。

### 5.4 字幕同步（Phase 5 已实现，名字与代码一致）
```
controller.state.map { it.positionMs }        // 500ms tick，即 PlaybackPolicy.UI_TICK_INTERVAL_MS，不另开定时器
 ─► SubtitleSynchronizer.windowAt(pos)        // 对 startMs 数组二分查当前句 + GAP_ENTER_MARGIN_MS 迟滞
 ─► distinctUntilChanged() ─► StateFlow<SubtitleWindow>   // 仅窗口内容变化才更新 UI（FR-707）
点击句 ─► controller.seekTo(segment.startMs)
```
挂在 `PlayerViewModel` 里：`playingId.flatMapLatest { subtitleRepository.observeSegments(it) }` 先把整本字幕换成一个**无状态**的 `SubtitleSynchronizer`，再对控制器的实时位置求窗口。位置只有控制器一份，ViewModel 不另存（AGENTS.md §4 的「两处各存一份必然对不上账」）。
字幕数量与来源走另一条流 `observeSummary()`，UI 用它决定「显示窗口」还是「如实说明还没有字幕」，并常驻标注是不是 ASR 产物。

## 6. 线程与并发

| 工作 | 调度 |
| --- | --- |
| Room 读写 | `Dispatchers.IO` + Room transaction；DAO 返回 `Flow` |
| 播放事件的落库 | 控制器内 `Channel<suspend () -> Unit>(UNLIMITED)`，单个 IO 协程按序消费：Media3 回调并发进来，串行才不会有两个写对同一行「读-改-写」。每个 op 用 `runCatching` 包住，失败只 `Log.w` 后跳过（不重试、不崩播放），所以**统计落库失败会静默丢这一笔**，见 §10 D13 |
| 目录扫描 | `Dispatchers.IO`（WorkManager 或 appScope 协程） |
| 播放器回调 | Media3 应用线程（主线程），只做轻量状态搬运 |
| PCM 解码/重采样/VAD | **设计**：转录 worker 的协程/线程池，`Dispatchers.Default`（`PcmDecoder`/`VadSegmenter` 代码已就绪，worker 还没写，目前只在仪器化测试的调用线程上跑） |
| ASR 推理 | **设计（代码里还没有）**：单线程串行（`asrDispatcher = newSingleThreadContext`），避免多模型实例并存；同一时刻只一个引擎句柄。当前只有仪器化测试在调用线程上直接 `SherpaOnnxEngine.open()`，两个类里的 `check(Looper… != getMainLooper())` 是这条约束目前唯一的执行者 |
| UI | Compose + `collectAsStateWithLifecycle` |

约束：
- 全 App 只有 1 个 ExoPlayer 实例；只有 1 个 ASR 引擎句柄；转录与播放可并行但互不共享线程。
- 播放位置的持久化节流（10s）与 UI 刷新（200ms~1s）分离，避免 DB 写放大。

## 7. 依赖注入与可测试性

- **手工 DI**（`AppGraph` 一个对象 + ViewModelFactory）。理由：Hilt/KAPT 在本机是新增依赖面 + 注解处理器，AGP 9 内置 Kotlin 下的兼容性未实测；项目规模不需要图管理。若后续模块拆分导致装配复杂，再评估。
- Room 走 **KSP**，实测版本 `com.google.devtools.ksp:2.3.12`（`libs.versions.toml`）。注意 KSP 的 2.3.x 是它自己的编号，**不存在对应的 Kotlin 2.3.12**，不要为了「对齐 KGP」去改它。`kapt` 被 AGP 9 判定与 built-in Kotlin 不兼容（硬错误），所以没有 kapt 退路；真要出问题就换 KSP 版本，不改注解处理器路线。
- 可测试性设计：`TimeSource`、`PlaybackPolicy`、`SessionTracker`、`SubtitleSynchronizer`、`TestSubtitleParser` 全是纯 Kotlin，无 Android import，可在 JVM 里跑。
  `asr/ModelCatalog`、`ModelVerifier`、`ModelFetcher`、`ModelManager` 同理（只用 `java.io.File` / `java.net` / `MessageDigest`），35 条 JVM 单测就是这么来的；
  它们的**接缝在依赖上**（注入假 `ModelFetcher` + `FakeTimeSource` + `TemporaryFolder`），不是靠给 `ModelManager` 套接口。
  `domain/asr/PcmConversion`、`SincResampler`、`AsrChunkPlanner` 也是同一类接缝（合成信号 + 手搓字节，37 条 JVM 单测），它们**不需要设备**：
  没有 Android 类型，靠单频探针就能验出「滤波器有没有在工作」。`domain/asr/TokenTimingMapper` 也是同一层：
  把「秒还是毫秒」这种猜错就偏移 1000 倍的换算规则做成纯函数，14 条 JVM 单测把越界/倒序/数量不符都钉成异常，设备上一旦出现就能立刻认出来。
  反过来 `asr/PcmDecoder` 是**必须上设备**的那一层：
  它吃 `Context`/`Uri`，只能测「这台机器的解码器实际给什么」——4 条仪器化用例（真 MP3 时长对账、块连续性、幅度、取消、非音频），
  接缝是 `fun interface PcmChunkSink`：解码逻辑与「拿到块以后干什么」在测试里被分开。
  `asr/VadSegmenter`（6 条）与 `asr/SherpaOnnxEngine`（8 条）同属必须上设备的一层，而且**比解码更贵**：它们要真模型（243 MB）才动得起来，
  所以引擎侧的可测性做法是「一个类共用一个句柄」（`@BeforeClass` 装、`@AfterClass` 释放）+ 把单位判据写成断言而不是日志。
  这也意味着没有 `AsrEngine` 接口就没有假引擎：JVM 侧只能测到 `TokenTimingMapper` 与 `AsrChunkPlanner` 这一层，端到端必须真跑。
  `text/SentenceSplitter` **目前代码里还没有**（Phase 6 第 5 项）。

## 8. 错误处理与降级

| 场景 | 行为 |
| --- | --- |
| SAF 授权失效（`SecurityException`/无持久化授权） | 该文件夹标 `REVOKED` 并在重扫时跳过（其他文件夹照常扫），提示「文件夹授权已失效，请重新授权」；**不删任何记录**、历史保留。按文件夹的「重新授权」入口在 Phase 7 的设置页 | 
| 时长读取失败 | `durationMs = 0`（未知），进度条禁用但可播放，统计按会话结束时的实际最大位置 |
| 播放器报错 `onPlayerError` | **Phase 4 已实现**：`Log.w("EchoMindPlayback")` + 先 `flushNow()` 结算这场（已经听过的时间要落账），再把 `无法播放（<errorCodeName>）` 透出到 `PlaybackUiState.error`，播放页红字显示。不做自动重试、不自动跳下一首 |
| 无字幕 / 字幕表为空 | **Phase 5 已实现**：七句窗口不渲染，卡片里如实写「还没有字幕。本地转录（Phase 6 的 sherpa-onnx）接入后这里会自动出现，之前不会放任何假字幕。」；没有「生成字幕」入口（那是 Phase 6）。debug 包多一个「导入测试字幕」按钮，release 包按 `FLAG_DEBUGGABLE` 判掉 |
| 测试字幕文件解析失败 | 整份拒绝并把**行号 + 原因**回给用户（`导入失败：第 N 行的开始时间无法解析…`），绝不退化成补零或按字数均分（AGENTS.md §5） |
| 模型缺失/损坏 | **引擎侧已实现（Phase 6 第 3 项）**：`SherpaOnnxEngine.open()` 先按 `ModelCatalog` 走 `ModelVerifier`（存在 → 大小 → SHA-256），任一步不合格抛 `AsrModelNotReadyException`，消息里点名缺哪个文件、目录在哪（用例 `模型不合格时引擎拒绝装载并把原因写清楚`）。**没有「文件在就用」**。设置页的下载/导入入口与「禁用转录入口」还没做（Phase 6 第 6 项）；**播放不受影响**（播放路径不加载模型） |
| 推理 OOM | — **还没做**（Phase 6 第 4 项）。现在只有两条前置拒绝：单块 ≤20 s、样本数与块描述一致。装载 243 MB 模型在 2 GB RAM 真机上的峰值 RSS 未实测（模拟器上装载 ~4.9 s、识别 RTF 0.083~0.143，两轮同一素材差 1.6 倍，只能当量级看） |
| 进程被杀 | 启动时 `recoverCrashedSessions`（幂等，任何写之前自动先跑）把残留 `ACTIVE` 行按最后一次 flush 结算为 `CRASHED` |
| Room 迁移失败 | 不做 destructive 兜底；崩溃前记录并提示（正式版策略：修复迁移） |

## 9. UI 结构

**现状（Phase 6 第 1-3 项之后，UI 未变）**：单 Activity + Compose Navigation，5 个目的地：`shelf`（全部/最近/收藏 tab + 搜索）✅ → `player/{audioId}`（播放/暂停、±15s、拖动、倍速 5 档、上下曲、收藏、停止、本首统计、**七句字幕窗口**：当前句高亮、点一句跳到该句起点、数量与来源常驻标注、无字幕时如实说明）✅ → `transcript`（**placeholder，标注 Phase 6**）→ `stats`（总时长/次数/完播 + 最近收听 + 规则说明）✅ → `settings`（**placeholder，标注 Phase 7**）。底部导航固定 4 个：书 / 转 / 统 / 设。主题 Material 3，Android 12+ 走 dynamic color（`dynamicColor=true`），深浅色由系统决定（App 内切换在 Phase 7）。

**尚未实现的目标（别当现状读）**：播放页从上往下滑出迷你条；设置页改字号与深浅色；「重算统计」入口；按文件夹的重新授权入口。这些都在 Phase 7。

遵循用户偏好：菜单优先于按钮排、tooltip 优先于外露路径文本、行内编辑标题（当前重命名仍是对话框 → Phase 7 换）、支持键盘快捷键（有实体键盘时）。

## 10. 决策记录（ADR 摘要）

| # | 决策 | 取舍 |
| --- | --- | --- |
| D1 | 单 Gradle 模块起步 | 换取构建可控与离线安全；代价是编译隔离靠约定 |
| D2 | 手工 DI，不引 Hilt | 少一个注解处理器依赖面；代价是装配代码手写 |
| D3 | 聚合计数以 `ListeningSession` 为真源 + 增量缓存 | 多一次重算入口，换取崩溃后不重复累计的可对账性 |
| D4 | 收听时长 = 墙上播放时间（不乘倍速），完播判定用 `consumed_audio_ms` | 两个口径分别满足需求 4.4 的第 9 条与「倍速」注释；口径写在 `PlaybackPolicy` 与统计页说明里 |
| D5 | 转录用 WorkManager + `setForeground`，不自建 Service | 复用系统重启恢复；代价是取消语义要配合 `cancelWorkflow` |
| D6 | 不复制音频进私有目录 | 省空间；代价是每次播放都走 SAF URI 读，且授权撤销即不可播 |
| D7 | 首版仅 mp3 | 系统解码器确定支持；其他格式待实测后扩展 |
| D8 | 设置存 Room KV 表而非 DataStore | 单一本地存储真源，备份/迁移一致；代价是设置读写要走 DAO（低频，可接受） |
| D9 | 扫描判定用纯 Kotlin 的 `AudioImportPlanner` + `TreeBrowser`/`TreeGrantStore` 两个接缝，而不是一个 `SafFolderScanner` 类 | 代价是多两个接口；换来 FR-106/107/108/110 全部能在 JVM/仪器化里用假树与假授权跑，尤其是「授权失效」这种无法在测试里真实制造的路径 |
| D10 | 重扫写入用 `@Upsert` 而不是 `@Insert(Authority.ABORT/REPLACE)` | Room 的 REPLACE 在 SQLite 里是 DELETE+INSERT，会触发 `playback_state` 的 `ON DELETE CASCADE`，把一次重扫变成「删书再建书」，进度与统计缓存直接消失。实测生成的 `AudioBookDao_Impl` 里 `upsertAll` 走 `performSuspending(db,false,true)`，批量写仍在单事务内 |
| D11 | 书架行 ID 用 `UUID.nameUUIDFromBytes(uri)` 派生（稳定 ID） | 删书后重新导入同一 URI 能接上 `listening_session` 历史（该表无外键，删书不删历史）；代价是 URI 变化（换设备/重新授权）即视为一本新书 |
| D12 | Phase 3 期间直接往 `version = 1` 的 schema 里加表，不写 Migration | 未发版、开发包数据可丢（实测仪器化测试本来就会清空数据）；代价是**一旦有已安装的真实包就必须冻结 v1**，之后所有变更走显式 Migration（见 roadmap 技术债） |
| D13 | 播放事件的落库走一条 `UNLIMITED` 通道串行，而不是每个回调各自 `launch` | Media3 的 listener 是从应用线程并发回调的，`flushProgress`/`settleSession` 都是「读-改-写」；并发跑会把聚合值各自更新一半。代价：通道无界，理论上写库卡死会堆积（实测未见）；失败只记日志不重试，见 D17 |
| D14 | 自动切歌的会话归属放在控制器 + `SessionTracker.start()` 的双重兜底 | 只在 `onMediaItemTransition` 里结算的话，任何没走这个回调的换曲路径（`play()` 直接换队列）都会把上一首的时长算到新头上。代价是 `start()` 返回「被带走的上一场」，调用方要多落一次库 |
| D15 | 播放/暂停的判据用 `player.isPlaying`，不用 `playWhenReady` | 实测：整队列播完进入 `STATE_ENDED` 时 Media3 仍保留 `playWhenReady=true`，用它判会让「播放」变成一次无效的暂停。代价：`isPlaying` 在缓冲期为 false，得配合 `STATE_READY` 才计时（`updatePlayStop` 里两个条件都在） |
| D16 | 瞬时焦点丢失在 `onPlaybackSuppressionReasonChanged` 处理，并自己记 `waitingForUserToResume` | 实测：`AUDIOFOCUS_LOSS_TRANSIENT` **不走** `onPlayWhenReadyChanged(…, REASON_AUDIO_FOCUS_LOSS)`，只置 suppressionReason；而且焦点回来时 Media3 **自己把 `playWhenReady` 恢复成 true**（铃声响着、队列还在往后播）。要兑现 FR-305「不自动续播」只能在 suppression 归零时立刻再 `pause()`。代价：多一个布尔状态，且与 `resume()` 的三种情形（暂停续播/中断重开/末尾回起点）耦合 |
| D17 | 统计落库失败只 `Log.w("EchoMindPlayback", …)` 后跳过，不重试、不影响播放 | 播放不能被统计写失败拖停；代价是**这一笔统计会永久丢**，事后可以用设置页「重算统计」对账，但重算读的是会话表，丢掉的那场不会回来。当前没有失败率监控 |
| D18 | v1→v2 的 `subtitle_segment` 用**手写 Migration**，DDL 与 Room 导出的 `2.json` 逐字对齐，`MigrationTestHelper` 的 `recreateTableOnMigrationError` 传 `false` | 让 Room 自动迁移的话，带 FK 的表它会整表重建（新表→拷贝→改名→重建索引），一旦生成的 SQL 有问题就是静默改写收听历史；只加一张空表本来也不需要重建。代价：schema 改了要手改 DDL（`Migration_1_2_Test` 会拿导出 JSON 校验结构，漏改当场红）。传 `true` 等于把「迁移失败就重建表」这条破坏性后路请回来，所以显式传 `false` |
| D19 | `kotlinx-serialization` 只在 androidTest 配置里 `resolutionStrategy.force` 到 1.8.1 | 实测：Compose BOM 2026.09.00 用 `{strictly 1.7.3}` 钉住 serialization-core，而 room-testing 2.8.5 的 schema bundle 序列化器要 1.8.1，跑迁移测试直接 `AbstractMethodError: GeneratedSerializer.typeParametersSerializers()`。App 运行时不碰序列化，所以只按配置名（含 `AndroidTest`）覆盖，不动主依赖图、也不降级 Room。代价：BOM 以后换严格版本时这个 force 可能要跟着调，`build.gradle.kts` 里写了原因 |
| D20 | 测试字幕走**独立通道 + 独立来源标记**（`source=MANUAL_TEST`，按钮只在 `FLAG_DEBUGGABLE` 下渲染），不复用 ASR 的写入路径 | 兑现 AGENTS.md §5「测试字幕只能验证管线，不得冒充 ASR 产物」：来源存在行上，UI 常驻标注「非 ASR 产物」，重新导入是整本替换。代价：多一个 debug 按钮与一条解析器（`TestSubtitleParser` 支持 SRT/VTT/TSV），且它不属于最终用户功能 |
| D21 | 书架那一格只描述**转录任务**状态（`transcription_state`），NONE 渲染成「未转录」而不是「无字幕」 | 手工导入的测试字幕故意不动 `transcription_state`（那列归 Phase 6 任务管线），于是一本有测试字幕的书在书架上仍是「未转录」——这句话是真的，而「无字幕」是假的。代价：想显示「有字幕」得让书架查询 JOIN 一次 `subtitle_segment`，为了一个 debug 通道不值得 |
| D22 | Maven 仓库顺序按 `GITHUB_ACTIONS` 环境变量分岔：本机阿里云镜像优先，CI 只用官方源 | 实测两头的网络结论相反——本机 `repo1.maven.org` DNS 超时只能用镜像；runner 打 `maven.aliyun.com/repository/{gradle-plugin,public}` 对 KSP marker 回 **502**，而 Gradle 把 5xx 当致命错、不再试下一个仓库，最后报成「plugin not found」（run 38631763968 的状态码矩阵）。代价：`settings.gradle.kts` 有一处环境分支，且**本机无法复现 CI 的解析路径**（本机 `repo.maven.apache.org` 对 HEAD 回 403），只能靠远端实测把关。用 `GITHUB_ACTIONS` 而不是自建变量名，是为了让「在 CI 上」这件事不需要第二份配置 |
| D23 | 38.7 MB 的 sherpa-onnx AAR 不进仓库，改由 **CI 按钉住的 SHA-256 从官方 release 取回**（配 `actions/cache`），本机缺失时 `app/build.gradle.kts` 在配置阶段抛异常打印 URL/大小/哈希 | 二进制入库会让每次 clone 都背 38 MB 且无法复核来源；静默自动下载又违背「不许偷偷拉几百 MB」（AGENTS.md §5）。让 CI 显式下载 + `sha256sum -c`，构建机与本机用同一份钉住值，取不到就红。代价：CI 依赖 GitHub release 的可用性（实测 runner 直连 13 MB/s，比本机还快），且 `app/build.gradle.kts` 多了一段人类可读的报错 |
| D24 | 模型取回用 `HttpURLConnection`（`HttpFetcher`）而不是再引一个 HTTP 库；**先写 `<file>.part` → 比大小 → 算整份 SHA-256 → 才 `renameTo` 正式文件**，失败删 `.part`；续传只做**文件级**（已合格的文件跳过，半截的重下） | 项目里已有 Media3 与 WorkManager，为「下一个 243 MB 的文件」再引 OkHttp 不划算，`HttpURLConnection` 够用但要自己跟 `Location`（实测 hf-mirror 会重定向到 `/api/resolve-cache/…`，GitHub 更是 302 到对象存储）。「改名前必须整份校验」是为了让磁盘上永远不存在「名字对但内容坏」的模型——那种文件下一步就会被 `SherpaOnnxEngine.open()` 加载。不做 HTTP Range 续传的取舍：清单里每个文件的哈希是**整份**的，半截文件既不能部分校验也不能假装可用，加 Range 只会把「重下」这条唯一的正确路径变复杂；代价是弱网下 243 MB 要重来（`docs/asr-design.md` §7）。进度按**单调时钟**限流 250 ms 推一次，因为 `StateFlow` 是合并式的，回调密度再高 UI 也只能看到最后一个值 |
| D25 | 下载这条路径**必须**留一条打真实 URL 的仪器化用例，哪怕它不在 CI 门禁里、且会因上游不可达变红 | 实测理由：`AndroidManifest` 漏了 `INTERNET` 权限时，**35 条 JVM 单测一条都不红**——假 fetcher 与 host 上的临时 `HttpServer` 都不过 Android 的权限检查；而且设备栈帧里是 `com.android.okhttp.internal.huc.HttpsURLConnectionImpl`，Android 的 `HttpURLConnection` 底层是 Framework 自带的 OkHttp 移植，与 host JDK 那份不是同一套实现（TLS、重定向、HTTP 版本行为都可能不同）。代价：这条用例依赖外网可达，CI 没有模拟器 → 它是**本机一次性凭据**而不是持续回归网；所以 GitHub 连不上时选择**移除**那条用例并如实记录（`silero_vad.onnx` 至今没从设备取回过），而不是留一条红色的假警报 |
| D26 | 采样率转换**自己写**（`domain/asr/SincResampler`：窗口 sinc / Blackman、每侧 16 抽头、按 `j*L/M` 的整数相位查系数表、每相位归一到直流增益 1），而不是线性插值、也不是引第三方重采样库 | 三个选项都实测过：① Media3 1.11.1 **没有**现成实现——`media3-common-1.11.1.aar` 的 `classes.jar` 里类路径含 `resampl` 的条目 **0 条**（`AudioResamplingAudioProcessor` 已不在该产物中），`media3-decoder-1.11.1.aar` 只剩 `Buffer/Decoder/SimpleDecoder` 一族（没有 `MediaDecoder`）；② 线性插值等于**不做低通**，44.1/48 kHz 书解里 8 kHz 以上的成分会整块折进 0–8 kHz，模型吃的是它没见过的噪声，而字幕上只会表现为「偶尔识别得差」，几乎无法归因；③ 引 libsamplerate/SoX 之类违背 AGENTS.md §3「不引未实测的大框架」，而这件事只需要一个有理数比的 FIR。实测（1 秒单频正弦 + 相干 DFT）：1/3.5/5 kHz 平坦（≤ −0.09 dB），7 kHz −2.2 ~ −2.4 dB，会折进输出 4 kHz 的 12 kHz 压到 **−69 ~ −78 dB**，20 kHz −91 dB；代价是 33 抽头在通带末端那 2 dB 跌落（要抹平得翻倍抽头，转录时间换边际收益，不做）与头尾各 ~1 ms 的边缘样本不准（常数 0.25 输入实测首样本 0.170、末样本 0.267）。另两条设计约束来自踩坑：产出判据只认「中心样本还在数据里」（否则同采样率下的恒等性质被末尾几个零填充样本破坏），以及系数表按**约分后的有理数**建（44.1k→16k 只有 160 个相位，不是每样本重算一次） |
| D27 | 分块与绝对时间偏移放在 `domain/asr/AsrChunkPlanner`（纯 Kotlin），`PcmChunkPlan` 带 `startSample/startMs/endMs`，**不在编排层用「累计时长」推时间** | FR-505 要求第 N 段的句时间戳落在绝对位置上。用「上一段末尾 + 本段时长」推 offsets 会把每段几毫秒的取整误差一路累加，180 段（1 小时）就是几百毫秒的漂移；改成一律 `startSample * 1000 / 16000`，单测用 180 段连续音频断言 `endMs == 3_600_000`（零漂移）钉住。>20s 的段**均分**成 `ceil(N/max)` 块（不是切满 20s 留一个尾巴块），避免最后剩个几百毫秒的小块——那既是一整次推理的固定开销，也最容易出空结果 |
| D28 | `PcmDecoder` 用 `MediaCodec` **同步模式 + 贪心喂入**（一次把能塞的输入都塞进去，本轮有产出就用 0 超时非阻塞继续取，两头都不动才等 10 ms），不用异步回调 API，也不逐「一进一出」 | 实测代价与收益：一进一出时解码器经常没活干，每轮都在 `dequeueOutputBuffer` 的超时上干等——148 s 真素材 27,974 ms（RTF 0.189）；改贪心后 24,557 ms（RTF 0.166），**产出字节序列逐样本相同**（`outSamples 2,378,188`、581 块、`peak/rms` 一致），所以这是调度而非算法改动。不选异步回调（`setCallback` + `MediaCodec.Callback`）：回调跑在专用线程上，要自己做「回调线程 ↔ 调用方取消」的同步与缓冲生命周期管理，而转录本来就在 `Dispatchers.IO` 协程里跑，同步模式 + 协程取消位（`PcmChunkSink` 返回 false）已经够用且状态全在一条栈上看得清。兜底：连续 3,000 轮（≈30 s）两头不动就抛 `PcmDecodeException`，不让任务无限挂住 |
| D29 | 逐字时间戳的换算**独立成纯 Kotlin 的 `TokenTimingMapper`**，单位按实测钉死为**秒**（`timestamps` 相对本块起点），`durations` 数组长度是 **0** 所以每字的 `endMs` 取**下一个字的起点**、末字收到块末尾；任何自相矛盾（字数≠时间戳数、NaN/Inf、负数、越块、倒序）抛 `TokenTimingMapper.Mismatch`，**不返回半份结果也不猜缺的是哪一个** | 这是全项目最容易自欺的一环：猜错单位是整条字幕偏移 1000 倍，而它不会崩、只会「看起来有点不对」。所以三条判据都写成了设备断言（`逐字时间落在块内且单调` + 反向对照 `把秒当成毫秒喂进换算器会立刻抛错`），而不是靠日志里看一眼。`endMs` 不猜的代价：相邻字挤在同一毫秒会得到零长字幕，实测存在且合法，UI 靠 `SubtitleSynchronizer` 的 gap 迟滞吸收。数量不等也绝不「按少的对齐」——真出这种结果说明模型与封装版本不匹配，静默对齐就是把未知错误写进字幕表 |
| D30 | `VadSegmenter` 是**流式**的（`push(FloatArray)` → 段列表，`finish()` 收尾），并且把**段内样本一起带回来**（`VadUtterance(startSample, samples)`），而不是只报区间让调用方回头再读一遍音频 | 内存账：1 小时书解 = 3.6×10⁷ 样本 = 144 MB float（还有解码中间态），而实测最长语音段 118,720 样本 ≈ 0.48 MB → 峰值只需 1 个输入块 + 512 样本窗 + 已出段。只报区间的写法要么整章进内存，要么解码两遍（这台设备 148 s 素材单趟解码就 8.9 s，RTF 0.15，两遍直接翻倍）。副作用实测：喂入块长（4096/1024/3000/整串一次）不影响分段结果——同一批样本吐出完全相同的 `(startSample, len)` 列表，这条已做成用例，就是「攒不满一窗的尾巴要留住」的实现契约。另外**不给 `AsrEngine` 套接口**：目前只有一个实现、没有假引擎可注入，代价是推理层只能上设备测（JVM 侧退到 `TokenTimingMapper`/`AsrChunkPlanner`），等真需要第二个引擎时再抽 |
| D31 | `subtitle_segment.job_id` 的外键在 **v3 用整表重建补上**（建 `_new` → 显式列名 `INSERT..SELECT` 搬数据（含主键 `id` 原值）→ DROP → RENAME → 重建三条索引），而不是「长期不建 FK」或「为挂 FK 新建一张关联表」 | 不建 FK 的代价是「字幕指向一个不存在的任务」这种行永远写得出、也没人会发现；用关联表绕开则是把一次 join 变成常态。SQLite 只有整表重建这一条正路，而它真正危险的地方是**搬丢数据**：所以 `Migration_2_3_Test` 不只要 `runMigrationsAndValidate` 与 `3.json` 对账，还要逐句比对字幕文本/时间/id，并在这台迁移后的库上真跑一次「删任务行只把 `job_id` 置空、不删字幕」——SET NULL 写在 DDL 里不等于生效过。另有一条有意的严格：搬数据时外键已开着，指向不存在任务的 `job_id` 会让整条迁移失败而不是被吞掉（v2 的代码写不出这种值，真出现就是库被外部改过，必须让人看见） |
| D32 | 任务状态跃迁写成**带 `WHERE status IN (...)` 守卫的 UPDATE**、返回受影响行数，由 `TranscriptRepositoryImpl` 把 0 行翻译成 `false`；而不是「先 SELECT 判断再 UPDATE」，也不给 `AsrEngine`/任务表加乐观锁版本号 | 转录这件事天然会有两个写者（UI 协程取消 + worker 结算 + WorkManager 重试），先读后写中间的窗口足够把 SUCCESS 改回 RUNNING。守卫式 UPDATE 把判定交给 SQLite 的单语句原子性，重复结算第二次就是 0 行，看得见也改不动；加版本号列则是为同一件事付双倍复杂度。例外是 `commitSubtitles`：那条路径只有成功分支，跃迁失败说明这批字幕不该落库，所以用 `check` 抛出、事务整体回滚——用例 `重复结算第二次整体失败并回滚字幕` 就是断言「第二次写完字幕又被回滚干净」。另外 `transcript_job` 的历史**只追加不覆盖**（重试新建一行），否则「上次为什么失败」就没有东西可查 |
