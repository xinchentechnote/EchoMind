package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.asr.AsrChunkPlanner
import com.xinchen.echomind.domain.asr.TokenTimingMapper
import com.xinchen.echomind.domain.model.ChunkRecognition
import com.xinchen.echomind.domain.model.PcmChunkPlan
import com.xinchen.echomind.domain.model.SpeechSpan
import java.io.File
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真识别模型第一次在设备上开口（`docs/asr-design.md` §5 第 5-6 步）。
 *
 * 这条测试的存在理由是把两个「不许猜」的单位问题钉成契约：
 * `OfflineRecognizerResult.timestamps` 是**秒**且相对本块起点（19.2 s 的块里首个字换算出 13,580 ms；
 * 反过来把毫秒当秒喂进来会跑到 3 小时开外，[com.xinchen.echomind.domain.asr.TokenTimingMapper] 会抛
 * `Mismatch` 而不是产出漂移字幕 —— 见 `把秒当成毫秒喂进换算器会立刻抛错而不是产出漂移字幕`），
 * `durations` 长度是 **0**（所以逐字的结束只能取下一个字的起点）。
 * 装载耗时实测 4,906~4,914 ms，整个类共用一个句柄（[BeforeClass]/[AfterClass] 成对释放）：
 * 243 MB 的模型多装一次，模拟器那 1.2 GB 可用内存就危险一次。
 */
@RunWith(AndroidJUnit4::class)
class SherpaOnnxEngineTest {

    companion object {
        private const val TAG = "SherpaEngineTest"

        private val context: Context = ApplicationProvider.getApplicationContext()

        /** 模型躺在 App 私有的 `files/models/<spec.id>/` 下，与生产布局一致（`docs/asr-design.md` §7）。 */
        private val modelsRoot: File = DeviceAsrFixtures.modelRoot(context)

        private lateinit var engine: SherpaOnnxEngine

        @BeforeClass
        @JvmStatic
        fun openEngine() {
            val startedAt = SystemClock.elapsedRealtime()
            engine = SherpaOnnxEngine.open(modelsRoot)
            Log.i(TAG, "OfflineRecognizer 装载耗时 ${SystemClock.elapsedRealtime() - startedAt} ms（后台线程）")
        }

        @AfterClass
        @JvmStatic
        fun releaseEngine() {
            if (::engine.isInitialized) engine.release()
        }
    }

    /** 本类自己统计的识别耗时（不是引擎内部数字，是 wall clock）。 */
    private var decodeMs = 0L
    private var decodedAudioMs = 0L

    private fun recognize(chunk: PcmChunkPlan, samples: FloatArray): ChunkRecognition {
        val startedAt = SystemClock.elapsedRealtime()
        val result = engine.recognize(chunk, samples)
        decodeMs += SystemClock.elapsedRealtime() - startedAt
        decodedAudioMs += chunk.endMs - chunk.startMs
        return result
    }

    @Test
    fun `设备侧按清单复核识别模型的三个文件`() {
        val verification = ModelVerifier.verify(ModelCatalog.paraformer, modelsRoot, System.currentTimeMillis())
        Log.i(TAG, "paraformer 目录 ${verification.dir.path} 核对：${if (verification.isReady) "OK" else verification.failures}")
        assertTrue("设备上的识别模型与清单不符：${verification.failures}", verification.isReady)
        val bytes = verification.dir.walk().filter { it.isFile }.sumOf { it.length() }
        Log.i(TAG, "目录里文件合计 $bytes B，清单期望 ${ModelCatalog.paraformer.totalBytes} B")
        assertTrue("目录里的大小比清单还小：$bytes", bytes >= ModelCatalog.paraformer.totalBytes)
    }

    @Test
    fun `逐字时间落在块内且单调单位是秒`() {
        // 19 s 才保证 AsrChunkPlanner 只给一块（20 s 会因解码块过冲被切成两块）
        val audio = DeviceAsrFixtures.decodedSeconds(19)
        val chunk = AsrChunkPlanner.plan(listOf(SpeechSpan(0, audio.size))).single()
        val recognition = recognize(chunk, audio)
        val tokens = recognition.tokens
        val audioMs = audio.size * 1000L / AudioFormats.ASR_SAMPLE_RATE
        Log.i(
            TAG,
            "识别 text=「${recognition.text}」 tokens=${tokens.size} 首=${tokens.firstOrNull()?.startMs} " +
                "末=${tokens.lastOrNull()?.endMs} 块=${chunk.startMs}~${chunk.endMs} audioMs=$audioMs " +
                "decodeMs=$decodeMs RTF=" + "%.4f".format(decodeMs.toDouble() / audioMs)
        )

        assertTrue("模型一个字都没给（素材是真人声）：text=「${recognition.text}」", recognition.text.isNotBlank())
        assertTrue("tokens 为空", tokens.isNotEmpty())
        // 单位判据：秒被当成毫秒的话换算结果会跑到 30 分钟开外，立刻出块
        for (token in tokens) {
            assertTrue(
                "字「${token.token}」[${token.startMs},${token.endMs}] 出块 [${chunk.startMs},${chunk.endMs}]",
                token.startMs in chunk.startMs..chunk.endMs && token.endMs in chunk.startMs..chunk.endMs,
            )
        }
        assertTrue("逐字时间倒挂", tokens.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue("最后一个字的结束应收到块末尾", tokens.last().endMs == chunk.endMs)
        assertTrue(
            "相邻字的结束应等于下一个字的开始（模型没给 durations，只能这样收口）",
            tokens.dropLast(1).zip(tokens.drop(1)).all { (a, b) -> a.endMs == b.startMs },
        )
    }

    @Test
    fun `VAD段逐块识别出来的时间是全局单调的`() {
        // 真端到端：解码 → VAD → AsrChunkPlanner → 识别。这是第一批可用于字幕的逐字时间
        val segmenter = VadSegmenter(DeviceAsrFixtures.vadModel(context))
        val recognitions = mutableListOf<ChunkRecognition>()
        val wallStart = SystemClock.elapsedRealtime()
        var fedSamples = 0L
        try {
            PcmDecoder(context).decode(Uri.fromFile(DeviceAsrFixtures.fixture(context = context))) { samples, startSample ->
                for (utterance in segmenter.push(samples)) {
                    fedSamples = maxOf(fedSamples, utterance.endSample)
                    if (recognitions.size < 6) recognitions += chunksOf(utterance)
                }
                startSample + samples.size < 60L * AudioFormats.ASR_SAMPLE_RATE
            }
            for (utterance in segmenter.finish()) {
                if (recognitions.size < 6) recognitions += chunksOf(utterance)
            }
        } finally {
            segmenter.release()
        }
        val tokens = recognitions.flatMap { it.tokens }
        val speechMs = decodedAudioMs
        Log.i(
            TAG,
            "端到端：语音到 $fedSamples 样本($fedSamples * 1000 / 16 ms 处) 块=${recognitions.size} token=${tokens.size} " +
                "跨度=${tokens.firstOrNull()?.startMs}~${tokens.lastOrNull()?.endMs} " +
                "识别语音长=${speechMs}ms decodeMs=$decodeMs RTF=" + "%.4f".format(decodeMs.toDouble() / speechMs) +
                " 整轮 wallMs=${SystemClock.elapsedRealtime() - wallStart}"
        )
        recognitions.forEachIndexed { index, recognition ->
            Log.i(TAG, "  块$index ${recognition.chunk.startMs}~${recognition.chunk.endMs} 「${recognition.text}」")
        }

        assertTrue("一段都没识别", recognitions.isNotEmpty())
        assertTrue("一个字的绝对时间都没出", tokens.isNotEmpty())
        assertTrue("跨块时间倒挂", tokens.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue(
            "有块超过官方 20s：${recognitions.map { it.chunk.endMs - it.chunk.startMs }}",
            recognitions.all { it.chunk.endMs - it.chunk.startMs <= AudioFormats.ASR_MAX_SEGMENT_MS },
        )
    }

    /** 一段（可能 >20s）→ 若干块的识别结果，最多取 3 块，别让这条测试跑完整章。 */
    private fun chunksOf(utterance: VadUtterance): List<ChunkRecognition> =
        AsrChunkPlanner.plan(listOf(utterance.span)).take(3).map { chunk ->
            val from = (chunk.startSample - utterance.startSample).toInt()
            recognize(chunk, utterance.samples.copyOfRange(from, from + chunk.sampleCount))
        }

    @Test
    fun `超过20秒的块必须被拒绝而不是悄悄截断`() {
        val samples = FloatArray(AudioFormats.ASR_SAMPLE_RATE * 21) // 21 s
        val chunk = AsrChunkPlanner.plan(
            spans = listOf(SpeechSpan(0, samples.size)),
            maxChunkMs = 30_000L, // 故意绕过规划器的 20s 上限，看引擎自己兜不兜
        ).single()
        val outcome = runCatching { engine.recognize(chunk, samples) }
        assertTrue("超 20s 竟然喂进去了：${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is AsrInputException)
    }

    @Test
    fun `样本数与块描述不符要报错`() {
        val chunk = AsrChunkPlanner.plan(listOf(SpeechSpan(0, AudioFormats.ASR_SAMPLE_RATE))).single()
        val outcome = runCatching { engine.recognize(chunk, FloatArray(AudioFormats.ASR_SAMPLE_RATE - 1)) }
        assertTrue(outcome.exceptionOrNull() is AsrInputException)
    }

    @Test
    fun `模型不合格时引擎拒绝装载并把原因写清楚`() {
        val emptyRoot = File(context.externalCacheDir ?: context.cacheDir, "no-models-here")
        emptyRoot.mkdirs()
        val outcome = runCatching { SherpaOnnxEngine.open(emptyRoot) }
        val failure = outcome.exceptionOrNull()
        assertTrue("缺模型竟然装起来了：$failure", failure is AsrModelNotReadyException)
        val message = failure!!.message!!
        assertTrue("错误里要点名缺的文件：$message", message.contains("model.int8.onnx 不存在"))
        assertTrue("错误里要点名缺的文件：$message", message.contains("tokens.txt 不存在"))
        assertTrue("错误里要给出目录好排查：$message", message.contains(emptyRoot.path))
    }

    @Test
    fun `主线程装载模型要直接拒绝`() {
        val outcome = runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { SherpaOnnxEngine.open(modelsRoot) }
        }
        assertTrue("主线程竟然能打开引擎：${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `把秒当成毫秒喂进换算器会立刻抛错而不是产出漂移字幕`() {
        val audio = DeviceAsrFixtures.decodedSeconds(19)
        val chunk = AsrChunkPlanner.plan(listOf(SpeechSpan(0, audio.size))).single()
        val recognition = recognize(chunk, audio)
        assertTrue("这条测试需要模型真给出时间", recognition.tokens.isNotEmpty())
        // 已经换算好的毫秒再当秒喂一次 → 数值 ×1000 → 必然出块
        val asIfSeconds = FloatArray(recognition.tokens.size) { recognition.tokens[it].startMs.toFloat() }
        val outcome = runCatching {
            TokenTimingMapper.map(recognition.tokens.map { it.token }, asIfSeconds, chunk)
        }
        assertTrue("单位搞错竟然没被发现：${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is TokenTimingMapper.Mismatch)
    }
}
