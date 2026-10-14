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

/**
 * 一个字/词和它的**绝对**时间（整篇音频的毫秒，FR-504 的产物形态）。
 *
 * [startMs] 一律来自模型的 `timestamps`（换算见 `domain/asr/TokenTimingMapper`），
 * [endMs] 取下一个 token 的起点——paraformer 的 `durations` 实测是空数组，模型没给每字时长。
 * 两者都不许用「按字数均分」造出来（AGENTS.md §5）。
 */
data class TokenTiming(
    val token: String,
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0) { "startMs 不能为负：$startMs" }
        require(endMs >= startMs) { "token「$token」endMs=$endMs 早于 startMs=$startMs" }
    }
}

/**
 * 识别器对**一小块**音频的产出：原文 + 逐字时间。
 *
 * [text] 是模型给整句的文本（含标点、可能带空格），[tokens] 是逐字时间；
 * 两者的数量关系由 `TokenTimingMapper` 核对过才敢一起返回——
 * 对不上时它抛异常，而不是硬凑一份看起来完整的字幕。
 * 一个 [ChunkRecognition] 只对应一个 [PcmChunkPlan]，块与块之间的拼接由调用方负责。
 */
data class ChunkRecognition(
    val chunk: PcmChunkPlan,
    val text: String,
    val tokens: List<TokenTiming>,
)
