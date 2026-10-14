package com.xinchen.echomind.core.conf

/** 扫描与解码相关的格式白名单（FR-103）。扩展其他格式前必须先实测系统解码能力。 */
object AudioFormats {
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3")

    /** ASR 侧统一采样率；sherpa-onnx 内部会重采样，但 VAD 要求 16k（docs/asr-design.md §1-A8） */
    const val ASR_SAMPLE_RATE: Int = 16_000

    /** 转录单段上限：sherpa-onnx 官方要求非流式模型输入不超过约 20s */
    const val ASR_MAX_SEGMENT_MS: Long = 20_000
}
