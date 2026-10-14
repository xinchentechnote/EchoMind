package com.xinchen.echomind.domain.asr

/**
 * PCM 的形态转换：`MediaCodec` 解码出来的 16-bit 小端字节 → 单声道 `[-1,1]` float。
 *
 * 纯 Kotlin（不 import `android.*`）才能在大头 JVM 单测里把「声道混合」和「尾字节处理」这两件事钉死——
 * 它们错了不会崩，只会让字幕时间整体偏移或声音变小，是最难在真机上察觉的一类 bug。
 */
object PcmConversion {

    const val BYTES_PER_SAMPLE = 2

    /** 一个完整帧的字节数。 */
    fun frameBytes(channelCount: Int): Int = BYTES_PER_SAMPLE * channelCount

    /** 能凑成完整帧的数量；末尾不足一帧的字节要交给调用方决定是留着还是丢掉（不许当成一个样本读）。 */
    fun frameCount(byteLength: Int, channelCount: Int): Int {
        require(channelCount >= 1) { "channelCount 至少 1：$channelCount" }
        return byteLength / frameBytes(channelCount)
    }

    fun leftoverBytes(byteLength: Int, channelCount: Int): Int = byteLength - frameCount(byteLength, channelCount) * frameBytes(channelCount)

    /**
     * 多声道按**均值**混合成单声道（不是取左声道：书解里左右声道可能不同相，取一路会丢内容）。
     *
     * 缩放用 `1/32768`，所以 `-32768 → -1.0f`、`32767 → ≈0.99997f`，不会溢出到 ±1 之外。
     */
    fun pcm16LeToFloatMono(bytes: ByteArray, offset: Int, length: Int, channelCount: Int): FloatArray {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "读取范围越界：offset=$offset length=$length size=${bytes.size}"
        }
        val frames = frameCount(length, channelCount)
        val out = FloatArray(frames)
        if (frames == 0) return out
        if (channelCount == 1) {
            for (frame in 0 until frames) {
                val i = offset + frame * 2
                out[frame] = sample(bytes[i], bytes[i + 1])
            }
            return out
        }
        for (frame in 0 until frames) {
            var sum = 0.0
            val base = offset + frame * frameBytes(channelCount)
            for (channel in 0 until channelCount) {
                val i = base + channel * BYTES_PER_SAMPLE
                sum += sample(bytes[i], bytes[i + 1]).toDouble()
            }
            out[frame] = (sum / channelCount).toFloat()
        }
        return out
    }

    fun pcm16LeToFloatMono(bytes: ByteArray, channelCount: Int): FloatArray =
        pcm16LeToFloatMono(bytes, 0, bytes.size, channelCount)

    private fun sample(low: Byte, high: Byte): Float {
        val unsigned = (low.toInt() and 0xFF) or (high.toInt() shl 8)
        return unsigned.toShort() * (1f / 32768f)
    }
}
