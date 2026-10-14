package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.domain.model.PcmChunkPlan
import com.xinchen.echomind.domain.model.TokenTiming

/**
 * 把模型给的逐字时间（**秒**）换算成整篇音频的绝对毫秒（FR-504；`docs/asr-design.md` §5 第 6 步）。
 *
 * 单位不是猜的：2026-10-15 在 emulator-5554 上用真模型跑了一段 20.224 s 的音频，
 * `timestamps` 落在 13.6 ~ 19.32。当毫秒解释就是「第 13.6 毫秒」，整句字幕会往前错 1000 倍；
 * 当秒解释正好落在音频之内。同一批测量还确认 `durations` 长度是 **0**，
 * 所以每个字的结束只能借用**下一个字的起点**（最后一个字借整块的末尾）。
 * 这不是均分伪造：每个起点都是模型输出的原值，只是「结束」这件事模型没给。
 *
 * 两件刻意不做的事（AGENTS.md §5「不许伪造时间戳」）：
 * 1. token 数与 timestamp 数不等时**不裁剪也不补齐**——直接抛 [Mismatch]。
 *    少一个时间就少一个字，硬凑出来的字幕看不出错，但会一路写进数据库。
 * 2. 越界（时间超出这块音频）不往回收拢。抛异常。
 *
 * 取整方向是**向下**（`toLong()` 截断），最大 1 ms 误差，且只会「早」不会「晚」，
 * 与 [PcmChunkPlan] 那边「样本下标 → 毫秒同样向下取整」保持一致。
 */
object TokenTimingMapper {

    /** 模型的输出自相矛盾：数量对不上、时间为负、或落在块外。调用方必须把这一块标成失败，不许猜。 */
    class Mismatch(message: String) : Exception(message)

    /**
     * @param tokens 模型的 `result.tokens`（逐字）
     * @param timestampsSec 模型的 `result.timestamps`，单位**秒**，相对这块音频的起点
     * @param chunk 这块音频在整篇里的绝对位置
     */
    fun map(
        tokens: List<String>,
        timestampsSec: FloatArray,
        chunk: PcmChunkPlan,
    ): List<TokenTiming> {
        if (tokens.isEmpty() && timestampsSec.isEmpty()) return emptyList()
        if (tokens.size != timestampsSec.size) {
            throw Mismatch(
                "token 数与时间戳数不等：tokens=${tokens.size} timestamps=${timestampsSec.size}" +
                    "（块 ${chunk.startMs}~${chunk.endMs} ms），拒绝猜测缺的是哪一个"
            )
        }

        val blockMs = chunk.endMs - chunk.startMs
        val starts = ArrayList<Long>(tokens.size)
        for (index in timestampsSec.indices) {
            val seconds = timestampsSec[index]
            if (seconds.isNaN() || seconds.isInfinite()) {
                throw Mismatch("第 $index 个字「${tokens[index]}」的时间戳不是有限数：$seconds")
            }
            if (seconds < 0f) {
                throw Mismatch("第 $index 个字「${tokens[index]}」的时间戳为负：${seconds}s")
            }
            val offsetMs = (seconds * 1000f).toLong()
            if (offsetMs > blockMs) {
                throw Mismatch(
                    "第 $index 个字「${tokens[index]}」落在块外：${seconds}s = 块内第 $offsetMs ms，" +
                        "而这块只有 $blockMs ms（${chunk.startMs}~${chunk.endMs} ms）"
                )
            }
            val absolute = chunk.startMs + offsetMs
            if (index > 0 && absolute < starts[index - 1]) {
                throw Mismatch(
                    "时间戳倒序：第 ${index - 1} 个字在 ${starts[index - 1]} ms，第 $index 个字却在 $absolute ms"
                )
            }
            starts += absolute
        }

        return tokens.mapIndexed { index, token ->
            TokenTiming(
                token = token,
                startMs = starts[index],
                // 最后一个字收到块末尾：模型没给时长，`durations` 实测为空，只能这样收口
                endMs = if (index == tokens.lastIndex) chunk.endMs else starts[index + 1],
            )
        }
    }
}
