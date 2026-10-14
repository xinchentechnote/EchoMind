package com.xinchen.echomind.domain.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PCM16 小端 → 单声道 float 的转换。这里的每条断言都对应一种「不会崩、只会让字幕悄悄变歪」的错误：
 * 字节序搞反 = 整个音量变成噪声；声道取一路 = 立体声书解丢内容；尾帧没对齐 = 时间轴每块偏一样本。
 */
class PcmConversionTest {

    private fun le(value: Short): ByteArray =
        byteArrayOf((value.toInt() and 0xFF).toByte(), ((value.toInt() shr 8) and 0xFF).toByte())

    @Test
    fun `单声道按小端读取并缩放到 -1 到 1`() {
        val bytes = le(0) + le(32767) + le(-32768) + le(-1)
        val out = PcmConversion.pcm16LeToFloatMono(bytes, channelCount = 1)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-9f)
        assertEquals(32767 / 32768f, out[1], 1e-9f)
        assertEquals(-1f, out[2], 1e-9f)
        assertEquals(-1 / 32768f, out[3], 1e-9f)
        assertTrue("不许越过 ±1", out.all { it >= -1f && it <= 1f })
    }

    @Test
    fun `高字节在后的写法读出来才对（防字节序反）`() {
        // 0x0201 = 513，如果按大端读会变成 0x0102 = 258
        val out = PcmConversion.pcm16LeToFloatMono(byteArrayOf(0x01, 0x02), channelCount = 1)
        assertEquals(513 / 32768f, out[0], 1e-9f)
    }

    @Test
    fun `立体声按均值混合而不是取左声道`() {
        val left = 32767
        val right = -32768
        val bytes = le(left.toShort()) + le(right.toShort())
        val out = PcmConversion.pcm16LeToFloatMono(bytes, channelCount = 2)
        assertEquals(1, out.size)
        assertEquals(0f, out[0], 2e-5f)
        // 只有取一路才会得到 ±1，这条断言把「均值」这个语义钉死
        assertTrue("不该等于任一声道", kotlin.math.abs(out[0]) < 0.1f)
    }

    @Test
    fun `单侧有声时混合后幅度减半（这就是取均值的代价，如实测出）`() {
        val bytes = le(32767.toShort()) + le(0)
        val out = PcmConversion.pcm16LeToFloatMono(bytes, channelCount = 2)
        assertEquals(0.5f, out[0], 1e-4f)
    }

    @Test
    fun `三声道也能混合`() {
        val bytes = le(3000) + le(6000) + le(9000)
        val out = PcmConversion.pcm16LeToFloatMono(bytes, channelCount = 3)
        assertEquals(6000 / 32768f, out[0], 1e-6f)
    }

    @Test
    fun `不足一帧的尾字节被丢掉并如实报告`() {
        val bytes = le(1000) + byteArrayOf(0x7F)
        assertEquals(1, PcmConversion.frameCount(bytes.size, 1))
        assertEquals(1, PcmConversion.leftoverBytes(bytes.size, 1))
        val out = PcmConversion.pcm16LeToFloatMono(bytes, channelCount = 1)
        assertEquals(1, out.size)
        assertEquals(1000 / 32768f, out[0], 1e-9f)
    }

    @Test
    fun `立体声尾半个帧同样只算一帧`() {
        val bytes = le(1) + le(2) + le(3)
        assertEquals(1, PcmConversion.frameCount(bytes.size, 2))
        assertEquals(2, PcmConversion.leftoverBytes(bytes.size, 2))
        assertEquals(1, PcmConversion.pcm16LeToFloatMono(bytes, channelCount = 2).size)
    }

    @Test
    fun `可以只转缓冲区中间一段`() {
        val bytes = le(0) + le(0) + le(16384) + le(16384) + le(0) + le(0)
        val out = PcmConversion.pcm16LeToFloatMono(bytes, offset = 4, length = 4, channelCount = 1)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
    }

    @Test
    fun `空输入给空输出`() {
        assertEquals(0, PcmConversion.pcm16LeToFloatMono(ByteArray(0), channelCount = 1).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `声道数为零直接拒绝`() {
        PcmConversion.pcm16LeToFloatMono(ByteArray(4), channelCount = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `读取范围越界直接拒绝而不是静默少读`() {
        PcmConversion.pcm16LeToFloatMono(ByteArray(4), offset = 2, length = 4, channelCount = 1)
    }
}
