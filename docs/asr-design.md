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
  **2026-10-15 实际取回记录**（替换上面那句估算，网络已经不是瓶颈了）：`curl -sSL` 直连 GitHub release，**38,691,998 B / 3.0s / 13.0 MB/s**，与确证大小一致；
  本机钉住 `SHA-256 b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`，
  并用 `gh api repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8` 读到该 release 资产的 `digest` 字段
  = `sha256:b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` —— **与本机下载逐字节一致，所以这份有官方校验值**
  （§2.1 早期写作时以为 release 资产没有 digest，那是把 `asr-models` 那个 tag 的 `silero_vad_v5.onnx`（`digest: null`）当成了普遍情况；
  顺带确证：`asr-models` 的 `silero_vad.onnx` `digest` = `sha256:9e2449e1…`、`size` 643,854 B，本机取回逐字节相同）。
  `app/build.gradle.kts` 在文件缺失时抛 `GradleException` 打印 URL/大小/这份哈希；CI 每次按同一份哈希 `sha256sum -c` 后放进 `app/libs/`（`docs/environment.md` §8）。
- Kotlin API 面（`unzip` 出 `classes.jar`，238,706 B，122 个 class，包 `com.k2fsa.sherpa.onnx`）实测确认可用：
  `OfflineRecognizer` / `OfflineRecognizerConfig` / `OfflineRecognizerResult` / `OfflineStream` / `OfflineParaformerModelConfig` / `OfflineModelConfig`、
  `Vad` / `VadModelConfig` / `SileroVadModelConfig` / `WaveData`。类文件带 `$Companion` 与 `*Kt`，**是 Kotlin API 而非 Java 反射壳**，`SherpaOnnxEngine` 可以直接调，不需要 JNI 自写。
- ABI：AAR 含 `arm64-v8a / armeabi-v7a / x86 / x86_64`。本项目 `abiFilters` **只留 arm64-v8a**（真机优先；本机模拟器 `emulator-5554` 也是 arm64-v8a，仪器化测试同样可用），全 ABI 打进包里未压缩约 105 MB，不可接受。
  **2026-10-15 已收窄并实测生效**：`app-debug.apk` 16.1 MB → **40,794,372 B**，`unzip -l` 里 sherpa 只有 `lib/arm64-v8a/libsherpa-onnx-jni.so`（24,169,352 B）一份。

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
| `tokens.txt` | 75,756 B | 官方未给（HF API 的 `sha256` 字段为空）→ **本机钉住 `59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6`** | 同上仓库 |
| `config.yaml` | ≈0.06 MB | 不需要（sherpa-onnx 已把 cmvn/lfr 烧进 ONNX metadata） | 同上 |

- **2026-10-15 实测取回**：`https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/{model.int8.onnx,tokens.txt}`
  → 243,371,218 B / **6.2s / 39.2 MB/s**，`shasum -a 256` 得 `f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945`，**与 HF LFS `oid` 逐字符相同**；
  `tokens.txt` 75,756 B 同时落到 `app/libs/models/sherpa-onnx-paraformer-zh-2023-09-14/`（gitignore 内，不入库）。
- 下载首选 **hf-mirror.com**（本轮实测 39.2 MB/s；A10 那次是 5.86 MB/s，速度同样会漂）；`huggingface.co` 本机超时，只作为备用源写在清单里。
- **2026-10-15 05:10 二次复核（接完 `ModelManager` 之后）**：重新取 `tokens.txt` → 75,756 B、`59aba8873a…cb6e6`，**与清单逐字符相同**，
  且 hf-mirror 这次返回 200 但 `url_effective` 落到了 `/api/resolve-cache/...`（**它自己会重定向**，这就是 `HttpFetcher` 必须处理 `Location` 的实测依据）；
  同一时刻 `github.com:443` 在本机 **连接超时**（两次各 75 s 失败，而 04:20 取 AAR 时还是 13 MB/s），所以 `silero_vad.onnx` 那份没能远端复核，
  只有本机上 04:20 之前那份（643,854 B、`9e2449e1…`，与官方 `digest` 一致）。**清单里 VAD 只有 GitHub 一个地址**，这是已知的单点，
  备选源要等实测到「同一个 sha256」才能进清单——本轮探测的两个 hf-mirror 路径都返回 404，因此没有写入。
- **2026-10-15 05:25 三次复核（设备侧）**：不带 `-L` 直接看 `tokens.txt` 的响应行是 **`HTTP/2 307` + 相对 `Location`**
  （`/api/resolve-cache/models/.../<rev>/tokens.txt?...`）——不是 302，`HttpFetcher` 的判定写的是 `status in 300..399`，覆盖 307；
  同一份 URL 在 emulator-5554 上由 App 自己的代码取回，字节数与 sha256 与本机一致（`ModelManagerNetworkTest`）。
  同一时刻设备侧连 `github.com:443` 也是 **15 s connect 超时**（`SocketTimeoutException`，栈里是 `com.android.okhttp.internal.huc.HttpsURLConnectionImpl`），
  所以「GitHub 单源」这个风险现在有了两条独立证据（宿主机 + 模拟器），而 `objects.githubusercontent.com` 与 `hf-mirror.com` 从设备 ICMP 可达。
- GitHub 也有整包 `.../releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2`（**234.05 MB**），但 release 资产 **digest 字段为 null → 无官方校验值**，且本机只有 45 KB/s，不推荐。
- `OfflineParaformerModelConfig` 只有 `model` 一个字段（A3/源码），应用只需要 `model.int8.onnx` + `tokens.txt` 两个文件；int8 与 fp32 二选一，清单里默认 int8。

### VAD：`silero_vad.onnx`

| 文件 | 大小 | SHA-256 | 来源 |
| --- | --- | --- | --- |
| `silero_vad.onnx` | 643,854 B | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`（官方 `digest`，**已逐字节复核**） | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`；2026-10-15 取回 643,854 B，`shasum -a 256` 与 `gh api` 的 `digest` 一致 |

### 方案 B（备选/对照）：SenseVoice int8

- `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` **163.00 MB**，官方 sha256 `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e`（GitHub release 资产 digest）；`...-int8-2025-09-09.tar.bz2` 165.78 MB。
- 优点：CTC 对齐给真实时间戳（60ms 栅格）、更快更省内存；缺点：纯普通话书解精度可能不及 paraformer-large —— **存疑，需自测**，故列为对照而非默认。
- 用途：如果 §8 的降级路线触发，SenseVoice 是第一替代。

## 4. 组件划分（对应 `architecture.md` 的 `asr/` 包）

```
AsrEngine (interface)                     ← 唯一的推理抽象                代码里还没有
├── SherpaOnnxEngine                      ← 真实实现，持有 OfflineRecognizer + Vad   代码里还没有
└── FakeAsrEngine (test)                  ← 固定文本 + 可预测时间戳，用于端到端流程测试  代码里还没有
ModelManager                              ← 清单/下载/导入/SHA-256 校验/删除/状态 Flow   ✅ DONE（设备真 URL 验证过，见 §7）
PcmDecoder                                ← MediaExtractor + MediaCodec（同步模式）流式解码   TODO（Phase 6 第 3 项前半）
domain/asr/PcmConversion                  ← 16-bit LE PCM 字节 → 单声道 float（均值下混）  ✅ DONE + 11 条 JVM 单测
domain/asr/SincResampler                  ← 源采样率 → 16 kHz 的抗混叠流式重采样            ✅ DONE + 9 条 JVM 单测
VadSegmenter                              ← silero VAD：float 块 → [startSample, endSample]  TODO
domain/asr/AsrChunkPlanner                ← 绝对时间偏移计算 + ≤20s 分块，纯 Kotlin          ✅ DONE + 14 条 JVM 单测
domain/model/AsrModels                    ← SpeechSpan / PcmChunkPlan（不带任何 native 类型）✅ DONE
TranscriptCoordinator                     ← 编排：解码→VAD→逐块识别→偏移合并→切句→入库      TODO
```

**为什么重采样要自己写**（不是偷懒，是实测没有现成的）：`AudioFormats.ASR_SAMPLE_RATE = 16_000`，而书解 MP3 常见是
44.1 / 48 kHz，silero VAD 只吃 16k（§1-A8）。Media3 1.11.1 里翻不到任何重采样实现——实测
`media3-common-1.11.1.aar` 的 `classes.jar` 里含 `resampl` 的类路径**0 条**（`AudioResamplingAudioProcessor` 已不在该产物中），
`media3-decoder-1.11.1.aar` 只有 `Buffer/Decoder/SimpleDecoder` 一族、**没有** `MediaDecoder`。
所以解码只能用框架 `MediaExtractor` + `MediaCodec`，采样率转换只能用 `domain/asr/SincResampler`（窗口 sinc / Blackman，33 抽头，
按 `j*L/M` 的整数相位查系数表，每相位归一到直流增益 1）。这里刻意**不做线性插值**：线性插值等于没有低通，
源里 8 kHz 以上的成分会折叠进 0–8 kHz，给模型喂一层它训练时没见过的噪声——`SincResamplerTest` 用两个单频探针
（1 kHz 通过、12 kHz 衰减 > 40 dB）把这件事钉在 JVM 里。

`AsrEngine` 契约（有意保持窄；**下面是设计稿，代码里还没有这个接口**，2026-10-15 实测 `grep -r "interface AsrEngine"` 无结果）：

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
2. PcmDecoder（TODO）：MediaExtractor 选出 mp3 track → MediaCodec 同步模式解出 16-bit LE PCM（保持源采样率）
3. 转为 FloatArray [-1,1]（domain/asr/PcmConversion，DONE），按 VAD 窗口 512 样本喂 VadModel.acceptWaveform（多声道先取均值转单声道）
   ─ 每次只持有 ≤ 1s 的窗口缓冲，峰值内存受控（NFR-05/07）
   ─ 源采样率 ≠ 16k 时先过 domain/asr/SincResampler（DONE）：窗口 sinc（Blackman，33 抽头）低通 + 整数相位取值，
     **不是线性插值**（线性插值等于没有低通，源里 >8kHz 的成分会折进可听带，给模型喂它没训练过的噪声）。
     为什么必须自己写：实测 media3-common 1.11.1 的 classes.jar 里含 `resampl` 的类 **0 条**、
     media3-decoder 1.11.1 只有 `Buffer/Decoder/SimpleDecoder` 一族（没有 `MediaDecoder`），
     系统侧也没有「解码时顺带改采样率」的可靠开关——见 `docs/architecture.md` D26
4. VAD 产出语音段：minSilence=0.25s、minSpeech=0.15s、maxSpeech=20s（取 sherpa-onnx Python `vad_utils.py` 的默认值，理由与单位见 §3 末）
   段起点是样本下标 → startMs = startSample * 1000 / sampleRate（不做估计）
   ─ 该换算的唯一实现是 `AsrChunkPlanner.msOf`（DONE），识别前还要用它把 >20s 的段均分成 ≤20s 的块
5. 每块：createStream → acceptWaveform(该块 float) → decode → getResult
   → result.timestamps 逐字秒 + 块 startMs = 绝对时间（FR-505）
   ⚠ 时间戳为空的块记录 tokenCountMismatch，按 §8 降级处理
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

三种准备路径，最终都归一到 `ModelVerifier.verify(spec, modelsRoot, verifiedAtMs)`（存在 → 大小 → SHA-256 三步，全过才算就绪）：

1. **在线下载**（默认）→ **已实现（2026-10-15）**：`asr/ModelManager.kt`。
   - 目录：`filesDir/models/<ModelSpec.id>/`（`ModelVerifier.dirOf`）。
   - 流程：`ModelFetcher.fetch` 把字节写进 `<file>.part` → 比大小 → `ModelVerifier.sha256` 流式比对清单值 → 全对才 `renameTo(正式文件)`；
     任何一步失败都删 `.part`，**不留一份「看起来存在」的坏模型**。断点续传只到**文件级**（已合格的文件跳过，半截文件下次从头重下），
     不用 HTTP Range：清单的哈希是整份文件的，拿半截去比对没有意义。
   - 多源：按 `ModelFileSpec.candidateUrls`（首选 + 备用）依次尝试，全失败才抛 `ModelDownloadException`，异常里带上每个地址各自的失败原因。
   - 来源记录：每个取回成功的文件向模型目录里的 `download-provenance.properties` 追加 6 行
     （`<file>.url` / `.bytes` / `.sha256` / `.checksum=official|pinned-by-first-download` / `.model_id` / `.fetched_at_ms`）。
     无官方校验值的文件（当前只有 `tokens.txt`）记的就是 `pinned-by-first-download`，与 §3 表格一致。
   - 进度：`HttpFetcher` 的 64 KB 分块回调按**单调时钟**限流 250 ms 才推一次 `ModelStatus.download`（尾包必推），
     否则一次 243 MB 的下载要往 `StateFlow` 塞 ~3700 个值。
   - 传输层是 `HttpURLConnection`（不引 OkHttp），自己跟 `Location`（相对地址按当前 URL 解析），
     `instanceFollowRedirects=false` + 最多 5 跳，见 §3 里 hf-mirror 实测返回缓存 URL 那条。
     **注意**：设备上的 `HttpURLConnection` 实现是 `com.android.okhttp.internal.huc.*`（Android Framework 自带的 OkHttp 移植），
     与 host JDK 那份不是同一套代码，所以「JVM 单测绿」不构成「设备能下载」的证据——这条路径由 `androidTest/asr/ModelManagerNetworkTest.kt`
     用真实 hf-mirror URL 验证（2026-10-15，4/4 通过，`docs/testing.md` §4）。前提条件是 `AndroidManifest` 里的 `INTERNET` 权限，
     它当初被漏掉时**没有任何一条单测变红**，这是这类代码的固有风险。
2. **本地导入**（完全离线的首次使用）→ **未实现**：设置页选一个模型包目录（SAF），跑同一个 `ModelVerifier`，再把文件移入私有目录。
   校验逻辑已经复用得上（`ModelVerifier.checkFile` 就是给它准备的），缺的是 SAF 侧的搬运与 UI。
3. **预置**：把模型放 APK assets —— **不做**（§2.2 体积结论：仅模型就 232 MiB，远超可接受范围）。

删除模型：`ModelManager.delete(spec)` 只删 `filesDir/models/<id>/` 里清单列出的文件、`.part` 与来源记录，再删目录本身；
字幕与收听历史一律不碰（FR-508）。删除不成功的文件路径会写进 `ModelStatus.error`，不假装删干净了。

## 8. 降级与失败处理

| 触发 | 行为 |
| --- | --- |
| `timestamps` 为空或与 tokens 数量不一致（A6 的硬条件） | 该段标记 `no_token_timing`；整本比例 > 20% 时任务仍 SUCCESS，但字幕以 VAD 段级时间戳为准（段起点 + 段时长均分**仅用于段内**，并在转录任务记录与本文里标明为降级），UI 顶部提示「字幕时间为段级估计」；否则不产出该句 |
| 实际使用的模型不支持时间戳（用户误导入 zh-2024-03-09 等，A4） | `verify()` 阶段就比对清单 modelId 与文件哈希，不匹配直接拒绝并提示正确模型 |
| 解码失败 / 不支持的编码 | job `FAILED(DECODE_FAILED)`，文案指向「该文件可能不是 MP3 或已损坏」；不改 `transcription_state` 以外的数据 |
| 授权被撤销 | `FAILED(PERMISSION_REVOKED)`，任务列表给「重新授权」快捷入口 |
| OOM / native crash 风险 | `numThreads=1`、段 ≤20s、单引擎串行、每 200 段主动 `release()+reload()` 释放累积内存（防泄漏兜底）；OOM → `FAILED(OUT_OF_MEMORY)` 且下次自动用更小段长 |
| GitHub 不可达（A10） | 下载源按 hf-mirror → huggingface → GitHub 顺序重试，全部失败则提示用本地导入路径 |

## 9. Phase 6 的验证计划（可复现）

1. `curl` 取 AAR → 放 `app/libs/`（构建脚本在缺失时打印确切 URL 与大小，不静默失败）。
2. `./gradlew :app:assembleDebug`（确认 arm64 so 打进包、`package-abi` 过滤生效）。
3. 用 `ModelManager` 在设备上下载 paraformer int8 + silero VAD，校验 sha256 与 §3 一致（把实测值与清单值同时记录进 `docs/testing.md`）。
   **部分完成（2026-10-15）**：设备上用真实 URL 取回并核对了 `tokens.txt`（75,756 B，sha 独立复算一致）；
   243 MB 的 `model.int8.onnx` 与 GitHub 单源的 `silero_vad.onnx` 还没在设备上取回过（前者是磁盘与耗时顾虑，后者是 `github.com:443` 本机与设备都连不上）。
4. 准备真实素材：一段 3–10 分钟中文书解 MP3（本机自行生成或用用户提供的样本），跑转录，人工抽查 10 处字幕时间对齐误差（记录 ±ms 分布）。
5. 仪器化测试覆盖「分段偏移合并」「切句」「重转替换」「任务取消/重试」，JVM 单测覆盖纯逻辑。
6. 真机（≥2GB RAM）实测峰值 RSS 与 RTF；未做则明确标注未完成。

## 10. 当前状态（诚实标注）

- **已确证/已落地**：AAR 取回并打进包（§2.1，`assembleDebug` 通过，APK 40.79 MB，只含 arm64 一份 sherpa `.so`）；
  三个模型文件已在**本机**下载并逐一核对 sha256（§3，Paraformer int8 + tokens + silero VAD，`tokens.txt` 于 2026-10-15 二次复核仍一致）；
  模型管理这层已有代码：`asr/ModelCatalog.kt`（清单，数值由 `ModelCatalogTest` 钉住）、`asr/ModelVerifier.kt`（存在/大小/SHA-256 三步核对）、
  `asr/ModelManager.kt` + `asr/ModelFetcher.kt`（下载 → `.part` → 校验 → 改名 → 记来源，含多源回退、文件级跳过、删除、按单调时钟限流的进度），
  已挂进 `AppGraph`（`modelsRoot = filesDir/models`），35 条 JVM 单测覆盖（`docs/testing.md` §4）。
- **尚未实现**：`asr/` 里还没有 `SherpaOnnxEngine`、`VadSegmenter`、`PcmDecoder`、`TranscriptCoordinator`，`work/` 是空的，
  `transcript_job` 表也还没建。**所以「ASR 端到端可用」仍然未完成**——本机那些模型文件目前只是躺在 `app/libs/models/`（gitignore 内），
  App 一次都没有加载过它们，一行真实转录都没产出过。
- **音频数学层（2026-10-15 新增，Phase 6 第 3 项前半）**：`domain/asr/PcmConversion`（16-bit LE → 单声道 float，多声道取均值）、
  `domain/asr/SincResampler`（窗口 sinc / Blackman、每侧 16 抽头、整数相位系数表、每相位归一直流增益；实测 12 kHz 折进输出 4 kHz 的成分压到 −69 dB，
  见 `docs/architecture.md` D26）、`domain/asr/AsrChunkPlanner` + `domain/model/AsrModels`（>20s 均分、绝对毫秒偏移，见 D27）。
  36 条 JVM 单测，`grep` 确认 `domain/` 无 Android/Media3/Room import。**这些类目前没有任何调用方**：
  上游的 `PcmDecoder` 还没写，所以**一个真实 MP3 字节都没进过这些函数**，频响与长度性质都只在合成信号上证过。
- **模型管理这层的设备侧状态（2026-10-15 更新）**：`AndroidManifest` 现在有 `INTERNET` 权限（**之前漏了**，那 35 条单测全绿也发现不了），
  `androidTest/asr/ModelManagerNetworkTest.kt` 用清单里真实的 hf-mirror URL 在 emulator-5554 上下过 `tokens.txt` 并独立复算 sha256（4/4 通过）。
  仍然空白的三块：243 MB 的 int8 模型没从设备取回过；`silero_vad.onnx`（GitHub 单源）本机与设备**都**连不上、一次都没取回过；
  设置页/转录页也还没有入口用它。**「点一下就能下模型」要到 Phase 6 第 6 项才算交付。**
- Phase 5 用测试字幕验证的是七句窗口管线；测试字幕与 ASR 产物在数据上以 `source=MANUAL_TEST` / `ASR` 严格区分，不得互相冒充。
