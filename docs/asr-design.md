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
ModelManager                              ← 清单/下载/导入/SHA-256 校验/删除/状态 Flow   ✅ DONE（设备真 URL 验证过，见 §7）
PcmDecoder                                ← MediaExtractor + MediaCodec（同步模式）流式解码   ✅ DONE + 4 条仪器化（真 MP3 已在设备解出）
domain/asr/PcmConversion                  ← 16-bit LE PCM 字节 → 单声道 float（均值下混）  ✅ DONE + 11 条 JVM 单测
domain/asr/SincResampler                  ← 源采样率 → 16 kHz 的抗混叠流式重采样            ✅ DONE + 12 条 JVM 单测
asr/VadSegmenter                          ← silero VAD：float 块 → VadUtterance（全局样本起点 + 该段音频） ✅ DONE + 6 条仪器化（真模型真音频）
domain/asr/AsrChunkPlanner                ← 绝对时间偏移计算 + ≤20s 分块，纯 Kotlin          ✅ DONE + 14 条 JVM 单测
domain/asr/TokenTimingMapper              ← 模型的逐字**秒** → 绝对毫秒；不符就抛 Mismatch    ✅ DONE + 14 条 JVM 单测
domain/model/AsrModels                    ← SpeechSpan / PcmChunkPlan / TokenTiming / ChunkRecognition（不带任何 native 类型）✅ DONE
asr/SherpaOnnxEngine                      ← OfflineRecognizer：open 前按清单核对、单块 ≤20s、release 成对   ✅ DONE + 9 条仪器化（**真模型已在设备产出逐字时间**）
domain/asr/SentenceSplitter               ← 逐字时间 → 候选句（§6 六条规则），纯 Kotlin                     ✅ DONE + 25 条 JVM 单测
domain/asr/TranscriptQuality              ← 「丢弃块占比是否还能算一份字幕」的质量闸门，纯 Kotlin            ✅ DONE + 9 条 JVM 单测
asr/TranscriptCoordinator                 ← 编排：解码→VAD→逐块识别→偏移合并→切句→质量闸门→单事务入库        ✅ DONE + 9 条仪器化（真模型跑完整首歌，生产代码第一次把 35 句写进字幕表；用例落的是 in-memory Room，**用户那份库现在也有 35 行 ASR 字幕了**——Phase 6d 从界面点出来的）
work/TranscriptWorker                     ← CoroutineWorker（FGS dataSync）+ 通知进度，把协调器搬到后台       ⚠ `doWork` 主体**仍无用例**（9 条仪器化直接调协调器）；有设备证据：前台通知可见、中途取消真的停下、守卫日志「跳过：任务 … 的状态是 Failed」
work/TranscriptWorkScheduler              ← 入队 / 唯一性 / 取消（先改库再 cancelWorkById）                   ⚠ `APPEND` 排队**无用例**，但**已有生产调用方**（转录页 ViewModel）；界面上看得见「转录中 + 排队中」同屏
ui/transcript/                            ← 转录页：任务列表 + 确认卡片 + 取消/重试 + 失败降级文案            ✅ DONE + 13 条 JVM 用例（`TranscriptDisplayTest`）+ 设备实测（§10）
```

⚠ **`work/` 这两个类的主体逻辑仍然没有自动化用例**，不是「测得少」。`TranscriptCoordinatorTest` 造的是 `TranscriptCoordinator` 本身，
`setForeground` / `ForegroundInfo` / `enqueueUniqueWork(全局唯一名, ExistingWorkPolicy.APPEND)` / `attachWorkRequest` 的写不回撤销，全都在 worker 与调度器里，
一条都没被断言过。`work/` 目前唯一有的 2 条仪器化用例（`TranscriptForegroundServiceTypeTest`）只查**合并后清单**里前台服务的类型与权限——
那是「点开始转录直接崩」那条 bug 的回归闸门（D37），不是行为测试。
Phase 6d 之后这些行为有了**设备证据**（人工从界面点出来：前台通知可见、中途取消停下、重启后守卫拒绝复活、排队不并发），
但那是观察，不是用例。

**为什么没有 `AsrEngine` 接口**：这一段原本写的是设计稿（`EngineReadiness` / `RecognizedSegment` / `FakeAsrEngine`），
用意是「模型还没到手时也能推进」。2026-10-15 真模型已经在 emulator-5554 上跑出结果，那层垫片就失去了存在理由：
一个实现配一个接口，只会把 native 的形状反向定义进 `domain`。落地的形态是 `SherpaOnnxEngine.open(modelsRoot)`——
能不能起由 `ModelVerifier`（也就是清单里那份 sha256）决定，缺文件抛 `AsrModelNotReadyException` 并把「缺哪个文件、在哪个目录」
写进消息，UI 直接拿来显示；`FakeAsrEngine` 同样不再需要，端到端测试直接用真模型跑（`SherpaOnnxEngineTest`）。

`VadSegmenter` / `SherpaOnnxEngine` 的实际契约（都在 2026-10-15 设备实测过）：

- **不许在主线程构造**：`init`/`open` 里 `check(Looper.myLooper() != Looper.getMainLooper())`。装载识别器实测阻塞 **4,906~4,914 ms**，
  放主线程就是 ANR；两条「主线程构造要直接拒绝」的仪器化测试专门在 `runOnMainSync` 里试。
- **`release()` 成对且幂等**：第二次调用不再碰 native；release 之后再 `push`/`recognize` 立刻 `IllegalStateException`，不会 use-after-free。
- **单块 ≤20 s**：`recognize` 自己再兜一道（`AsrInputException`），`samples.size != chunk.sampleCount` 同样拒绝——截断或错位都会让时间戳说谎。
- **VAD 把该段音频一起交出来**（`VadUtterance.samples`）：一小时书解的 16k float 是 230 MB，留在内存里必然 OOM；
  调用方只需留住当前这一段（≤20 s ≈ 1.28 MB）。

**为什么重采样要自己写**（不是偷懒，是实测没有现成的）：`AudioFormats.ASR_SAMPLE_RATE = 16_000`，而书解 MP3 常见是
44.1 / 48 kHz，silero VAD 只吃 16k（§1-A8）。Media3 1.11.1 里翻不到任何重采样实现——实测
`media3-common-1.11.1.aar` 的 `classes.jar` 里含 `resampl` 的类路径**0 条**（`AudioResamplingAudioProcessor` 已不在该产物中），
`media3-decoder-1.11.1.aar` 只有 `Buffer/Decoder/SimpleDecoder` 一族、**没有** `MediaDecoder`。
所以解码只能用框架 `MediaExtractor` + `MediaCodec`，采样率转换只能用 `domain/asr/SincResampler`（窗口 sinc / Blackman，33 抽头，
按 `j*L/M` 的整数相位查系数表，每相位归一到直流增益 1）。这里刻意**不做线性插值**：线性插值等于没有低通，
源里 8 kHz 以上的成分会折叠进 0–8 kHz，给模型喂一层它训练时没见过的噪声——`SincResamplerTest` 用两个单频探针
把这件事钉在 JVM 里（1 kHz 通过幅度 1.00、会折进输出 4 kHz 的 12 kHz 实测衰减 **−69.3 dB**，断言卡在 −60 dB）。

## 5. 转录流水线（长音频，不整本进内存）

```
1. 打开 SAF InputStream（只读，不复制文件）
2. PcmDecoder（DONE）：MediaExtractor 选出音频 track → MediaCodec 同步模式解出 16-bit LE PCM（保持源采样率）
   ─ 设备实测（emulator-5554，真 MP3 素材 148s）：解码器上报 44100 Hz / 2 声道，单个输出缓冲 ≤4,608 B，
     解出 2,378,188 个 16k 样本与容器时长对账偏差 0.22%，RTF ≈0.17
   ─ 取消由 `PcmChunkSink.onChunk` 返回 false 表达；`BlockAccumulator` 每次只攒 `blockSize`（默认 4,096 = 256ms）样本
   ─ 贪心喂入 + 有进展就非阻塞取输出：一进一出的写法每轮都在 10ms 超时上干等（实测 27,974 → 24,557 ms，产出逐字节相同）
3. 转为 FloatArray [-1,1]（domain/asr/PcmConversion，DONE），按 VAD 窗口 512 样本喂 VadModel.acceptWaveform（多声道先取均值转单声道）
   ─ 每次只持有 ≤ 1s 的窗口缓冲，峰值内存受控（NFR-05/07）
   ─ `asr/VadSegmenter`（DONE）把这一步封成 `push(FloatArray) / finish()`：**允许任意长度的块**，内部攒满 512 才喂模型，
     所以 `PcmDecoder` 的 4,096 块直接进即可。设备实测：**同一份 20.224 s 音频按 4096 / 1024 / 3000 / 整块四种切法喂，
     出的段完全一致**（`(223904,31168)`、`(259744,63840)`），证明窗缓冲没有错位；纯 VAD 耗时 1,021 / 1,211 ms（两轮）/ 60,160 ms 音频，**RTF 0.017~0.020**
   ─ 源采样率 ≠ 16k 时先过 domain/asr/SincResampler（DONE）：窗口 sinc（Blackman，33 抽头）低通 + 整数相位取值，
     **不是线性插值**（线性插值等于没有低通，源里 >8kHz 的成分会折进可听带，给模型喂它没训练过的噪声）。
     为什么必须自己写：实测 media3-common 1.11.1 的 classes.jar 里含 `resampl` 的类 **0 条**、
     media3-decoder 1.11.1 只有 `Buffer/Decoder/SimpleDecoder` 一族（没有 `MediaDecoder`），
     系统侧也没有「解码时顺带改采样率」的可靠开关——见 `docs/architecture.md` D26
4. VAD 产出语音段：minSilence=0.25s、minSpeech=0.15s、maxSpeech=20s（取 sherpa-onnx Python `vad_utils.py` 的默认值，理由与单位见 §3 末）
   段起点是样本下标 → startMs = startSample * 1000 / sampleRate（不做估计）
   ─ **单位已在设备钉死（2026-10-15）**：`SpeechSegment.start` 是 **16 kHz 下的样本下标**，落在 1 ms（=16 样本）网格上。
     判据是数字而不是猜想：60 s 素材 = 962,560 样本 = 60,160 ms，实际 9 段的 start 是 `223904 … 944288` 这种六位数量级，
     当毫秒解释比整篇长 15 倍；`start % 16 == 0` 全部成立，`start % 512 == 0` **不成立**（起点被对齐到毫秒网格，不是窗边界）
   ─ `maxSpeechDuration=20s` 的强制切段实测生效：60 s 素材出的 9 段最长 118,720 样本（7.4 s），一段都没有超 20 s。
     `AsrChunkPlanner` 因此是第二道保险，不是摆设
   ─ 一个例外要记住：`finish()` 里 `flush()` 出来的**最后一截不受 `minSpeechDuration` 约束**（实测段长 1,142 ms），
     所以短于 0.15 s 的尾巴是可能出现的，别把它当 bug
   ─ 该换算的唯一实现是 `AsrChunkPlanner.msOf`（DONE），识别前还要用它把 >20s 的段均分成 ≤20s 的块
5. 每块：createStream → acceptWaveform(该块 float) → decode → getResult
   → result.timestamps 逐字秒 + 块 startMs = 绝对时间（FR-505）
   ─ **单位已在设备钉死（2026-10-15）**：`timestamps` 是**秒**、相对这一块。19.2 s 的块给出 13 个 token，首个换算出 **13,580 ms**（模型给的原值 ≈13.58 s），
     若把它当毫秒解释就是「第 13,580 毫秒 → 换算是 13,580,000 ms」，整条轴偏 1000 倍、立刻出块。`durations` 长度实测为 **0** → Paraformer 不给每字时长，
     所以 `TokenTiming.endMs` 取**下一个 token 的 `startMs`**（末字收到块 `endMs`）：起点是模型原值，不是均分伪造
   ─ 换算与不合格判定（数量不等 / 负值 / NaN / 越界 / 倒序）全在 `domain/asr/TokenTimingMapper`（DONE，14 条 JVM 单测），
     它**不裁剪不补齐不夹回块内**，一律抛 `Mismatch`；设备侧另有一条反向对照测试：把已换算的毫秒再当秒喂进去 → 立刻 `Mismatch`
   ─ 实测 RTF **0.131 / 0.083 / 0.133**（三轮；58 个 token / 6 块 / 29,864 ms 语音，`decodeMs=3,922` → `2,467` → `3,973`；单块另测 2,749 / 2,564 / 2,202 / 2,362 ms = 0.143 / 0.134 / 0.115 / 0.123。同一素材差 1.6 倍 → 模拟器 RTF 只当量级），
     `OfflineRecognizer` 装载 **4,906 / 4,914 / 3,398 / 3,060 ms**（后台线程；同一天四次，冷/热差别没测过，只当「秒级」看）
   ─ **切块边界会影响文本**（如实记在这里）：同一段歌词，整块 20 s 给「那片笑声让我想起我的那些年」，
     按 VAD 切成 13,994~15,942 / 16,234~20,166 ms 两块则给「偏笑声让」+「想起我的那些话」。
     逐字时间依然成立（各自在块内、跨块单调），但**块边界不等于句子边界** → 拼句是 §6/第 5 项 `SentenceSplitter` 的活
   ─ **时间戳不合格的块：丢弃，不补时间**（2026-10-15 定稿，`docs/architecture.md` §10 D34）。`TranscriptCoordinator` 捕获 `Mismatch` 后
     只记一笔 `rejectedChunks`，该块的一个字都不进字幕。全本丢弃比例 > `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT`（20%）
     或去重后句子数为 0 → 任务 `FAILED(OUTPUT_UNRELIABLE)`，**不写 SUCCESS、不写字幕**。
     148 s 整首歌实测 **24 块 / 丢弃 0 块**，所以这条闸门到今天仍只有仪器化用例（`TranscriptCoordinatorTest`）走过，没有真素材触发过
6. 段结果（`List<RecognizedSegment>`，一个 VAD 段一组逐字时间）→ `SentenceSplitter`（§6，DONE）→ `List<SubtitleDraft>`
   ─ 2026-10-15 设备实测：60 s 素材前 6 段 / 58 字 → **9 句**，平均每句 6.4 字，硬切 0 句；
     字幕内容与《那些花儿》歌词对得上（「在我生命每一个角落。」/「静静为我开着。」/「他们都老了吧。」），
     逐字时间与模型输出逐字相等（反面检查：摘掉两边标点后的字符序列必须完全相同）
   ─ **整首歌（148 s）实测**：24 段 → 24 块 → **35 句**，**硬切 0 句**（`boundaryGuess` 全 false）。
     维度不同的第二份素材仍是歌唱，所以 §6 第 2、3 条的两个阈值**还是没被朗读型语音标定过**
   ─ ⚠ **`boundaryGuess` 不落库**（2026-10-15 决定，D35）：`SubtitleDraft` 有这一列，`subtitle_segment` 表没有，落库即丢，
     只在收口日志里给数量。理由：加一列要走 v3→v4 迁移，而 MVP 里没有任何 UI 行为依赖它——为「以后可能标注」预付迁移费用不值得。
     代价如实记在这里：「60 字硬切出来的句子」在库里和证据充分的句子长得一样，读库的人分不出来
7. 全部段完成后单事务：DELETE 旧字幕 + INSERT 新字幕 + job=SUCCESS + book.transcription_state=READY
   ─ 2026-10-15 第一次真的走通：`TranscriptCoordinatorTest.真模型跑完整首歌后字幕第一次进了数据库`
     （⚠ 「进了数据库」= **用例进程里的 in-memory Room**，跑的是生产 `TranscriptRepositoryImpl` + 生产 DAO/entity，
     但不是用户机上那份 `echomind.db`——那里面到现在还是 0 句 ASR 字幕，见 §10）
     断言 35 句全部落库、`segment_index` 连续从 0 起、时间升序、`source=ASR`、`job_id` 指向这一行、
     末句 `end_ms ≤ 容器时长 148,303 + 1,000 ms`、job SUCCESS 且 `progress == 1.0`、书 `READY`
   ─ **写入方负责排序**（FR-601）：协调器在提交前 `sortedBy { startMs }` 再 `mapIndexed` 编号。
     仓储层（`commitSubtitles`）信任调用方给的是时间升序，它不排——所以这根轴只在协调器这一处钉住
8. 进度：done_segments/total_segments，≥1s 节流写库（不逐段 fsync）
   ─ **边跑边数不出 total**：VAD 是流式的，读到最后一段之前没人知道总段数，所以 `TranscriptRepository.updateProgress` 收到
     `totalSegments = 0` 表示「分母未知」，字段原样写 0。UI 因此**不许画百分比**，只能显示「已识别 N 段」
     （设备用例 `真模型跑完整首歌后字幕第一次进了数据库` 断言了所有上报的 total 恒为 0、done 单调不减）
   ─ 收口时 `done = total = 实际段数`、`progress = 1.0`
```

分段策略说明：为什么「先 VAD 再识别」而不是「固定 15s 硬切」——硬切会把词切断、时间戳错位且需要跨段合并 token；VAD 段本身就是句读线索，与 §6 的切句一致。代价：VAD 静音判定本身有 ±0.2–0.5s 误差（§1 已声明）。

### 5.1 编排层的生命周期与守护（`asr/TranscriptCoordinator.kt` + `work/`，2026-10-15 落地）

编排不是「把六步串起来」这么轻，因为它的上游是 WorkManager——**同一个 job 可能被第二个 worker 拿到**（进程被杀后重启、
`recoverInterrupted()` 已把该行标成 `FAILED(INTERRUPTED)` 之后 work 又跑了一轮）。所以顺序与守卫都是契约的一部分：

1. `markRunning(jobId, 0)` 先行。它的跃迁语句是 `WHERE status = 'QUEUED' AND NOT EXISTS (… status = 'RUNNING' AND id <> :id)`，
   **抢不到行就立刻 `Skipped` 收手**——一条守卫同时挡住三种情况：第二个 worker 抢跑、进程被杀后复活的 worker 覆盖中断结论
   （`recoverInterrupted()` 已把那行标成 `FAILED(INTERRUPTED)`）、以及**另一本书占了模型句柄时的并发转录**（D36）。
   第三档必须写在 SQL 里：「先 SELECT 看有没有 RUNNING 再 UPDATE」在查与改之间就是并发窗口，两个 worker 都能读到「没有」，
   于是各自开一份 243 MB 的引擎。用例 `别的任务正在跑时第二本书的任务抢不到运行中的行` / `别的书正在跑时第二个协调器收手且不写字幕`。
2. 三道守护在**装载引擎之前**返回，避免为注定失败的活儿付 3~5 秒 native 装载 + 一份内存：
   文件状态（`isFilePresent=false` → `DECODE_FAILED`）、**SAF 授权**（→ `PERMISSION_REVOKED`）、模型清单核对（→ `MODEL_MISSING`）。
3. ⚠ **授权查的是所属文件夹的树 uri，不是书的文档 uri**。可持久化授权只发在树上（`imported_folder.tree_uri`），
   `audio_book.uri` 是 `DocumentFile.fromTreeUri().listFiles()` 给出的文档 uri，两者不相等；
   用文档 uri 调 `hasReadGrant` 必然 false，等于**每一本 SAF 书都被判成「授权已失效」**。
   `TranscriptionTarget.treeUri` + `ImportedFolderDao.byId` 就是为了把树的地址送到协调器手里（用例 `授权查的是所属文件夹的树而不是文档uri`）。
4. 解码→VAD→分块→识别→切句是**一条流**：`PcmChunkSink.onChunk` 返回 false 就是取消信号（同时断开解码器），
   每次 `push` 前检查取消位。取消**只停手，不写终态**——谁把 `isCancelled` 变成 true 的（`TranscriptRepository.cancel`）谁负责写 `CANCELLED`，
   协调器写第二份终态就是把状态机的归属权抢过来（用例 `取消位为真时立刻停手且不写终态`）。
5. 引擎在 `try/finally` 里 `release()`，主线程构造由 `SherpaOnnxEngine.open` 自己的 `check` 拒绝（§4）。
6. WorkManager 侧只做两件事：`TranscriptWorker`（`doWork` → `setForeground(FGS dataSync)` → `coordinator.run(jobId, isCancelled = { isStopped })`，
   把 `TranscriptOutcome` 翻成 `Result`；进度回调里同时写库和刷通知）与 `TranscriptWorkScheduler`
   （`enqueueUniqueWork(UNIQUE_NAME, APPEND)`，唯一名**全局一个**（`transcript-series`）而不是每本书一个——按 WorkManager 的官方语义，
   同一唯一名下的工作不会并发运行（**这是引用文档，不是本机实测**）。把 `work_request_id` 写回活动任务，写不回就立刻 `cancelWorkById` 撤销；
   取消是**先改库再 `cancelWorkById`**，见 `database-design.md` §6）。
   D36 的串行化分两处落：这里的唯一名（**没有用例**，但 Phase 6d 之后界面上看得见：两本书先后入队时同屏「转录中 + 排队中」）与 §5.1 第 1 条的 SQL 单行闸门（**有设备用例，并对「去掉闸门」的构建证过伪**）；
   要说「一个进程只一个引擎句柄」被验过，验的是后者。
   ⚠ **这两个类的主体逻辑仍无用例**（§4 末的说明）：前台通知、`APPEND` 的排队、`work_request_id` 的写回与撤销，都只有代码 + 人工点出来的设备证据。
   ✅ **入口已经有了**（Phase 6d，任务 #23）：转录页 ViewModel 是 `schedule()` 的生产调用方，书架行尾 ⋮ →「转录这本书」→ 转录页确认卡片 →「开始转录」这条用户路径在 emulator-5554 上跑通，
   35 行 `source=ASR` 字幕进了用户库（`docs/testing.md` §4 Phase 6d）。
   另外 `dataSync` 类型在 Android 14+ 有 **6 小时**累计上限（`TranscriptWorker` 类注释里记着），长书面前没有实测依据。

## 6. 切句与标点（FR-602 / FR-604 的现实约束）

模型产出的中文文本**不含标点**（A7）。切句规则因此以「时间」而不是「标点」为主，且必须有明确降级。
下面六条是 `domain/asr/SentenceSplitter` 的实现口径（2026-10-15 定稿，第 2、4 条被设备实测改过两次，改动理由记在每条后面）：

1. 一个 VAD 段 = 一组候选字，段边界是最主要的断句依据。
2. 段内断句：相邻两字**起点差**同时满足「≥ `ASR_INTRA_SEGMENT_PAUSE_MS`（500ms）」且「≥ 本段起点差**中位数** × `ASR_PAUSE_MEDIAN_RATIO`（2）」→ 断句。
   ─ 为什么不是原稿的「间隔 ≥ 300ms 就断」：**起点差里混着前一个字自己的发音长度**（Paraformer 的 `durations` 实测长度 0，减不掉）。
     唱歌时每个字本就占 400~600ms，绝对门槛等于逐字打字 —— 同一份素材第一次跑出来是 **58 字 → 53 句**，平均每句 1.6 字，
     而「时长为正 / 不重叠 / 逐字与模型输出相等」三条断言当时**全绿**（`SherpaOnnxEngineTest.真模型的逐字时间喂进切句器得到能读的句子`，
     那条「平均每句 ≥3 字」的断言就是补上的，把门槛改回 300/倍数 1 会立刻红）。改成相对判据后同一素材 **58 字 → 9 句**，平均每句 6.4 字。
   ─ 基准取中位数不取平均值：一段里只有一个大间隔时平均值会被它自己抬上去，反而断不开。
   ─ 两个条件各管一头：**倍数**管「慢不是停顿」（550ms 均匀字距照样越过 500ms 下限，靠倍数才不断）；
     **绝对下限**管另一头（语速极快的段中位数只有 100ms，×2 = 200ms 的跳跃不值得换一行）。
3. 相邻候选句：文本合并后 ≤ `ASR_MAX_SENTENCE_CHARS`（60 字）且间隔 ≤ `ASR_SENTENCE_MERGE_GAP_MS`（700ms）→ 合并（消掉 VAD 过度切分）。
   ─ 只有**本段最后一句**允许跨段合并；段内因停顿断开的两句不再合回去，否则第 2 条白做。
   ─ 原稿写的是 250ms，形同虚设：`VAD_MIN_SILENCE_SEC` 就是 0.25s，VAD 能断成两段说明中间静音必然 ≥250ms。
     改成 700ms（约 250 的三倍，取「句内换气」量级），**尚未用真人朗读标定**。
4. 超过 60 字的长句按「弱标点 → 空白 → 字数上限处硬切」三档回退（FR-602 的「超出按标点或空格回退切分」），
   只有落到最后一档才把该句标 `SubtitleDraft.boundaryGuess = true`（写日志，**不落库**：`subtitle_segment` 没有这一列）。
   ─ 原稿在这里还有一档「在最大 token 间隔处再切」，已删：能走到这一层的句子说明段内（第 2 条，判据完全相同）没有可断的停顿，
     而跨段合并来的句子字数必然 ≤ 上限、进不了超长分支——「本集合里最大」不再携带任何信息，留着只会让硬切冒充证据。
5. 收尾：句末追加「。」作为展示符号（明确记录：**这是展示层补的，不是模型判的句读**）；不猜逗号和问号。
   中文 token 直接连排，两侧都是字母/数字时补一个空格（模型给英文分词时不留空格）。
6. `SentenceSplitter` 是纯 Kotlin，输入 `List<RecognizedSegment>`，输出 `List<SubtitleDraft>`；
   进门先做时间清洗（乱序重排、上一字压到下一字头上时以起点为准夹住、零长句并进相邻句或如实丢掉），FR-603 的单测全部落在这里。
   ─ 丢弃是允许的：给不出正时长又并不进邻居的句子（比如整篇只剩一个孤立零长字）不进字幕，宁缺不造。

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

**这一节的判定与写入方都接进了 `asr/TranscriptCoordinator`（2026-10-15，Phase 6 第 4b 项）**：错误码不再只是仓储里的字段——
`DECODE_FAILED` / `PERMISSION_REVOKED` / `MODEL_MISSING` / `OUTPUT_UNRELIABLE` / `OUT_OF_MEMORY` 都有生产写入方，
`TranscriptCoordinatorTest` 9 条用例逐条走过（`文档里不许出现代码没有的能力`，反过来说也一样：写入了但没验过的路径要标出来）。
**UI 那一半在 Phase 6d 补上了**：`ui/transcript/TranscriptDisplay.failureGuide` 把 8 个 `TranscriptFailure` 逐个翻成「发生了什么 + 你能做什么」，
卡片同时把 `error_message` 原文照登成「原始信息：…」（不加工、不美化），文案里出现的阈值一律从 `AudioFormats` 常量渲染（13 条 JVM 用例钉住，
含 `每种失败都给得出一个动作`）。**仍缺**：`OUT_OF_MEMORY` 分支既无用例也无设备证据；`MODEL_MISSING` 文案里的「请先在模型页准备好」指向**还不存在的页面**（任务 #24）。

| 触发 | 行为 |
| --- | --- |
| `timestamps` 为空或与 tokens 数量不一致（A6 的硬条件） | `TokenTimingMapper` 抛 `Mismatch`，协调器**抓住它、只把这一块丢掉**——不裁剪、不补齐、不均分（AGENTS.md §5 禁止「段起点 + 段时长按字数均分」，本节原稿写过那种做法，已删）。丢弃数记进 `rejectedChunks`，由 `domain/asr/TranscriptQuality` 判整本还能不能算一份字幕：丢弃占比 > `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT`（20%）→ `FAILED(OUTPUT_UNRELIABLE)`，**不提交任何字幕**。取舍理由与反例见 `docs/architecture.md` §10 D34：宁可这本书这次没有字幕，也不给它一份时间造假的字幕。148 s 素材实测丢弃 0/24 块，所以闸门本身只有 `TranscriptQualityTest`（9 条）和 `没有语音的素材标成结果不可用而不是成功` 这条设备用例证过 |
| 一段音频根本没有语音（或识别不出任何带时间的字） | `drafts.isEmpty()` → `FAILED(OUTPUT_UNRELIABLE)`，消息里如实写「如果这段音频本来就没有语音，这是正常结果」。**不给 SUCCESS、不给空字幕**——空字幕会让书在书架上显示「已有字幕」，那是假话（设备用例 `没有语音的素材标成结果不可用而不是成功`，用的正是那批静音占位《三体》MP3） |
| 实际使用的模型不支持时间戳（用户误导入 zh-2024-03-09 等，A4） | `verify()` 阶段就比对清单 modelId 与文件哈希，不匹配直接拒绝并提示正确模型（**已实现**，`ModelVerifier` + `SherpaOnnxEngine.open` 装载前复核；协调器把装载期的 `AsrModelNotReadyException` 落成 `MODEL_MISSING`） |
| 解码失败 / 不支持的编码 / 文件已不在原位置 | `FAILED(DECODE_FAILED)`，消息带书名。**两条路径都有写入方**：进引擎之前就查 `fileStatus`（`文件已不在原位置时报解码失败且不启动推理`，这条用例断言它连引擎都不装），以及 `PcmDecodeException`（解码中途挂） |
| 授权被撤销 | `FAILED(PERMISSION_REVOKED)`，消息带上那个树 uri。「重新授权」快捷入口**仍然没有**（转录页只显示文案，设置页的按文件夹入口属 Phase 7）。⚠ 判定本身此前是错的：拿书的**文档** uri 去查授权必然 false，会把每本 SAF 书都标成「授权已失效」——现在查 `imported_folder.tree_uri`（§5.1 第 3 条） |
| OOM / native crash 风险 | **实测配置**：`numThreads=2`（`AudioFormats.ASR_NUM_THREADS`，不是原稿写的 1）、单段 ≤20s、单引擎句柄串行、装载只在后台线程；协调器 `catch (OutOfMemoryError)` → `FAILED(OUT_OF_MEMORY)` 并带回收口摘要。**未实现**：原稿的「每 200 段主动 `release()+reload()`」一行都没做——目前没有可参照的内存实测，先不做假兜底。⚠ `OUT_OF_MEMORY` 这条分支**没有用例**（要真把堆撑爆才走得到），只有代码 |
| Worker 层面意外崩（Room 挂了 / native 抛 RuntimeException） | `TranscriptWorker` 兜一条 `FAILED(UNKNOWN)`，否则那行会永远显示 RUNNING。**⚠ 仍无用例**：Phase 6d 之后转录能从界面入队了，但这条兜底路径要造出「协调器之外抛异常」才走得到，没造。设备上有证据的是**相邻那条**守卫：重启后复活的 worker 撞在已结算的行上，日志打出「跳过：任务 … 的状态是 Failed，不该再跑推理」 |
| GitHub 不可达（A10） | **部分实现**：`ModelFetcher`/`HttpFetcher` 按 `candidateUrls` 顺序（首选 → 备用）逐个试，全失败才抛 `ModelDownloadException` 并带上每个地址各自的失败原因（多源回退的代码是有的，`ModelManagerTest` 覆盖了）；但**本地导入路径仍未实现**，所以「全部失败则提示用本地导入」这半句在 UI 上兑现不了（任务 #24） |

## 9. Phase 6 的验证计划（可复现）

1. `curl` 取 AAR → 放 `app/libs/`（构建脚本在缺失时打印确切 URL 与大小，不静默失败）。
2. `./gradlew :app:assembleDebug`（确认 arm64 so 打进包、`package-abi` 过滤生效）。
3. 用 `ModelManager` 在设备上下载 paraformer int8 + silero VAD，校验 sha256 与 §3 一致（把实测值与清单值同时记录进 `docs/testing.md`）。
   **部分完成（2026-10-15 更新）**：设备上用真实 URL 取回并核对了 `tokens.txt`（75,756 B，sha 独立复算一致）；
   243 MB 的 `model.int8.onnx` 与 GitHub 单源的 `silero_vad.onnx` **至今没有在设备上通过 HTTP 取回过**（前者是磁盘与耗时顾虑，后者是 `github.com:443` 本机与设备都连不上）。
   三份文件在 2026-10-15 由 `adb push` + `run-as` 搬进 `filesDir/models/<id>/` 并用 `ModelVerifier` 在设备侧核对通过（大小 + SHA-256 逐项一致），
   **推理用的就是那份搬运来的文件**——所以「设备能自己下模型」这件事仍然没有证据，只有 `tokens.txt` 一条有。
4. 准备真实素材：一段 3–10 分钟中文书解 MP3（本机自行生成或用用户提供的样本），跑转录，人工抽查 10 处字幕时间对齐误差（记录 ±ms 分布）。
   **部分完成（2026-10-15）**：**整本跑通了**（148 s 真人演唱《那些花儿》→ 24 段 → 24 块 → 35 句 → 全部落库，§5 第 6~7 步有数字），
   但**人工抽查时间对齐误差这一条没做**，因为素材是歌唱、不是书解：歌词与字幕内容能逐字对上（用例里就是这么断言的），
   「这句该在几秒」需要人耳判断，而歌唱的对齐结论搬不到朗读上。
   另外 `/sdcard/Music/EchoMind/` 那批「《三体》」素材经逐字节核对是**静音占位 MP3**（帧体 99% 为 0x00），
   宿主机也没有 MP3 编码器（无 ffmpeg/lame/sox），所以**朗读型真实语音要么由用户提供，要么等 App 能录音**。
   在此之前**转录精度（字错率、时间对齐 ±ms）无从测量，不要声称测过**。静音那批现在只当负例用（`FAILED(OUTPUT_UNRELIABLE)` 那条用例）。
5. 仪器化测试覆盖「分段偏移合并」「切句」「重转替换」「任务取消/重试」，JVM 单测覆盖纯逻辑。
   **DONE（2026-10-15）**：偏移合并 = `SherpaOnnxEngineTest`（跨块时间单调）+ `TranscriptCoordinatorTest`（端到端首次入库）；
   切句 = 25 条 JVM + 1 条设备端到端用例；重转替换 = `TranscriptRepositoryTest`（整本替换 + 重复结算回滚）；
   取消 = `TranscriptRepositoryTest`（状态机）+ `TranscriptCoordinatorTest.取消位为真时立刻停手且不写终态`（编排层真的停在解码中间）；
   重试 = 仓储层用例（新建一行、旧行留错误上下文）。**全套 JVM 203 / 仪器化 104 全绿**（`docs/testing.md` §4）。
   ⚠ 唯一没覆盖的是 `work/` 那两个类（§4 末）。
6. 真机（≥2GB RAM）实测峰值 RSS 与 RTF；**未做**（则明确标注未完成）。目前所有数字都来自 emulator-5554，
   且同一素材两轮 RTF 差 1.6 倍 → 这些只能当量级，不能当性能结论。

## 10. 当前状态（诚实标注）

- **已确证/已落地**：AAR 取回并打进包（§2.1，`assembleDebug` 通过，APK 40.79 MB，只含 arm64 一份 sherpa `.so`）；
  三个模型文件已在**本机**下载并逐一核对 sha256（§3，Paraformer int8 + tokens + silero VAD，`tokens.txt` 于 2026-10-15 二次复核仍一致）；
  模型管理这层已有代码：`asr/ModelCatalog.kt`（清单，数值由 `ModelCatalogTest` 钉住）、`asr/ModelVerifier.kt`（存在/大小/SHA-256 三步核对）、
  `asr/ModelManager.kt` + `asr/ModelFetcher.kt`（下载 → `.part` → 校验 → 改名 → 记来源，含多源回退、文件级跳过、删除、按单调时钟限流的进度），
  已挂进 `AppGraph`（`modelsRoot = filesDir/models`），35 条 JVM 单测覆盖（`docs/testing.md` §4）。
- **编排层（2026-10-15 新增，Phase 6 第 4b 项，本节原来说它「尚未实现」）**：`asr/TranscriptCoordinator.kt` + `domain/asr/TranscriptQuality.kt`
  + `work/TranscriptWorker.kt` / `work/TranscriptWorkScheduler.kt`，全部挂进 `AppGraph`，`EchoMindApp.onCreate` 里调 `recoverInterrupted()`。
  **生产代码第一次把模型的字写进字幕表**：`TranscriptCoordinatorTest`（现 9 条仪器化）里那条端到端用例在 emulator-5554 上把 148 s 整首歌跑完——
  收口日志 `解码 2378188 样本（44100 Hz/2 ch → 16000 Hz，容器时长 148303 ms），语音段 24 个，块 24 个，丢弃 0 个；句子 35（硬切边界 0 句）`，
  墙钟 42,965 ms（含装载 ~5 s，≈ RTF 0.29；同一条用例两次独立进程再跑给出 33,453 ms ≈ 0.23 与 63,714 ms ≈ 0.43，**三次产出逐项相同、耗时差 1.9 倍** → RTF 只报区间），35 句全部落 `subtitle_segment`（`source=ASR`、`job_id` 指向那一行、索引连续、时间升序、
  末句不越出容器时长）。⚠ 那条用例落的**是用例进程里的 in-memory Room**：被验证的是「生产仓储 + 生产 DAO + 真模型 + 真解码」这条完整路径。
  ✅ **Phase 6d 之后用户机上那份 `echomind.db` 里也有了**：从界面点出来的 **35 行 `source=ASR` 字幕**（`segment_index` 0~34 连续、`start_ms` 14,134 ~ `end_ms` 145,766，都在容器时长 148,304 ms 内）+ 7 行 `transcript_job`（SUCCESS / FAILED(INTERRUPTED) / FAILED(OUTPUT_UNRELIABLE) / CANCELLED 各态都有），`那些花儿` 是 `READY`，播放页七句窗口读的就是这批（`docs/testing.md` §4 Phase 6d）。守护顺序、取消归属、SAF 树 uri 那条坑见 §5.1。
- **仍然没交付的**：①`work/` 两个类的主体逻辑**仍无自动化用例**（§4 末）——前台通知、`APPEND` 排队、`FAILED(UNKNOWN)` 兜底、`cancelWorkById` 撤销都只有代码 + 人工点出来的设备证据；②模型准备 UI + 本地导入（任务 #24），因此 `MODEL_MISSING` 文案里那句「请先在模型页准备好」目前指向不存在的页面；③「设备自己能下载 243 MB 模型」仍然没有证据（§9 第 3 条）；④「播放 + 转录同时跑不卡顿」（FR-509）没测。
  ✅ **「点一下就能转录出字幕」这句话在 Phase 6d 成立了**：书架 ⋮ →「转录这本书」→ 转录页「开始转录」→ 排队中/转录中/「共 24 段全部识别」，字幕进用户库、播放页能读到。
- **推理层（2026-10-15 新增，Phase 6 第 3 项终，`docs/testing.md` §4 有全部数字）**：
  `asr/VadSegmenter.kt`（silero VAD 流式封装：512 样本窗缓冲、`push/finish/release`、`VadUtterance` 带全局样本起点与该段音频）、
  `asr/SherpaOnnxEngine.kt`（`OfflineRecognizer` + `assetManager = null` 走文件系统绝对路径；`open()` 先过 `ModelVerifier`；
  >20s / 样本数不符 → `AsrInputException`；主线程构造被 `check` 拒绝）、
  `domain/asr/TokenTimingMapper.kt`（秒 → 绝对毫秒、`endMs` 取下一字起点、五种不合格输入全抛 `Mismatch`）+ 14 条 JVM 单测 + 14 条仪器化。
  **两个「不许猜」的单位问题已用真数据钉死**：`SpeechSegment.start` = 16k 样本下标（1 ms 网格，`%512` 反而不成立）、
  `result.timestamps` = **秒**（`durations` 长度 0）。真模型在 emulator-5554 上跑出过 58 个 token 的逐字时间，
  装载 ~4.9 s、识别 RTF 0.083~0.143（模拟器两轮抖动）、VAD RTF 0.017~0.020；模型文件是 `adb push` + `run-as` 搬进 `filesDir/models/` 的，
  **设备至今没能自己从 HTTP 取回 `model.int8.onnx` 与 `silero_vad.onnx`**（前者没试过 243 MB，后者 GitHub 不通）。
  仍未做的：转录精度的人工抽查（缺真人语音素材，§9 第 4 条）、整章内存峰值（只有模拟器数字）。
  ~~「块边界影响文本」的收口~~ → **已由 `SentenceSplitter` 承担并接进协调器（2026-10-15，第 4b 项）**：
  跨 VAD 段/跨块的过度切分靠 §6 第 3 条的合并窗口收拾，148 s 素材实测 35 句、硬切 0 句（§10 编排层那条有原始日志）。
- **音频数学层（2026-10-15 新增，Phase 6 第 3 项前半）**：`domain/asr/PcmConversion`（16-bit LE → 单声道 float，多声道取均值）、
  `domain/asr/SincResampler`（窗口 sinc / Blackman、每侧 16 抽头、整数相位系数表、每相位归一直流增益；实测 12 kHz 折进输出 4 kHz 的成分压到 −69 dB，
  见 `docs/architecture.md` D26）、`domain/asr/AsrChunkPlanner` + `domain/model/AsrModels`（>20s 均分、绝对毫秒偏移，见 D27）。
  36 条 JVM 单测，`grep` 确认 `domain/` 无 Android/Media3/Room import。**它们现在有生产调用方了**（2026-10-15 第 4b 项之后）：
  `PcmDecoder` 内部调 `PcmConversion` + `SincResampler`，而 `TranscriptCoordinator` 调 `PcmDecoder`/`AsrChunkPlanner`/`TokenTimingMapper`
  与 `SentenceSplitter`；写下来之前那一版确实只有测试在调，这句话留着是为了说明「谁来调」这条链路是本轮才接上的。
- **解码层（2026-10-15 新增，Phase 6 第 3 项后半）**：`asr/PcmDecoder.kt`（框架 `MediaExtractor` + `MediaCodec` 同步模式，
  贪心喂入/非阻塞排空，`PcmConversion` 均值下混 → `SincResampler` → `BlockAccumulator` 定长块回调）+
  `androidTest/asr/PcmDecoderTest.kt` 4 条，在 emulator-5554 上真解过 148 秒 MP3：44100 Hz/2 声道、单缓冲 ≤4,608 B、
  样本数与容器时长偏差 0.22%、`peak=0.659`、取消只交付 3 块、非音频文件抛人话异常（数字见 `docs/testing.md` §4）。
  **本轮顺带挖出一件影响后续的事**：Phase 3~5 一直在用的那批「《三体》」素材帧体 99% 是 `0x00`，也就是**静音占位 MP3**，
  所以「音频内容」到今天才第一次被真正验证过；转录精度需要**真人语音**素材，机器上目前只有歌曲与静音占位。
- **模型管理这层的设备侧状态（2026-10-15 更新）**：`AndroidManifest` 现在有 `INTERNET` 权限（**之前漏了**，那 35 条单测全绿也发现不了），
  `androidTest/asr/ModelManagerNetworkTest.kt` 用清单里真实的 hf-mirror URL 在 emulator-5554 上下过 `tokens.txt` 并独立复算 sha256（4/4 通过）。
  仍然空白的三块：243 MB 的 int8 模型没从设备取回过；`silero_vad.onnx`（GitHub 单源）本机与设备**都**连不上、一次都没取回过；
  设置页/转录页也还没有入口用它。**「点一下就能下模型」要到 Phase 6 第 6 项才算交付。**
- Phase 5 用测试字幕验证的是七句窗口管线；测试字幕与 ASR 产物在数据上以 `source=MANUAL_TEST` / `ASR` 严格区分，不得互相冒充。
