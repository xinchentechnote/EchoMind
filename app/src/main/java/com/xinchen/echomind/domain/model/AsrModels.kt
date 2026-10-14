package com.xinchen.echomind.domain.model

/**
 * ASR 管线在 domain 侧的数据形态（`docs/asr-design.md` §4）。
 *
 * 刻意不放 native 类型：`com.k2fsa.sherpa.onnx.SpeechSegment` 之类的东西只许出现在 `asr/` 里，
 * 这样「分段与时间偏移」这套算式能在 JVM 单测里跑（AGENTS.md §3、§6）。
 */

/**
 * VAD 报出的一段语音。
 *
 * [startSample] 是**整篇音频**里的样本下标（重采样之后、16 kHz 单声道），不是这一段的相对位置——
 * VAD 是流式喂的，它给的起点本来就是全局的。
 * [sampleCount] 是这段的样本数。
 */
data class SpeechSpan(
    val startSample: Long,
    val sampleCount: Int,
) {
    val endSample: Long get() = startSample + sampleCount
}

/**
 * 一次识别调用要喂的那一小块：从 [SpeechSpan] 里切出来、带绝对位置。
 *
 * [startMs] / [endMs] 都由样本下标算（`domain/asr/AsrChunkPlanner`），
 * 是**整数除法**的绝对时间，段与段之间不会因为逐段累加而漂移（FR-505）。
 */
data class PcmChunkPlan(
    val startSample: Long,
    val sampleCount: Int,
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(sampleCount > 0) { "样本数必须为正：$sampleCount" }
        require(endMs >= startMs) { "endMs=$endMs 早于 startMs=$startMs" }
    }
}
