package com.xinchen.echomind.domain.asr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/**
 * 抗混叠重采样器：把「源采样率的单声道 float 流」变成 [outputRate] 的流（有理数比，逐块喂入）。
 *
 * 为什么要自己写：
 * - Silero VAD 只接受 16 kHz（`docs/asr-design.md` §1-A8），而书解 MP3 常见是 44.1 kHz / 48 kHz，
 *   必须有一处把源采样率搬到 16k。
 * - Media3 1.11.1 已经没有现成实现：实测 `media3-common-1.11.1.aar` 的 `classes.jar` 里**类路径含 `resampl` 的条目 0 条**
 *   （`AudioResamplingAudioProcessor` 已不在该产物中），`media3-decoder-1.11.1.aar` 只有 `Buffer/Decoder/SimpleDecoder`
 *   一族、**没有** `MediaDecoder`。系统侧也没有「解码时顺带改采样率」的可靠开关。
 * - 直接线性插值等于**不做低通**：源里 8 kHz 以上的成分会折叠进可听带，给模型加一层它没训练过的噪声。
 *   所以这里用窗口 sinc（Blackman）做 FIR 低通，再按整数相位取值。
 *   实测频响（临时探针单测，1 秒单频正弦、掐头去尾各 64 样本做相干 DFT，2026-10-15）：
 *   48k→16k 在 1/3.5/5 kHz 为 0.0/0.0/−0.09 dB，6.5 kHz −1.33，7 kHz −2.38，7.9 kHz −5.56；
 *   会折进输出 4 kHz 的 12 kHz 压到 **−69.3 dB**，20 kHz −91.0 dB。44.1k→16k 同形（5 kHz −0.05，7 kHz −2.16，12 kHz −77.9）。
 *   通带靠 8 kHz 那端确有 2 dB 跌落，是 33 抽头的过渡带宽度决定的，不为这点边际收益翻倍抽头数——语音识别不吃这 2 dB。
 *
 * 思路：输出第 `j` 个样本对应的源位置是 `j * L / M`（`L = inputRate/gcd`、`M = outputRate/gcd`，已约分）。
 * 因为 `gcd(L,M)=1`，小数部分只有 `M` 种取值，于是给每个相位预算一组 FIR 系数，
 * 并把每组归一到直流增益 1（于是「常数输入 → 常数输出」在**中段**成立；首尾各 `T` 个样本要补零，
 * 实测 DC 输入 0.25 时首样本 0.170、末样本 0.267，这是有限窗 FIR 的固有边缘效应，不是算错），
 * 运行时每个输出样本只做一次点积。
 *
 * 流式：跨块只留 `2*T+1` 个源样本（`T = tapsPerSide`），峰值内存与音频总长无关（NFR-05/07）。
 * 契约：分块喂入产出的样本序列与一次性喂入**逐元素相同**（[resample] 的单测就是冲它去的）。
 */
class SincResampler(
    val inputRate: Int,
    val outputRate: Int,
    private val tapsPerSide: Int = DEFAULT_TAPS_PER_SIDE,
) {

    init {
        require(inputRate > 0) { "inputRate 必须为正：$inputRate" }
        require(outputRate > 0) { "outputRate 必须为正：$outputRate" }
        require(tapsPerSide >= 1) { "tapsPerSide 至少 1：$tapsPerSide" }
    }

    private val stepNumerator: Int = inputRate / gcdOf(inputRate, outputRate)
    private val stepDenominator: Int = outputRate / gcdOf(inputRate, outputRate)

    /** `[相位][抽头]`，相位 = `(j * L) % M`。 */
    private val kernels: Array<FloatArray> = buildKernels()

    private var nextOutputIndex = 0L
    private var fedSamples = 0L
    private var carry = FloatArray(0)

    /** 已产出的输出样本数。调用方用它核对「输入 N 个 ≈ 输出 N*M/L 个」。 */
    val producedSamples: Long get() = nextOutputIndex

    /**
     * 吃掉 [input] 的一块，返回**目前可以交出**的输出样本。
     *
     * [isFinal] 必须是且仅是最后一片。产出判据只有「中心样本还在数据里」这一条：
     * 中心越过数据末尾的那些（最多 `T` 个 ≈ 1ms）一律不产出——它们要靠零填充才能算出来，于是既给出
     * 近乎无意义的样本，又让「同采样率 ⇒ 长度与数值都不变」这条自检性质失效（第一次踩到的人是我）。
     * 非 final 的块还要更严一档：抽头窗必须整个落在数据里，这样分块产出与一次性产出**逐元素相同**。
     */
    fun resample(input: FloatArray, isFinal: Boolean): FloatArray {
        val workStartAbs = fedSamples - carry.size
        val work = if (carry.isEmpty()) input else carry + input
        val lastAvailable = workStartAbs + work.size - 1L
        val keep = minOf(2 * tapsPerSide + 1, work.size)
        val newCarry = work.copyOfRange(work.size - keep, work.size)

        val capacity = outputsProducible(lastAvailable, isFinal)
        fedSamples = lastAvailable + 1L
        carry = newCarry
        if (capacity == 0) return FloatArray(0)

        val out = FloatArray(capacity)
        var written = 0
        while (written < capacity) {
            val position = nextOutputIndex * stepNumerator
            val base = position / stepDenominator
            if (base > lastAvailable) break
            if (!isFinal && base + tapsPerSide > lastAvailable) break
            out[written] = dot(kernels[(position % stepDenominator).toInt()], base, work, workStartAbs)
            nextOutputIndex++
            written++
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    /** 丢弃相位从头开始。转录任务重排队时用，别拿上一本音频的尾巴接着算。 */
    fun reset() {
        nextOutputIndex = 0L
        fedSamples = 0L
        carry = FloatArray(0)
    }

    private fun outputsProducible(lastAvailable: Long, isFinal: Boolean): Int {
        val limitCenter = if (isFinal) lastAvailable else lastAvailable - tapsPerSide
        if (limitCenter < 0L) return 0
        // base = floor(j*L/M) ≤ limitCenter 的上界；真正的收敛判据在循环里
        val lastIndex = (limitCenter * stepDenominator + stepDenominator - 1L) / stepNumerator
        val count = lastIndex + 1L - nextOutputIndex
        return if (count <= 0L) 0 else count.toInt()
    }

    private fun dot(kernel: FloatArray, base: Long, work: FloatArray, workStartAbs: Long): Float {
        var acc = 0.0
        for (tap in kernel.indices) {
            val abs1 = base - tapsPerSide + tap
            if (abs1 < 0L) continue
            val index = (abs1 - workStartAbs).toInt()
            // 越界只该有两种：音频真正的开头（左补零）与最后一片（右补零）。
            // carry 留了 2T+1 个样本，中间块走不到这里；真走到了就当 0，也好过读错样本。
            val sample = if (index < 0 || index >= work.size) 0f else work[index]
            acc += kernel[tap] * sample
        }
        return acc.toFloat()
    }

    private fun buildKernels(): Array<FloatArray> {
        // 截止频率取「源与目标里较低那个」的一半，折成每个源样本的周期数
        val cutoff = 0.5 * minOf(1.0, outputRate.toDouble() / inputRate)
        return Array(stepDenominator) { phase ->
            val frac = phase.toDouble() / stepDenominator
            val kernel = FloatArray(2 * tapsPerSide + 1)
            var sum = 0.0
            for (tap in kernel.indices) {
                val u = (tap - tapsPerSide).toDouble() - frac
                val x = 2.0 * cutoff * u
                // sinc(0)=1，直接除会 NaN，所以中心抽头单列
                val lowpass = if (x == 0.0) 2.0 * cutoff else 2.0 * cutoff * sin(PI * x) / (PI * x)
                val coefficient = lowpass * blackman(u)
                kernel[tap] = coefficient.toFloat()
                sum += coefficient
            }
            if (sum == 0.0) {
                kernel.fill(0f)
                kernel[tapsPerSide] = 1f
            } else {
                for (tap in kernel.indices) kernel[tap] = (kernel[tap] / sum).toFloat()
            }
            kernel
        }
    }

    /** Blackman 窗，定义在 `|v| <= tapsPerSide`，端点正好为 0，截断处不会有明显振铃。 */
    private fun blackman(v: Double): Double {
        if (abs(v) > tapsPerSide) return 0.0
        val normalized = v / (2.0 * tapsPerSide)
        return 0.42 + 0.5 * cos(2.0 * PI * normalized) + 0.08 * cos(4.0 * PI * normalized)
    }

    companion object {
        /** 每侧 16 抽头（共 33）在语音带宽上阻带衰减够用；再加就是拿转录时间换边际收益 */
        const val DEFAULT_TAPS_PER_SIDE = 16
    }
}

/** 采样率都是小整数，迭代欧几里得够用；全为 0 时返回 1，避免除零。 */
internal fun gcdOf(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return if (x == 0) 1 else x
}
