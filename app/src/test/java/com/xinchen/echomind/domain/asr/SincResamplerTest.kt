package com.xinchen.echomind.domain.asr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重采样器的单测只关心一件事：**输出序列对不对**。
 * 长度差一两个不会毁掉转录，而「相位算错」会让音频变调、「没低通」会给模型喂混叠噪声，
 * 这两种在真机上都只会表现为「字幕偶尔不准」，几乎不可能被肉眼抓出来，所以必须在 JVM 里钉死。
 */
class SincResamplerTest {

    private fun constant(n: Int, value: Float) = FloatArray(n) { value }

    /** 相干 DFT 单点幅度（Goertzel 思路），用来判「这层低通到底有没有在工作」。 */
    private fun magnitudeAt(samples: FloatArray, frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        var re = 0.0
        var im = 0.0
        for (i in samples.indices) {
            re += samples[i] * cos(w * i)
            im += samples[i] * sin(w * i)
        }
        return 2.0 * sqrt(re * re + im * im) / samples.size
    }

    private fun sine(count: Int, frequencyHz: Double, sampleRate: Int): FloatArray {
        // 整数个周期，避免窗边缘泄漏干扰幅度判断
        val cycles = (count.toDouble() * frequencyHz / sampleRate).toInt().coerceAtLeast(1)
        val actualHz = cycles * sampleRate.toDouble() / count
        return FloatArray(count) { (sin(2.0 * PI * actualHz * it / sampleRate)).toFloat() }
    }

    @Test
    fun `同采样率必须是恒等变换（长度逐元素都相等）`() {
        val input = FloatArray(500) { ((it * 37) % 211) / 100f - 1f }
        val out = SincResampler(44_100, 44_100).resample(input, isFinal = true)
        assertEquals(input.size, out.size)
        // 系数在整数栅格上只剩中心抽头 1.0，旁瓣是 sin(π·整数) 的浮点残差（实测 ~3e-33）。
        // 所以逐元素差必须远小于一个量化级（16-bit 满量程的 1e-5 已经宽了三个数量级）；
        // 曾经直接写 contentEquals，它在输入恰好为 0.0f 的两个样本上失败——不是滤波错了，是 0 没有相对容差
        var maxDiff = 0f
        for (i in input.indices) maxDiff = maxOf(maxDiff, abs(input[i] - out[i]))
        assertTrue("同采样率下逐元素差应在浮点残差量级，实测 $maxDiff", maxDiff < 1e-5f)
    }

    @Test
    fun `常数输入得到常数输出（每相位系数归一到直流增益1）`() {
        val out = SincResampler(44_100, 16_000).resample(constant(4_410, 0.25f), isFinal = true)
        assertTrue(out.isNotEmpty())
        // 头尾各 T 个抽头会补零，只有中段才是「滤波器增益 = 1」的证据
        val middle = out.drop(SincResampler.DEFAULT_TAPS_PER_SIDE + 1)
            .dropLast(SincResampler.DEFAULT_TAPS_PER_SIDE + 1)
        assertTrue("中段应全为 0.25，实测 max=${middle.max()} min=${middle.min()}",
            middle.all { abs(it - 0.25f) < 1e-4f })
    }

    @Test
    fun `通带内的 1kHz 正弦幅度不衰减`() {
        val input = sine(44_100, 1_000.0, 44_100)
        val out = SincResampler(44_100, 16_000).resample(input, isFinal = true)
        val trimmed = out.drop(64).dropLast(64)
        val amplitude = magnitudeAt(trimmed.toFloatArray(), 1_000.0, 16_000)
        assertTrue("1kHz 应基本原样通过，实测幅度 $amplitude", amplitude in 0.95..1.05)
    }

    @Test
    fun `通带上限：5kHz 平坦、7kHz 只跌 2dB 左右（33 抽头决定的过渡带）`() {
        // 这条钉的是 cutoff 别写错。若 cutoff 被误取成源奈奎斯（不降采样时该 0.5、降采样时该 out/in*0.5 写反），
        // 5kHz 处就会出现明显跌落，一眼能量出来
        val fiveK = magnitudeAt(outOf(44_100, 16_000, 5_000.0), 5_000.0, 16_000)
        assertTrue("5kHz 应基本平坦，实测幅度 $fiveK", fiveK in 0.97..1.03)
        val sevenK = magnitudeAt(outOf(44_100, 16_000, 7_000.0), 7_000.0, 16_000)
        // 实测 −2.16 dB。接受到 −3 dB（0.707）为止；再深就说明窗口/抽头数被动过了
        assertTrue("7kHz 允许轻微跌落但不该进阻带，实测幅度 $sevenK", sevenK in 0.70..0.85)
    }

    @Test
    fun `输出奈奎斯以上成分必须被压掉（这就是不做线性插值的理由）`() {
        val out = outOf(48_000, 16_000, 12_000.0)
        // 12kHz 在 16k 采样率下与 4kHz 是同一个 DFT bin（实信号共轭对称），所以这个探针量的就是「混叠进来多少」
        val residual = magnitudeAt(out, 12_000.0, 16_000)
        // sine() 的幅度是 1.0，magnitudeAt 给的也是幅度，所以 dB 直接对 1.0 取比值
        val attenuationDb = 20.0 * log10(residual.coerceAtLeast(1e-12))
        // 实测 −69.3 dB。阈值定 −60 是给浮点与探针窗留余量；线性插值在这个频点几乎不衰减（≈ −6 dB 量级），
        // 所以这条断言正好把「有没有真低通」分开
        assertTrue("12kHz 成分应衰减 > 60dB，实测 ${attenuationDb.toInt()}dB", attenuationDb < -60.0)
    }

    /** 整数个周期的单频探针：整秒输入，掐掉头尾各 64 个样本再测幅度。 */
    private fun outOf(inputRate: Int, outputRate: Int, frequencyHz: Double): FloatArray {
        val out = SincResampler(inputRate, outputRate).resample(sine(inputRate, frequencyHz, inputRate), isFinal = true)
        return out.drop(64).dropLast(64).toFloatArray()
    }

    @Test
    fun `输出样本数正好等于 ceil(N 乘 目标率 除以 源率)`() {
        // PcmDecoder 要拿这个式子对账「解出来的样本数 ÷ 采样率 == 时长」，所以长度必须是**可预测**的，
        // 不能是「约等于」。实测 44.1k→16k 逐点吻合（N=100 → 37、N=4410 → 1600、N=44100 → 16000）
        for (n in listOf(1, 2, 33, 100, 4_410, 44_100, 97_600)) {
            val out = SincResampler(44_100, 16_000).resample(FloatArray(n), isFinal = true)
            val predicted = kotlin.math.ceil(n * 16_000.0 / 44_100.0).toInt()
            assertEquals("N=$n 的输出长度", predicted, out.size)
        }
    }

    @Test
    fun `分块喂入与一次性喂入必须逐元素相同`() {
        val input = FloatArray(4_410) { sin(it * 0.013f) }
        val oneShot = SincResampler(44_100, 16_000).resample(input, isFinal = true)

        val chunked = SincResampler(44_100, 16_000)
        val collected = mutableListOf<Float>()
        var offset = 0
        while (offset < input.size) {
            val size = minOf(377, input.size - offset)
            val isLast = offset + size == input.size
            collected += chunked.resample(input.copyOfRange(offset, offset + size), isFinal = isLast).toList()
            offset += size
        }
        assertEquals("分块与整段的产出数量必须相同", oneShot.size, collected.size)
        assertEquals(oneShot.toList(), collected)
    }

    @Test
    fun `reset 之后重新开始不受上一段尾巴影响`() {
        val a = constant(1_000, 0.5f)
        val resampler = SincResampler(44_100, 16_000)
        resampler.resample(constant(3_000, -0.9f), isFinal = false)
        resampler.reset()
        val out = resampler.resample(a, isFinal = true)
        val expected = SincResampler(44_100, 16_000).resample(a, isFinal = true)
        assertEquals(expected.toList(), out.toList())
    }

    @Test
    fun `一秒44100样本正好产出16000个输出`() {
        // 产出判据是「中心样本还在数据里」，所以 1 秒就是 16000 个，不多不少。
        // 曾经写成「允许末尾多出 T 个零填充样本」，结果同采样率下的恒等性质被破坏——那种尾巴宁可不产
        val out = SincResampler(44_100, 16_000).resample(FloatArray(44_100), isFinal = true)
        assertEquals(16_000, out.size)
    }

    @Test
    fun `整数倍关系（48k到16k）输出正好是输入的三分之一`() {
        assertEquals(16_000, SincResampler(48_000, 16_000).resample(FloatArray(48_000) { 0.1f }, isFinal = true).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `采样率为零直接拒绝`() {
        SincResampler(0, 16_000)
    }
}
