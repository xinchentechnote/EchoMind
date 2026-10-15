package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.core.conf.AudioFormats

/**
 * 转录质量闸门（`docs/asr-design.md` §8 第一行，2026-10-15 定的降级策略）。
 *
 * 背景：`TokenTimingMapper` 发现模型自相矛盾（token 数与时间戳数不等、越界、倒序）时抛 `Mismatch`，
 * 那一块的字**没有可用时间**。此时只有两条路：
 * - 把这块丢掉 → 字幕在那个时间区间是空洞（诚实，但读者看不见缺了什么）；
 * - 按字数补时间 → AGENTS.md §5 明确禁止，写进库就再也查不出来是猜的。
 * 所以丢块是默认行为，本对象只管一件事：**丢到什么程度就不该再管这叫「转录成功」**。
 *
 * 用整数比较而不是 `rejected / total` 的浮点比例：除法在 1 块对 6 块这种小分母上会给出
 * 16.666…%，「四舍五入算不算超阈值」是无意义的问题，宁可让判据只看乘积。
 */
object TranscriptQuality {

    /** 一块都没跑（[totalChunks] = 0）时返回 false：没有证据支持「成功」这个结论。 */
    fun isUsable(
        totalChunks: Int,
        rejectedChunks: Int,
        maxRejectedPercent: Int = AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT,
    ): Boolean {
        require(totalChunks >= 0) { "块数不能为负：$totalChunks" }
        require(rejectedChunks in 0..totalChunks) { "丢弃块数 $rejectedChunks 不在 0..$totalChunks 之内" }
        require(maxRejectedPercent in 0..100) { "阈值必须是百分数：$maxRejectedPercent" }
        if (totalChunks == 0) return false
        return rejectedChunks * 100L <= maxRejectedPercent * totalChunks.toLong()
    }
}
