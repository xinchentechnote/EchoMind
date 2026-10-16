package com.xinchen.echomind.asr

import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.asr.TokenTimingMapper
import com.xinchen.echomind.domain.model.ChunkRecognition
import com.xinchen.echomind.domain.model.PcmChunkPlan
import java.io.File

/** 模型不合格（缺文件/大小或哈希不符）以至于引擎起不来。带人话原因，UI 要原样显示。 */
class AsrModelNotReadyException(message: String) : Exception(message)

/** 喂进来的音频与这一块的元数据不一致，或者超过官方 20s 约束。属于调用方 bug，立刻暴露。 */
class AsrInputException(message: String) : Exception(message)

/**
 * sherpa-onnx 离线 Paraformer 封装（`docs/asr-design.md` §5 第 5-6 步）。
 *
 * 三条来自 AGENTS.md §5 的硬规矩，都写死在这个类里：
 * 1. **不许在主线程构造**：装载 243 MB 的 int8 模型在 emulator-5554 上实测 **4,906 / 4,914 ms**（两轮，`docs/testing.md` §4），
 *    放主线程就是 ANR。
 * 2. **单段 ≤20s**：sherpa-onnx 对非流式模型的官方约束，超了结果不可信 → 超了直接抛，不悄悄截断。
 * 3. **`release()` 成对**：全 App 只允许一个引擎句柄，native 侧漏一次就是几百 MB 常驻。
 *
 * 时间戳单位已经实测钉死：`result.timestamps` 是**秒**、且相对这一块（19.2 s 的块首个字换算出 13,580 ms；
 * 按毫秒解释会偏 1000 倍并立刻出块），`result.durations` 长度是 **0**。换算见 [TokenTimingMapper]。
 */
class SherpaOnnxEngine private constructor(
    private val recognizer: OfflineRecognizer,
) {

    private var released = false

    /**
     * 识别**一块**音频。[samples] 必须正好是 [chunk] 描述的那段（16k 单声道 float）。
     *
     * 返回的 [ChunkRecognition.tokens] 是**绝对毫秒**；[ChunkRecognition.text] 是模型给这块的原文。
     * 模型输出自相矛盾（数量不等/越界/倒序）时抛 [TokenTimingMapper.Mismatch]，不返回半份结果。
     */
    fun recognize(chunk: PcmChunkPlan, samples: FloatArray): ChunkRecognition {
        check(!released) { "SherpaOnnxEngine 已经 release，不能再用" }
        if (samples.size != chunk.sampleCount) {
            throw AsrInputException("块说要 ${chunk.sampleCount} 个样本，实际给了 ${samples.size} 个（块 ${chunk.startMs} ms 起）")
        }
        val maxSamples = AudioFormats.ASR_MAX_SEGMENT_MS * AudioFormats.ASR_SAMPLE_RATE / 1000L
        if (samples.size > maxSamples) {
            throw AsrInputException(
                "单段 ${samples.size} 样本 = ${samples.size * 1000L / AudioFormats.ASR_SAMPLE_RATE} ms，" +
                    "超过官方上限 ${AudioFormats.ASR_MAX_SEGMENT_MS} ms（该先由 AsrChunkPlanner 切块）"
            )
        }

        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AudioFormats.ASR_SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return ChunkRecognition(
                chunk = chunk,
                text = result.text,
                tokens = TokenTimingMapper.map(result.tokens.toList(), result.timestamps, chunk),
            )
        } finally {
            stream.release()
        }
    }

    /** 与 [open] 里的 native 构造成对；之后 [recognize] 会立刻抛，不会 use-after-free。 */
    fun release() {
        if (released) return
        released = true
        recognizer.release()
    }

    companion object {
        /**
         * 核对模型文件 → 加载引擎。**会阻塞几秒**（Paraformer 实测装载 4,100 ms），调用方必须在后台线程。
         *
         * [spec] 决定装配哪种 sherpa-onnx 模型配置：中文书解默认用清单里的 Paraformer（逐字时间戳），
         * 英文/歌曲用 [ModelCatalog.whisperBase]（`enableTokenTimestamps = true`、`language = ""` 自动检测）。
         * 核对走 [ModelVerifier]，也就是清单里那份 sha256，不接受「文件在就用」。
         */
        fun open(modelsRoot: File, spec: ModelSpec = ModelCatalog.paraformer): SherpaOnnxEngine {
            check(Looper.myLooper() != Looper.getMainLooper()) { "SherpaOnnxEngine 不能在主线程打开（要加载 onnx 模型）" }
            val dir = ModelVerifier.dirOf(modelsRoot, spec)
            val failures = spec.files
                .map { fileSpec -> ModelVerifier.checkFile(File(dir, fileSpec.fileName), fileSpec) }
                .filterNotNull()
            if (failures.isNotEmpty()) {
                throw AsrModelNotReadyException(
                    "${spec.displayName} 不可用：${failures.joinToString("；") { ModelManager.describeFailure(it) }}。" +
                        "模型目录 ${dir.path}"
                )
            }
            val tokensFile = spec.files.first { it.fileName.endsWith("tokens.txt") }.fileName
            val modelConfig = when (spec.kind) {
                ModelKind.PARAFORMER -> OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = File(dir, ModelCatalog.PARAFORMER_MODEL_FILE).absolutePath,
                    ),
                    tokens = File(dir, tokensFile).absolutePath,
                    numThreads = AudioFormats.ASR_NUM_THREADS,
                    debug = false,
                    modelType = "paraformer",
                )

                ModelKind.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig().copy(
                        encoder = File(dir, ModelCatalog.WHISPER_ENCODER_FILE).absolutePath,
                        decoder = File(dir, ModelCatalog.WHISPER_DECODER_FILE).absolutePath,
                        language = "", // 空 = 模型自动检测语言（实测 lang=en 检测正确）
                        task = "transcribe",
                        enableTokenTimestamps = true, // 实测导出无注意力权重，时间戳仍为空（D41 打样记录）
                    ),
                    tokens = File(dir, tokensFile).absolutePath,
                    numThreads = AudioFormats.ASR_NUM_THREADS,
                    debug = false,
                    modelType = "whisper",
                )

                ModelKind.SENSEVOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, ModelCatalog.SENSEVOICE_MODEL_FILE).absolutePath,
                        language = "", // 空 = 自动检测（result.lang 回读）
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, tokensFile).absolutePath,
                    numThreads = AudioFormats.ASR_NUM_THREADS,
                    debug = false,
                    modelType = "sense-voice",
                )
            }
            val recognizer = OfflineRecognizer(
                assetManager = null, // null = 用文件系统绝对路径，模型不进 assets 也不进 APK
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = AudioFormats.ASR_SAMPLE_RATE,
                        featureDim = AudioFormats.ASR_FEATURE_DIM,
                        dither = AudioFormats.ASR_DITHER,
                    ),
                    modelConfig = modelConfig,
                ),
            )
            return SherpaOnnxEngine(recognizer)
        }
    }
}
