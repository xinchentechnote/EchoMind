package com.xinchen.echomind.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.SpeechSpan
import com.xinchen.echomind.domain.asr.SincResampler

import com.xinchen.echomind.domain.asr.AsrChunkPlanner
import com.xinchen.echomind.domain.model.PcmChunkPlan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whisper 打样实测（D41 第一期）：不做产品断言，产出**选型数据**——
 * 1. 英文 TTS（有标准答案）→ 转写质量人工比对 + RTF；
 * 2. 用户的 56 分钟中文书解 mp3 前 120 s → 真实语音上的 RTF；
 * 3. 《那些花儿》全曲 → 中英歌词质量目检；
 * 4. 顺手量出 whisper 时间戳语义（相对块起点还是绝对、数量是否与 token 对齐）——
 *    决定 [TokenTimingMapper] 要不要加 whisper 支路。
 *
 * 直接构造 [OfflineRecognizer]（与 `SherpaOnnxEngine` 工厂同一套配置），为的是拿到**原始**
 * `result.tokens/timestamps/lang` 而不是经过 Mapper 的结果。模型缺文件时 fail 并给推送命令。
 */
@RunWith(AndroidJUnit4::class)
class WhisperPilotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 英文 TTS 的标准答案（macOS `say` 生成音频时用的原文，一字不差） */
    private val enGroundTruth =
        "Chapter One. The old lighthouse stood at the edge of the harbour, and for forty-two years " +
            "Mr. Whitfield had kept its lamp burning through every storm. On the morning of October the third, " +
            "the sea was unusually quiet, and the gulls had gone silent. He climbed the one hundred and eight " +
            "steps with a thermos of tea, a brass key, and a letter he had never opened. By noon, the fog lifted, " +
            "and he saw something that changed everything: a small blue boat, drifting toward the rocks with no one aboard."

    private fun whisperRecognizer(): OfflineRecognizer {
        val spec = ModelCatalog.whisperBase
        val dir = ModelVerifier.dirOf(DeviceAsrFixtures.modelRoot(context), spec)
        val failures = spec.files
            .map { fileSpec -> ModelVerifier.checkFile(File(dir, fileSpec.fileName), fileSpec) }
            .filterNotNull()
        if (failures.isNotEmpty()) {
            val f = failures.first()
            error("whisper 模型不可用：${ModelManager.describeFailure(f)}。请执行：\n" +
                "  adb push app/libs/models/whisper-base-multilingual/${f.fileName} /data/local/tmp/w.bin\n" +
                "  adb shell run-as $PACKAGE sh -c 'mkdir -p files/models/${spec.id} && cat /data/local/tmp/w.bin > files/models/${spec.id}/${f.fileName}'")
        }
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = AudioFormats.ASR_SAMPLE_RATE,
                    featureDim = AudioFormats.ASR_FEATURE_DIM,
                    dither = AudioFormats.ASR_DITHER,
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig().copy(
                        encoder = File(dir, ModelCatalog.WHISPER_ENCODER_FILE).absolutePath,
                        decoder = File(dir, ModelCatalog.WHISPER_DECODER_FILE).absolutePath,
                        language = "",
                        task = "transcribe",
                        enableTokenTimestamps = true,
                        enableSegmentTimestamps = true,
                    ),
                    tokens = File(dir, ModelCatalog.WHISPER_TOKENS_FILE).absolutePath,
                    numThreads = AudioFormats.ASR_NUM_THREADS,
                    debug = false,
                    modelType = "whisper",
                ),
            ),
        )
    }

    private fun senseVoiceRecognizer(): OfflineRecognizer {
        val spec = ModelCatalog.senseVoice
        val dir = ModelVerifier.dirOf(DeviceAsrFixtures.modelRoot(context), spec)
        val failures = spec.files
            .map { fileSpec -> ModelVerifier.checkFile(File(dir, fileSpec.fileName), fileSpec) }
            .filterNotNull()
        if (failures.isNotEmpty()) {
            val f = failures.first()
            error("sense-voice 模型不可用：${ModelManager.describeFailure(f)}。请执行：" +
                "  adb push app/libs/models/sense-voice-zh-en-ja-ko-yue/${f.fileName} /data/local/tmp/sv.bin；" +
                "  adb shell run-as $PACKAGE sh -c 'cat /data/local/tmp/sv.bin > files/models/${spec.id}/${f.fileName}'")
        }
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = AudioFormats.ASR_SAMPLE_RATE,
                    featureDim = AudioFormats.ASR_FEATURE_DIM,
                    dither = AudioFormats.ASR_DITHER,
                ),
                modelConfig = OfflineModelConfig(
                    senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                        model = File(dir, ModelCatalog.SENSEVOICE_MODEL_FILE).absolutePath,
                        language = "",
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, ModelCatalog.SENSEVOICE_TOKENS_FILE).absolutePath,
                    numThreads = AudioFormats.ASR_NUM_THREADS,
                    debug = false,
                    modelType = "sense-voice",
                ),
            ),
        )
    }

    /** 解码最多 [maxSeconds] 秒并按 ≤20 s 切块（复用 AsrChunkPlanner），返回 (全部样本, 块计划) */
    private fun decodeChunks(
        context: Context,
        uri: Uri,
        maxSeconds: Int,
    ): Pair<FloatArray, List<PcmChunkPlan>> {
        val collected = mutableListOf<FloatArray>()
        var total = 0
        val limit = maxSeconds * AudioFormats.ASR_SAMPLE_RATE
        PcmDecoder(context).decode(uri) { samples, _ ->
            collected += samples
            total += samples.size
            total < limit
        }
        val all = FloatArray(total)
        var offset = 0
        for (c in collected) {
            System.arraycopy(c, 0, all, offset, c.size)
            offset += c.size
        }
        val chunks = AsrChunkPlanner.plan(listOf(SpeechSpan(startSample = 0, sampleCount = total)))
        return all to chunks
    }

    /** 逐块识别并计时，返回（拼全文, 总墙钟 ms, 首块原始 tokens/timestamps 形状日志） */
    private fun recognizeAll(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        chunks: List<PcmChunkPlan>,
    ): Triple<String, Long, String> {
        val text = StringBuilder()
        var wallMs = 0L
        var shapeLog = "（无输出）"
        for ((index, chunk) in chunks.withIndex()) {
            val from = (chunk.startSample - chunks.first().startSample).toInt()
            val slice = samples.copyOfRange(from, from + chunk.sampleCount)
            val stream = recognizer.createStream()
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                stream.acceptWaveform(slice, AudioFormats.ASR_SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val wall = android.os.SystemClock.elapsedRealtime() - t0
                wallMs += wall
                text.append(result.text).append(' ')
                if (index == 0) {
                    val tsHead = result.timestamps.take(6).joinToString { String.format(java.util.Locale.US, "%.2f", it) }
                    val tokHead = result.tokens.take(10).joinToString { it }
                    shapeLog = "首块 tokens=${result.tokens.size} timestamps=${result.timestamps.size} " +
                        "首6时间戳=[$tsHead] 首10token=[$tokHead] lang=${result.lang}"
                }
                Log.i(TAG, "块 $index（${chunk.startMs}ms 起，${chunk.sampleCount} 样本）：wall=${wall}ms text=${result.text.take(120)}")
            } finally {
                stream.release()
            }
        }
        return Triple(text.toString().trim(), wallMs, shapeLog)
    }

    @Test
    fun 英文TTS转写质量与RTF() {
        val audioSeconds = 27.3
        val recognizer = whisperRecognizer()
        val (samples, chunks) = decodeChunks(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture("en-tts.wav", context)),
            maxSeconds = 60,
        )
        val (text, wallMs, shape) = recognizeAll(recognizer, samples, chunks)
        val rtf = wallMs / 1000f / audioSeconds
        Log.i(TAG, "【英文TTS】RTF=$rtf（${wallMs}ms / ${audioSeconds}s）shape=$shape")
        Log.i(TAG, "【英文TTS】标准答案：$enGroundTruth")
        Log.i(TAG, "【英文TTS】whisper转写：$text")
        // 粗 sanity：关键实词应该在（WER 的精确计算留给人工比对）
        // whisper 会做文本归一化：harbour→harbor、forty-two→42，所以关键词选不受归一化影响的实词
        listOf("lighthouse", "whitfield", "thermos", "drifting", "burning", "fog").forEach { word ->
            assertTrue("转写里找不到关键词「$word」：$text", text.lowercase().contains(word))
        }
    }

    @Test
    fun 用户中文书解前两分钟的RTF() {
        val recognizer = whisperRecognizer()
        val (samples, chunks) = decodeChunks(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture("user-test.mp3", context)),
            maxSeconds = 120,
        )
        val (text, wallMs, shape) = recognizeAll(recognizer, samples, chunks)
        val audioSec = samples.size / AudioFormats.ASR_SAMPLE_RATE.toFloat()
        Log.i(TAG, "【中文书解${audioSec}s】RTF=${wallMs / 1000f / audioSec}（${wallMs}ms）shape=$shape")
        Log.i(TAG, "【中文书解】whisper转写（前 400 字）：${text.take(400)}")
    }

    @Test
    fun 全曲中英歌词质量目检() {
        val recognizer = whisperRecognizer()
        val (samples, chunks) = decodeChunks(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture(context = context)),
            maxSeconds = 200,
        )
        val (text, wallMs, shape) = recognizeAll(recognizer, samples, chunks)
        val audioSec = samples.size / AudioFormats.ASR_SAMPLE_RATE.toFloat()
        Log.i(TAG, "【全曲${audioSec}s】RTF=${wallMs / 1000f / audioSec}（${wallMs}ms）shape=$shape")
        Log.i(TAG, "【全曲歌词】whisper转写：$text")
    }

    // -- SenseVoice（D41 第二候选：中英日韩粤 + CTC 时间戳） ------------------------------

    @Test
    fun senseVoice英文TTS质量与时间戳语义() {
        val recognizer = senseVoiceRecognizer()
        val (samples, chunks) = decodeChunks(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture("en-tts.wav", context)),
            maxSeconds = 60,
        )
        val (text, wallMs, shape) = recognizeAll(recognizer, samples, chunks)
        val audioSec = samples.size / AudioFormats.ASR_SAMPLE_RATE.toFloat()
        Log.i(TAG, "【SV英文TTS】RTF=${wallMs / 1000f / audioSec}（${wallMs}ms）shape=$shape")
        Log.i(TAG, "【SV英文TTS】标准答案：$enGroundTruth")
        Log.i(TAG, "【SV英文TTS】sensevoice转写：$text")
    }

    @Test
    fun senseVoice中文书解RTF与时间戳语义() {
        val recognizer = senseVoiceRecognizer()
        val (samples, chunks) = decodeChunks(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture("user-test.mp3", context)),
            maxSeconds = 120,
        )
        val (text, wallMs, shape) = recognizeAll(recognizer, samples, chunks)
        val audioSec = samples.size / AudioFormats.ASR_SAMPLE_RATE.toFloat()
        Log.i(TAG, "【SV中文书解${audioSec}s】RTF=${wallMs / 1000f / audioSec}（${wallMs}ms）shape=$shape")
        Log.i(TAG, "【SV中文书解】转写（前 400 字）：${text.take(400)}")
    }

    @Test
    fun senseVoice全曲中英歌词() {
        val recognizer = senseVoiceRecognizer()
        val (samples, chunks) = decodeChunks(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture(context = context)),
            maxSeconds = 200,
        )
        val (text, wallMs, shape) = recognizeAll(recognizer, samples, chunks)
        val audioSec = samples.size / AudioFormats.ASR_SAMPLE_RATE.toFloat()
        Log.i(TAG, "【SV全曲${audioSec}s】RTF=${wallMs / 1000f / audioSec}（${wallMs}ms）shape=$shape")
        Log.i(TAG, "【SV全曲歌词】sensevoice转写：$text")
    }

    private companion object {
        const val TAG = "WhisperPilot"
    }
}
