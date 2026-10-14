package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.PcmChunkPlan
import com.xinchen.echomind.domain.model.SpeechSpan

/**
 * 把 VAD 语音段变成「可以逐个喂给识别器的小块」，并给出每块的**绝对**时间（FR-504、FR-505、`docs/asr-design.md` §5）。
 *
 * 两条硬规则：
 * 1. 单块不超过 [maxChunkMs]（默认 `AudioFormats.ASR_MAX_SEGMENT_MS` = 20s）。
 *    sherpa-onnx 对非流式模型的输入长度有约 20s 的官方约束，超了结果不可信；
 *    VAD 侧虽然配了 `maxSpeechDuration`，但那是模型内部的另一套数字，这里必须自己兜住。
 * 2. 时间只从**全局样本下标**算：`ms = sample * 1000 / sampleRate`。
 *    不做「上一段的结束时间 + 本段相对时长」这种累加——逐段累加会把整数除法的余数一点点攒成漂移，
 *    一小时后字幕就会和音频错开几十毫秒，而那正是 FR-601 要消灭的东西。
 *
 * 拆分时不平均到「最后一段特别短」：先算需要几块 `pieces`，再把每块长度取 `ceil(count/pieces)`，
 * 于是每块都 ≤ 上限且长度接近，避免末尾掉出一个 0.3s 的碎片（那种碎片识别质量最差）。
 */
object AsrChunkPlanner {

    /** 样本下标 → 绝对毫秒（向下取整）。 */
    fun msOf(sample: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate 必须为正：$sampleRate" }
        require(sample >= 0) { "样本下标不能为负：$sample" }
        return sample * 1000L / sampleRate
    }

    /** 毫秒 → 样本数（向下取整），用来把 [maxChunkMs] 换算成样本上限。 */
    fun samplesOf(ms: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate 必须为正：$sampleRate" }
        require(ms >= 0) { "毫秒不能为负：$ms" }
        return ms * sampleRate / 1000L
    }

    fun plan(
        spans: List<SpeechSpan>,
        sampleRate: Int = AudioFormats.ASR_SAMPLE_RATE,
        maxChunkMs: Long = AudioFormats.ASR_MAX_SEGMENT_MS,
    ): List<PcmChunkPlan> {
        val maxSamples = maxChunkMs * sampleRate / 1000L
        require(maxSamples >= 1) { "单块上限换算出 $maxSamples 个样本，太小：maxChunkMs=$maxChunkMs sampleRate=$sampleRate" }

        return spans.flatMap { span ->
            // 空段（VAD 理论上不该给，但模型给的数字不许信）直接跳过，不产出「0 样本」的识别调用
            if (span.sampleCount <= 0 || span.startSample < 0) return@flatMap emptyList()
            val pieces = ceilDiv(span.sampleCount.toLong(), maxSamples).toInt()
            val perPiece = ceilDiv(span.sampleCount.toLong(), pieces.toLong()).toInt()
            (0 until pieces).mapNotNull { piece ->
                val start = span.startSample + piece.toLong() * perPiece
                val count = (span.sampleCount - piece.toLong() * perPiece).coerceAtMost(perPiece.toLong()).toInt()
                if (count <= 0) return@mapNotNull null
                PcmChunkPlan(
                    startSample = start,
                    sampleCount = count,
                    startMs = msOf(start, sampleRate),
                    endMs = msOf(start + count, sampleRate),
                )
            }
        }
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor
}
