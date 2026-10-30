package com.xinchen.echomind.domain.asr

/**
 * 续跑闸门（FR-512 转录断点）：解码从 0 开始，但 [resumeFromSample] 之前的音频**已经识别过**，
 * 它的字在断点文件里——这些样本不能再次喂给 VAD，否则从断点起会识别出与前一轮重叠的段。
 *
 * 抽成纯函数是为了这条规则能在 JVM 单测里断言（AGENTS.md §6）：整块在断点前的丢弃、
 * 骑在断点上的块掐头、断点之后的原样放行，三种情形各有性质断言。
 */
object ResumeGate {

    /** 一块没被整个丢掉的音频：掐头后的起点（16 kHz 绝对样本）与剩余样本 */
    data class Trimmed(val startSample: Long, val samples: FloatArray)

    /**
     * [startSample] 起的这块音频里，落在 [resumeFromSample] 之后的部分。
     * 整块都在断点之前返回 null（调用方跳过，不喂 VAD）；否则返回掐头后的起点与样本。
     */
    fun slice(samples: FloatArray, startSample: Long, resumeFromSample: Long): Trimmed? {
        val endSample = startSample + samples.size
        if (endSample <= resumeFromSample) return null
        if (startSample >= resumeFromSample) return Trimmed(startSample, samples)
        val cut = (resumeFromSample - startSample).toInt()
        return Trimmed(startSample = resumeFromSample, samples = samples.copyOfRange(cut, samples.size))
    }
}
