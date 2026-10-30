package com.xinchen.echomind.asr

import android.os.Looper
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.SpeechSpan
import java.io.File

/**
 * VAD 判出的一段语音：在**整篇**音频里的起点 + 这段的音频本身。
 *
 * 起点单位已由设备实测钉死（`docs/testing.md` §4，2026-10-15）：sherpa 给的 `SpeechSegment.start`
 * 是 **16 kHz 下的样本下标**（落在 1 ms = 16 样本的网格上），不是毫秒。
 * 判据：60 s 素材（962,560 样本）里最大 start=944,288；当毫秒解释这段音频只有 96,256 ms，
 * 也就是说这个「起点」比整篇还长，只能当样本下标。
 *
 * 把 `samples` 一起带出来是刻意的：一小时书解的 16k float 是 230 MB，整篇留在内存里必然 OOM。
 * VAD 本来就在断句时攒着这段音频，直接交出去，调用方就只需要留住「当前这一段」（≤20s ≈ 1.28 MB）。
 */
class VadUtterance(val startSample: Long, val samples: FloatArray) {

    val span: SpeechSpan get() = SpeechSpan(startSample = startSample, sampleCount = samples.size)

    val endSample: Long get() = startSample + samples.size
}

/** silero VAD 封装：流式吃 16k 单声道 float，吐出语音段（`docs/asr-design.md` §5 第 3-4 步）。 */
class VadSegmenter(
    modelFile: File,
    private val sampleRate: Int = AudioFormats.ASR_SAMPLE_RATE,
    /**
     * 本实例吃到的第一样本在**整条音频时间线**上的绝对位置（FR-512 续跑：断点前的不喂，
     * 从断点起喂）。sherpa 报的 `segment.start` 只从「它自己被喂过多少」数起，从断点续跑时
     * 内部计数从 0 重新开始，不加这个偏移，续跑切出的所有段时间都会整体前移。
     */
    private val sampleOffset: Long = 0,
) {

    /** 攒不满一窗的尾巴。silero v5 只吃 512 样本的窗，实测喂别的长度不会推进状态。 */
    private val window = FloatArray(AudioFormats.VAD_WINDOW_SAMPLES)
    private var windowFilled = 0
    private var released = false
    private val vad: Vad

    init {
        // 「推理不得在主线程」（AGENTS.md §5）：这里要真的把 onnx 读进 native 堆
        check(Looper.myLooper() != Looper.getMainLooper()) { "VadSegmenter 不能在主线程构造（要加载 VAD 模型）" }
        require(modelFile.isFile) { "VAD 模型文件不存在：${modelFile.path}" }
        require(sampleRate == AudioFormats.ASR_SAMPLE_RATE) {
            // silero v5 的 512 窗是按 16k 定的，换采样率等于换一个模型，清单里没有就别自作主张
            "VAD 只按 16 kHz 设计（AudioFormats.ASR_SAMPLE_RATE），传进来的是 $sampleRate"
        }
        vad = Vad(
            assetManager = null, // null = 用文件系统绝对路径，不打 assets
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelFile.absolutePath,
                    threshold = AudioFormats.VAD_THRESHOLD,
                    minSilenceDuration = AudioFormats.VAD_MIN_SILENCE_SEC,
                    minSpeechDuration = AudioFormats.VAD_MIN_SPEECH_SEC,
                    windowSize = AudioFormats.VAD_WINDOW_SAMPLES,
                    maxSpeechDuration = AudioFormats.VAD_MAX_SPEECH_SEC,
                ),
                sampleRate = sampleRate,
            ),
        )
    }

    /**
     * 追加 PCM，返回**这次**判定完整的语音段（模型认为还没结束的段不会出现在这里）。
     *
     * 允许任意长度：内部按 [AudioFormats.VAD_WINDOW_SAMPLES] 切窗，所以 `PcmDecoder` 的 4096 块
     * 直接进来就行，调用方不必对齐窗边界。
     */
    fun push(samples: FloatArray): List<VadUtterance> {
        checkNotReleased()
        var index = 0
        val out = mutableListOf<VadUtterance>()
        while (index < samples.size) {
            val take = minOf(window.size - windowFilled, samples.size - index)
            System.arraycopy(samples, index, window, windowFilled, take)
            index += take
            windowFilled += take
            if (windowFilled == window.size) {
                out += acceptWindow(window.copyOf())
                windowFilled = 0
            }
        }
        return out
    }

    /**
     * 收尾：不足一窗的尾巴照样喂进去 + `flush()`，吐出最后几段。
     * 一个 [VadSegmenter] 实例用完就该 [release]，再 push 是不成对使用。
     */
    fun finish(): List<VadUtterance> {
        checkNotReleased()
        val out = mutableListOf<VadUtterance>()
        if (windowFilled > 0) {
            out += acceptWindow(window.copyOf(windowFilled))
            windowFilled = 0
        }
        vad.flush()
        out += drain()
        return out
    }

    /** 与 [init] 里的 native 构造成对。重复调用安全（不再往 native 里 second-free）。 */
    fun release() {
        if (released) return
        released = true
        vad.release()
    }

    private fun acceptWindow(windowSamples: FloatArray): List<VadUtterance> {
        vad.acceptWaveform(windowSamples)
        return drain()
    }

    private fun drain(): List<VadUtterance> {
        val out = mutableListOf<VadUtterance>()
        while (!vad.empty()) {
            val segment = vad.front()
            // start 是样本下标（实测已证），samples 是这段的音频；sampleOffset 把它抬回整条时间线
            out += VadUtterance(startSample = sampleOffset + segment.start.toLong(), samples = segment.samples)
            vad.pop()
        }
        return out
    }

    private fun checkNotReleased() {
        check(!released) { "VadSegmenter 已经 release 了，不能再喂音频" }
    }
}
