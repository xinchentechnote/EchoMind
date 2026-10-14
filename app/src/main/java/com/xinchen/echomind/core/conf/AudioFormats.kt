package com.xinchen.echomind.core.conf

/** 扫描与解码相关的格式白名单（FR-103）。扩展其他格式前必须先实测系统解码能力。 */
object AudioFormats {
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3")

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
}
