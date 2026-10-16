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
    /** 模型自报的语言标签（SenseVoice：zh/en/yue…；whisper：en…；Paraformer 不给 → null） */
    val lang: String? = null,
)

/**
 * 一个 VAD 段识别完的汇总（>20s 的段会被 `AsrChunkPlanner` 拆成好几块，所以这里可能拼自多个 [ChunkRecognition]）。
 *
 * 「一个 VAD 段 = 一组候选字」是 `docs/asr-design.md` §6 第 1 条，段边界因此是切句的主要依据之一：
 * [startMs]/[endMs] 用的是**这一段的语音边界**（不是最后一个字的结束），字与段边界之间的那截静音也算数。
 * [tokens] 的起点应当单调不减；实测跨块拼接最可能就出在这里，所以 `SentenceSplitter` 会夹而不抛。
 */
data class RecognizedSegment(
    val startMs: Long,
    val endMs: Long,
    val tokens: List<TokenTiming>,
) {
    init {
        require(startMs >= 0) { "段起点不能为负：$startMs" }
        require(endMs >= startMs) { "段末 $endMs 早于段起 $startMs" }
    }
}

/**
 * 切句切出来、时间已清洗过的一句字幕（还没入库，入库时由 `commitSubtitles` 编号）。
 *
 * [boundaryGuess] 为 true 表示这句的边界**不是**标点也不是静音给的，是超长兜底切分时按长度硬切的
 * （§6 第 4 条的最后一档）。UI 不特殊标记，但它必须能被日志与文档追到——不许把硬切伪装成模型判的句读。
 */
data class SubtitleDraft(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val boundaryGuess: Boolean = false,
)
