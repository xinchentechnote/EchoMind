package com.xinchen.echomind.domain.asr

/**
 * 「流内样本序号 ↔ 容器 PTS」的对齐账本（解码样本时间轴锚到播放时钟，2026-10-16）。
 *
 * 为什么需要它：字幕时间戳从解码流内的样本序号换算（第 N 个样本 = N/rate 秒），而播放位置走
 * 容器 PTS。干净文件两者一致（`那些花儿.mp3` 全程漂移 0.0 ms，实测）；但 VBR/含坏帧的 MP3 上
 * 解码器可能对个别帧不出样本——样本计数器于是**短于** PTS，字幕时间轴被压缩，
 * 越到后面字幕越提前（用户实测：mp3 播到后面字幕比语音快一两行）。
 *
 * 规则：预期位置 = 起点 PTS + 已推入样本数换算；实际 PTS 超前预期超过 [TOLERANCE_US] 就让调用方
 * 补一段静音把流「垫」到 PTS（静音进 VAD 是非语音，无副作用），落后超过容差则把起点重锚
 * （PTS 倒退不该搬动已产出的时间戳）。容差内的抖动（帧时长取整的 ±0.4 µs/帧）不动作。
 */
class PtsTimeline {

    private var startUs: Long = Long.MIN_VALUE

    val isAnchored: Boolean get() = startUs != Long.MIN_VALUE

    /**
     * 流内样本序号到达 [streamIndex]（其真实时间应为 [actualPtsUs]）之前，需要向流里补多少微秒的静音。
     * 返回 0 = 不需要；大于 0 = 补这么多的静音（样本数 = µs × rate / 1e6）。
     */
    fun gapBefore(actualPtsUs: Long, streamIndex: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate 必须为正：$sampleRate" }
        if (actualPtsUs < 0) return 0L // 没有可信 PTS（解码器没给）就维持样本计数
        val expectedUs = startUs + streamIndex * 1_000_000L / sampleRate
        if (startUs == Long.MIN_VALUE || expectedUs < 0L) {
            // 首次锚定：让流内序号 0 恰好落在 actualPts - streamIndex×周期 上
            startUs = actualPtsUs - streamIndex * 1_000_000L / sampleRate
            return 0L
        }
        val driftUs = actualPtsUs - expectedUs
        if (driftUs < 0L) {
            // PTS 倒退（容器时间轴异常）：重锚到实际值，不搬动历史
            startUs += driftUs
            return 0L
        }
        return if (driftUs <= TOLERANCE_US) 0L else driftUs
    }

    companion object {
        /** 帧时长取整的累计抖动远小于这个值；跨过它就是真的丢帧/跳帧 */
        const val TOLERANCE_US = 20_000L
    }
}
