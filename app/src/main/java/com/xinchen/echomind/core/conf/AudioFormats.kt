package com.xinchen.echomind.core.conf

/** 扫描与解码相关的格式白名单（FR-103）。扩展其他格式前必须先实测系统解码能力。 */
object AudioFormats {

    /**
     * 扫描白名单（FR-103）。**只列实测过「导入 → 播放 → 转录解码」的格式**，没实测的不放行：
     * - `mp3`（2026-10-15 起全程在用）
     * - `m4a` / `aac`（AAC in M4A，2026-10-16 端到端实测：导入 → 播放 → `PcmDecoder` 解码 → 转录出 38 句）
     * - `flac` / `wav`（同日实测导入、播放与转录解码；素材由 macOS `afconvert` 从同一份真语音转出）
     *
     * 刻意**不在**白名单里：
     * - `ogg` / `opus`：`DeviceCodecProbeTest` 探针确认设备上 `audio/vorbis` / `audio/opus` 解码器**存在**，
     *   缺的只是本机能产出的测试素材（`afconvert` 不支持 vorbis 容器）——素材到位后加两个词即可放行。
     * - `wma`：Android（AOSP）**没有 WMA 解码器**——`asr/DeviceCodecProbeTest` 在设备上枚举解码器清单为证；
     *   支持它必须引入 FFmpeg 量级的第三方解码框架，按 AGENTS.md §3 得先证明系统能力不足（已证）并立决策记录后才能动手。
     */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3", "m4a", "aac", "flac", "wav")

    /** ASR 侧统一采样率；sherpa-onnx 内部会重采样，但 VAD 要求 16k（docs/asr-design.md §1-A8） */
    const val ASR_SAMPLE_RATE: Int = 16_000

    /** 转录单段上限：sherpa-onnx 官方要求非流式模型输入不超过约 20s */
    const val ASR_MAX_SEGMENT_MS: Long = 20_000

    // ── silero VAD 的参数（唯一出处；`docs/asr-design.md` §5 第 4 步引用的就是这几行）──
    // 取 sherpa-onnx 上游 `vad_utils.py` 的默认值，时长单位是**秒**（API 里这几个字段是 float）。
    // `VAD_WINDOW_SAMPLES = 512` 是 silero v5 在 16k 下的硬要求，改成别的值模型会直接报错。

    /** VAD 每次喂入的窗长（= 32 ms @16k） */
    const val VAD_WINDOW_SAMPLES: Int = 512

    const val VAD_THRESHOLD: Float = 0.5f

    /** 静音超过这么久才断句 */
    const val VAD_MIN_SILENCE_SEC: Float = 0.25f

    /** 短于此长度的语音段直接丢掉 */
    const val VAD_MIN_SPEECH_SEC: Float = 0.15f

    /** 语音连续超过这么久强制切段（与 ASR_MAX_SEGMENT_MS 同值，改一个必须改另一个） */
    const val VAD_MAX_SPEECH_SEC: Float = 20f

    // ── sherpa-onnx 识别器参数（唯一出处）──

    /** Paraformer 的 fbank 维度。2026-10-15 在 emulator-5554 上装载成功用的就是这个值。 */
    const val ASR_FEATURE_DIM: Int = 80

    /** 关掉特征加噪：同一段音频两次转录必须给出同样的时间戳，dither 会让它抖。 */
    const val ASR_DITHER: Float = 0f

    /** 推理线程数。模拟器上 2 线程实测识别 RTF 0.13~0.14（`docs/testing.md` §4，同一素材两轮抖动值），再多只是跟播放抢 CPU。 */
    const val ASR_NUM_THREADS: Int = 2

    // ── 切句参数（唯一出处；`docs/asr-design.md` §6 的四条规则引用的就是下面这几个值）──

    /** 单句上限（FR-602 的「默认 60 字」）。超了才走兜底切分，见 `SentenceSplitter`。 */
    const val ASR_MAX_SENTENCE_CHARS: Int = 60

    /**
     * 段内断句的**起点差**绝对下限（ms，§6 第 2 条）。
     *
     * 它**不是静音时长**：Paraformer 不给每字时长（`durations` 实测长度 0），相邻两字起点之差 =
     * 前一字的发音长度 + 中间静音。2026-10-15 设备实测：一段歌唱素材按「差 ≥300 ms 就断」硬切，
     * 58 个字切成 53 句，几乎全是单字——唱歌时每个字本来就占 400~600 ms，那是**慢**不是**停顿**。
     * 挡住这种情况的是 [ASR_PAUSE_MEDIAN_RATIO]（相对本段典型字距），**不是**这个下限：慢素材里两者会同时越过 500 ms。
     * 下限管的是另一头——语速极快时中位数只有 100 ms，2 倍也才 200 ms，那种跳跃不值得换一行。
     * **未用真人朗读标定。**
     */
    const val ASR_INTRA_SEGMENT_PAUSE_MS: Long = 500

    /**
     * 起点差 ≥ 本段起点差**中位数** × 此倍数 才算停顿（§6 第 2 条）。
     * 取 2 = 「停顿至少听起来比正常字距慢一倍」；用中位数而不是平均值，一段里只有一个大间隔时它不会把基准带高。
     */
    const val ASR_PAUSE_MEDIAN_RATIO: Int = 2

    /**
     * 相邻候选句的间隔 ≤ 这么久、且合起来不超上限，就合并——消掉 VAD 的过度切分（§6 第 3 条）。
     *
     * 这个值**必须宽于 `VAD_MIN_SILENCE_SEC`（250 ms）**，否则永远合不了：VAD 能断成两段就说明中间静音已 ≥250 ms。
     * 700 ms = 250 的约三倍，取的是「书解里句内换气」的量级，**尚未用真语音素材标定**（缺真人朗读素材，见 `docs/roadmap.md`）。
     */
    const val ASR_SENTENCE_MERGE_GAP_MS: Long = 700

    // ── 转录质量闸门（`docs/asr-design.md` §8 的降级策略，2026-10-15 定）──

    /**
     * 一轮转录里「模型输出不合格、被丢掉的块」占比超过这个百分数，整本标失败而不是「成功但满是小洞」。
     *
     * 丢块本身是诚实做法（`TokenTimingMapper.Mismatch` 抛出来的块**没有可用时间**，
     * 而 AGENTS.md §5 禁止用均分把洞填上），但丢掉 1/5 以上意味着字幕里会有规律性的缺失，
     * 用户看到的「已完成」和实际能读的东西对不上。取 20 是「平均每 5 块丢 1 块」这个量级，
     * **未标定**（真机上 Mismatch 的实际发生率目前一个样本都没有）。
     */
    const val ASR_MAX_REJECTED_CHUNK_PERCENT: Int = 20
}
