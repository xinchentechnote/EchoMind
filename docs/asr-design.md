# 本地 ASR 设计（asr-design）

> 本文的**每一条外部事实都标了来源 URL 与「实测确证 / 存疑」状态**（调研于 2026-10-14 在本机网络环境完成）。
> 需求文档 FR-510「不得虚构下载地址或校验值」以本文为执行依据。

## 1. 关键前置结论（决定了整条流水线形状）

| # | 结论 | 状态 | 来源 |
| --- | --- | --- | --- |
| A1 | sherpa-onnx **没有** Maven Central 发布，官方搜索 `g:com.k2-fsa` 命中 0 条；Android 侧唯一官方分发是 GitHub release 的 AAR | 实测确证 | https://search.maven.org/solrsearch/select?q=g:com.k2-fsa |
| A2 | JitPack 只能取到 **JVM** 产物（`com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm`），`v1.13.8/…aar` 实测 404 | 实测确证 | https://github.com/k2-fsa/sherpa-onnx/issues/3981 |
| A3 | Kotlin 结果类叫 **`OfflineRecognizerResult`**（不是 `OfflineRecognitionResult`），字段含 `text / tokens: Array<String> / timestamps: FloatArray / lang / emotion / event / durations`；v1.13.8 **没有** `words` | 实测确证（源码 + `git show v1.13.8`） | `sherpa-onnx/kotlin-api/OfflineRecognizer.kt` |
| A4 | 中文离线 Paraformer 里，**只有 `sherpa-onnx-paraformer-zh-2023-09-14` 被官方明确标注支持时间戳**；`zh-2023-03-28`、`zh-2024-03-09`、`zh-small-2024-03-09`、`trilingual-zh-cantonese-en`、`en-2024-03-09` 官方逐条标注 **不支持**，`zh-int8-2025-10-07`（川渝）文档示例 `timestamps: []` 为空 | 实测确证 | https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html |
| A5 | 维护者明确要求：非流式模型单次输入**不要超过约 20 秒（最好 <30s）**，长音频必须走 VAD 分段（`sherpa-onnx-vad-with-offline-asr` 示例） | 实测确证 | https://github.com/k2-fsa/sherpa-onnx/issues/2051 |
| A6 | Paraformer 的时间戳来自 `us_cif_peak` CIF 峰值，量化栅格 0.02s；`timestamps` 与 `tokens` 数量不一致时**保持空数组** | 实测确证（源码） | `offline-recognizer-paraformer-impl.h:194-197`、`offline-paraformer-greedy-search-decoder.cc:42-67` |
| A7 | Paraformer 只支持 `greedy_search`，不支持 beam search / hotwords；且**不产出标点**（PUNC 子模型不在 sherpa-onnx 识别器内） | 实测确证（源码） | `offline-recognizer-paraformer-impl.h:94-101` |
| A8 | 喂给 `acceptWaveform` 的音频：单声道 float、幅度 **[-1,1]** 归一化；采样率**不必**是 16kHz（内部自动重采样） | 实测确证（源码+文档） | `features.cc:73-81`、`features.h:50-53` |
| A9 | sherpa-onnx **没有**通用 forced-alignment API（只有 Whisper 内置 DTW）；aeneas/whisperX 式对齐在 Android 上不现实 | 存疑→判定为不可行 | 源码检索 |
| A10 | 本机网络：`huggingface.co` 超时；**`hf-mirror.com` 可用且实测 5.86 MB/s**；`github.com` 可达但 release 二进制只有 **45–51 KB/s**；`raw.githubusercontent.com` connection reset | 实测确证 | 本机 curl 探测 |

### 由 A4 + A5 + A7 推出的字幕时间戳方案（需求 4.6 第 5 条的答案）

**采用「Silero VAD 切段 + Paraformer(zh-2023-09-14) 逐字时间戳 + 段起点偏移」**：

- 句的**起止时间**来自：段起点 = `VadSpeechBuf.start / 16000`（样本下标换算，非估计）；句内每个 token 起点 = 段起点 + `timestamps[i]`；句尾 = 下一 token 起点，或段尾（`start + 段长`）。
- 误差量级（依据 A6 的 20ms 栅格 + CIF 对齐特性 + VAD 静音判定）：字级时间戳量化 20ms；CIF 峰值对齐本身偏差约 **50–150ms**；句子边界由 VAD 给出，典型 **±0.2–0.5s**；跨段不累积漂移（每段独立对齐）。
- **不做**按字数均分时间、不做整段等分（需求 4.6 第 4 条禁令）。段内若有多个句子（无标点时无法判句读），按「token 间最大间隔」切，见 §6。
- 该结论若与后续实测冲突（例如 `timestamps` 长度不匹配返回空数组），降级路线见 §8。

## 2. 依赖与体积

### 2.1 AAR 获取

- 版本：**v1.13.8**（published 2026-09-10，288 个 release 资产）。
- 选用 **`sherpa-onnx-static-link-onnxruntime-1.13.8.aar`**：**38,691,998 B**；arm64 只含一个 `libsherpa-onnx-jni.so`（ORT 静态链入），未压缩 **24,169,352 B** / 压缩 8,985,687 B。
  URL：`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`
- 备选：`sherpa-onnx-1.13.8.aar`（50,129,134 B），arm64 四个 so 未压缩 31.93 MB，需自己 exclude `libsherpa-onnx-c-api.so` / `-cxx-api.so`。
- AAR 元数据：`minSdkVersion=21`（与本项目 minSdk 26 无冲突），`minCompileSdk=1`，`minAndroidGradlePluginVersion=1.0.0`，包名 `com.k2fsa.sherpa.onnx`，Kotlin 侧固定 `System.loadLibrary("sherpa-onnx-jni")`。
- 本机下载耗时估算：45–51 KB/s → 38.7 MB 约 **13–15 分钟**（A10）。因此 AAR **一次性手工取回放到 `app/libs/`，并加入 `.gitignore`**（47 MB 二进制不入库），构建脚本用 `implementation(files("libs/…aar"))`；缺失时构建给出可执行的提示（见 §9）。
- ABI：AAR 含 `arm64-v8a / armeabi-v7a / x86 / x86_64`。本项目 `abiFilters` **只留 arm64-v8a**（真机优先；本机模拟器 `emulator-5554` 也是 arm64-v8a，仪器化测试同样可用），全 ABI 打进包里未压缩约 105 MB，不可接受。

### 2.2 体积/内存预算

- APK 增量：静态链接版约 **+9 MB（压缩）/ +24 MB（安装后 arm64）**。
- 模型：int8 paraformer **243,371,218 B（≈232 MiB）** + `tokens.txt` 75,756 B；VAD `silero_vad.onnx` 0.64 MB。
- 内存：**未找到官方数据**（A10 之外的存疑项）。唯一可参考的真实设备数据点是 2 GB RAM / Snapdragon 680 上 SenseVoice int8 + VAD 可用，RTF 0.363（ https://github.com/k2-fsa/sherpa-onnx/issues/3913 ）。因此本项目按「`numThreads=1..2` + 单引擎串行 + 段 ≤20s + 及时 `release()`」设计，并在真机上实测峰值 RSS 后再决定是否放宽（Phase 6 验收项）。

## 3. 模型清单（集中管理，`asr/ModelCatalog.kt`）

清单里只允许出现**已实测确证**的数值。下表即 `ModelCatalog` 的内容来源：

### 方案 A（首选）：`sherpa-onnx-paraformer-zh-2023-09-14`（中英，带 us_cif_peak 的 VAD+PUNC 导出）

| 文件 | 大小 | SHA-256 | 来源 |
| --- | --- | --- | --- |
| `model.int8.onnx` | 243,371,218 B | `f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945` | HF LFS `oid`： https://hf-mirror.com/api/models/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/tree/main |
| `tokens.txt` | 75,756 B | 无官方校验值（同上 API 未给 sha256） | 同上仓库 |
| `config.yaml` | ≈0.06 MB | 不需要（sherpa-onnx 已把 cmvn/lfr 烧进 ONNX metadata） | 同上 |

- 下载首选 **hf-mirror.com**（A10 实测 5.86 MB/s，约 45s）；`huggingface.co` 本机超时，只作为备用源写在清单里。
- GitHub 也有整包 `.../releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2`（**234.05 MB**），但 release 资产 **digest 字段为 null → 无官方校验值**，且本机只有 45 KB/s，不推荐。
- `OfflineParaformerModelConfig` 只有 `model` 一个字段（A3/源码），应用只需要 `model.int8.onnx` + `tokens.txt` 两个文件；int8 与 fp32 二选一，清单里默认 int8。

### VAD：`silero_vad.onnx`

| 文件 | 大小 | SHA-256 | 来源 |
| --- | --- | --- | --- |
| `silero_vad.onnx` | 0.64 MB | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | GitHub release 资产 `digest` 字段（官方发布） |

### 方案 B（备选/对照）：SenseVoice int8

- `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` **163.00 MB**，官方 sha256 `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e`（GitHub release 资产 digest）；`...-int8-2025-09-09.tar.bz2` 165.78 MB。
- 优点：CTC 对齐给真实时间戳（60ms 栅格）、更快更省内存；缺点：纯普通话书解精度可能不及 paraformer-large —— **存疑，需自测**，故列为对照而非默认。
- 用途：如果 §8 的降级路线触发，SenseVoice 是第一替代。

## 4. 组件划分（对应 `architecture.md` 的 `asr/` 包）

```
AsrEngine (interface)                     ← 唯一的推理抽象
├── SherpaOnnxEngine                      ← 真实实现，持有 OfflineRecognizer + Vad
└── FakeAsrEngine (test)                  ← 固定文本 + 可预测时间戳，用于端到端流程测试
ModelManager                              ← 清单/下载/导入/SHA-256 校验/删除/状态 Flow
PcmDecoder                                ← MediaExtractor + MediaCodec（同步模式）流式解码
VadSegmenter                              ← silero VAD：float 块 → [startSample, endSample]
TranscriptCoordinator                     ← 编排：解码→VAD→逐段识别→偏移合并→切句→入库
AsrChunkPlanner (domain, 纯 Kotlin)        ← 绝对时间偏移计算，单测覆盖
```

`AsrEngine` 契约（有意保持窄）：

```kotlin
interface AsrEngine {
    val readiness: EngineReadiness            // READY / MODEL_MISSING / MODEL_CORRUPT / UNSUPPORTED_ABI / OUT_OF_MEMORY
    fun load(): EngineSession                 // 失败抛 AsrException(code)，UI 映射成人话
    interface EngineSession {
        fun segmentsOf(pcm: FloatArray, startOffsetMs: Long, sampleRate: Int): List<RecognizedSegment>
        fun release()
    }
}
data class RecognizedSegment(
    val startMs: Long, val endMs: Long,
    val tokens: List<TokenTiming>,            // 逐字时间戳，绝对毫秒（已加偏移）
    val text: String,
)
data class TokenTiming(val text: String, val startMs: Long, val endMs: Long)
```

**约束**：`load()` 只能在 `asrDispatcher`（单线程）调用；同一时刻全 App 只有一个 `EngineSession`；`TranscriptCoordinator` 负责每段后检查取消位。

## 5. 转录流水线（长音频，不整本进内存）

```
1. 打开 SAF InputStream（只读，不复制文件）
2. MediaExtractor 选出 mp3 track → MediaCodec 解码为 16-bit PCM（保持源采样率）
3. 转为 FloatArray [-1,1]，按 VAD 窗口 512 样本喂 VadModel.acceptWaveform（多声道先取均值转单声道）
   ─ 每次只持有 ≤ 1s 的窗口缓冲，峰值内存受控（NFR-05/07）
4. VAD 产出语音段：minSilenceDuration=0.5s、maxSpeechDuration=20s（A5 的硬约束）
   段起点是样本下标 → startMs = startSample * 1000 / sampleRate（不做估计）
5. 每段：createStream → acceptWaveform(该段 float) → decode → getResult
   → result.timestamps 逐字秒 + 段 startMs = 绝对时间（FR-505）
   ⚠ 时间戳为空的段落记录 tokenCountMismatch，按 §8 降级处理
6. 段结果 → SentenceSplitter（§6）→ List<SubtitleSegment>
7. 全部段完成后单事务：DELETE 旧字幕 + INSERT 新字幕 + job=SUCCESS + book.transcription_state=READY
8. 进度：done_segments/total_segments，≥1s 节流写库（不逐段 fsync）
```

分段策略说明：为什么「先 VAD 再识别」而不是「固定 15s 硬切」——硬切会把词切断、时间戳错位且需要跨段合并 token；VAD 段本身就是句读线索，与 §6 的切句一致。代价：VAD 静音判定本身有 ±0.2–0.5s 误差（§1 已声明）。

## 6. 切句与标点（FR-602 / FR-604 的现实约束）

模型产出的中文文本**不含标点**（A7）。切句规则因此以「时间」而不是「标点」为主，且必须有明确降级：

1. 一个 VAD 段 = 一个候选句。
2. 段内 token 间隔 ≥ `INTRA_SEGMENT_PAUSE_MS`（默认 300ms）→ 断句（这是真实静音，不是人为均分）。
3. 相邻候选句：文本合并后 ≤ `MAX_SENTENCE_CHARS`（默认 60）且间隔 ≤ `SENTENCE_MERGE_GAP_MS`（默认 250ms）→ 合并（消掉 VAD 过度切分）。
4. 超过 `MAX_SENTENCE_CHARS` 的长句：在**最大 token 间隔**处再切；没有可用间隔时按空白/半角空格切；仍无法切的连续中文长串才允许按长度切，并把该句标记为 `low_confidence_timing`（字幕 UI 不特殊标记，但记录在日志与本文的误差说明里）。
5. 收尾：句末追加「。」作为展示符号（明确记录：**这是展示层补的，不是模型判的句读**）；不猜逗号和问号。
6. `SentenceSplitter` 是纯 Kotlin，输入 `List<RecognizedSegment>`，输出 `List<SubtitleDraft>`，含时间清洗（负值、end<start、重叠、零长度）—— FR-603 的单测全部落在这里。

后续可选改进（roadmap，不进 MVP）：接入中文标点恢复模型（ sherpa-onnx 侧无现成 Kotlin API，需自行集成 `punc_ct-transformer` ONNX，成本较高），或换用自带标点的模型。

## 7. 模型准备与校验（FR-510、需求「模型部署策略」）

三种准备路径，都归一到 `ModelManager.verify()`：

1. **在线下载**（默认）：按清单 URL 下到 `filesDir/models/<modelId>/`，边下边算 SHA-256，与清单值比对；无官方校验值的文件记录「首次成功下载后由本机钉住 sha256」，并写入 `models.lock.json`（含来源 URL、下载时间、大小）。清单来源可追溯性见 §3 表格。
2. **本地导入**（完全离线的首次使用）：用户在设置页选一个模型包目录（SAF），`ModelManager` 校验必需文件存在 + 大小匹配 + sha256 与清单一致（或用户显式确认「清单未提供校验值」），再移入私有目录。
3. **预置**：把模型放 APK assets —— **不做**（§2.2 体积结论：仅模型就 232 MiB，远超可接受范围）。

删除模型：只删 `filesDir/models/`，字幕与历史保留；`AsrEngine.readiness` 变 `MODEL_MISSING`，转录入口禁用，播放不受影响（FR-508）。

## 8. 降级与失败处理

| 触发 | 行为 |
| --- | --- |
| `timestamps` 为空或与 tokens 数量不一致（A6 的硬条件） | 该段标记 `no_token_timing`；整本比例 > 20% 时任务仍 SUCCESS，但字幕以 VAD 段级时间戳为准（段起点 + 段时长均分**仅用于段内**，并在 `models.lock.json` 与文档里记录为降级），UI 顶部提示「字幕时间为段级估计」；否则不产出该句 |
| 实际使用的模型不支持时间戳（用户误导入 zh-2024-03-09 等，A4） | `verify()` 阶段就比对清单 modelId 与文件哈希，不匹配直接拒绝并提示正确模型 |
| 解码失败 / 不支持的编码 | job `FAILED(DECODE_FAILED)`，文案指向「该文件可能不是 MP3 或已损坏」；不改 `transcription_state` 以外的数据 |
| 授权被撤销 | `FAILED(PERMISSION_REVOKED)`，任务列表给「重新授权」快捷入口 |
| OOM / native crash 风险 | `numThreads=1`、段 ≤20s、单引擎串行、每 200 段主动 `release()+reload()` 释放累积内存（防泄漏兜底）；OOM → `FAILED(OUT_OF_MEMORY)` 且下次自动用更小段长 |
| GitHub 不可达（A10） | 下载源按 hf-mirror → huggingface → GitHub 顺序重试，全部失败则提示用本地导入路径 |

## 9. Phase 6 的验证计划（可复现）

1. `curl` 取 AAR → 放 `app/libs/`（构建脚本在缺失时打印确切 URL 与大小，不静默失败）。
2. `./gradlew :app:assembleDebug`（确认 arm64 so 打进包、`package-abi` 过滤生效）。
3. 用 `ModelManager` 在设备上下载 paraformer int8 + silero VAD，校验 sha256 与 §3 一致（把实测值与清单值同时记录进 `docs/testing.md`）。
4. 准备真实素材：一段 3–10 分钟中文书解 MP3（本机自行生成或用用户提供的样本），跑转录，人工抽查 10 处字幕时间对齐误差（记录 ±ms 分布）。
5. 仪器化测试覆盖「分段偏移合并」「切句」「重转替换」「任务取消/重试」，JVM 单测覆盖纯逻辑。
6. 真机（≥2GB RAM）实测峰值 RSS 与 RTF；未做则明确标注未完成。

## 10. 当前状态（诚实标注）

- `docs` 与代码里的 `SherpaOnnxEngine` **在 Phase 6 之前不存在**；Phase 5 用测试字幕验证七句窗口，Phase 6 才引入 AAR 与真实推理。
- 截至本文写作时刻，本机**尚未下载 AAR 与模型**，因此「ASR 端到端可用」**未完成**，不得被任何测试或 UI 冒充。
