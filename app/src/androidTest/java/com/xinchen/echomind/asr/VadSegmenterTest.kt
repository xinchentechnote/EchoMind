package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinchen.echomind.core.conf.AudioFormats
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * silero VAD 在**真设备 + 真模型 + 真解码音频**上的行为（`docs/asr-design.md` §5 第 3-4 步）。
 *
 * 最要紧的是单位：sherpa 的 `SpeechSegment.start` 是样本下标还是毫秒，猜错就等于整条字幕轴偏 1000 倍。
 * 判据（本类实测，数字进日志并进 `docs/testing.md` §4）：
 * 60 s 素材 = 962,560 样本 = 60,160 ms，实际 start 是六位数量级（例如 223,904），
 * 当毫秒解释比整篇还长 → 只能是 16k 下的样本下标；而且每个 start 都能被 16 整除，
 * 也就是落在 1 ms 的网格上（VAD 内部按窗推进，但起点被它对齐到了毫秒）。
 */
@RunWith(AndroidJUnit4::class)
class VadSegmenterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 流式跑法：解码一块 → push 一块，全程只留住当前块 + 已出段（生产管线就是这个形状）。 */
    private fun runStreaming(blockSize: Int, maxSeconds: Int): Stats {
        val segmenter = VadSegmenter(DeviceAsrFixtures.vadModel(context))
        val spans = mutableListOf<VadUtterance>()
        var total = 0L
        var vadMs = 0L
        val target = maxSeconds.toLong() * AudioFormats.ASR_SAMPLE_RATE
        val startedAt = SystemClock.elapsedRealtime()
        try {
            PcmDecoder(context, blockSize = blockSize).decode(Uri.fromFile(DeviceAsrFixtures.fixture())) { samples, startSample ->
                val pushStart = SystemClock.elapsedRealtime()
                spans += segmenter.push(samples)
                vadMs += SystemClock.elapsedRealtime() - pushStart
                total = startSample + samples.size
                total < target
            }
            val finishStart = SystemClock.elapsedRealtime()
            spans += segmenter.finish()
            vadMs += SystemClock.elapsedRealtime() - finishStart
        } finally {
            segmenter.release()
        }
        val wholeMs = SystemClock.elapsedRealtime() - startedAt
        val audioMs = total * 1000 / AudioFormats.ASR_SAMPLE_RATE
        Log.i(
            TAG,
            "VAD blockSize=$blockSize fedSamples=$total fedMs=$audioMs segments=${spans.size} " +
                "speechSamples=${spans.sumOf { it.samples.size }} vadMs=$vadMs RTF(纯VAD)=" +
                "%.4f".format(vadMs.toDouble() / audioMs) + " 整轮(含解码)wallMs=$wholeMs rms=" +
                "%.4f".format(speechRms(spans)) + " startList=${spans.map { it.startSample }} " +
                "lenList=${spans.map { it.samples.size }}"
        )
        return Stats(spans, vadMs, wholeMs, total)
    }

    private class Stats(
        val spans: List<VadUtterance>,
        val vadMs: Long,
        val wholeMs: Long,
        val totalSamples: Long,
    )

    private fun speechRms(spans: List<VadUtterance>): Double {
        var sum = 0.0
        var count = 0
        for (utterance in spans) {
            for (sample in utterance.samples) {
                sum += (sample * sample).toDouble()
                count++
            }
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    @Test
    fun `真素材流式分段起点是16k样本下标且段与段不重叠`() {
        val run = runStreaming(blockSize = 4_096, maxSeconds = 60)
        val spans = run.spans
        assertTrue("VAD 一段都没出：阈值或素材有问题（rms 见日志）", spans.isNotEmpty())

        var previousEnd = 0L
        for (utterance in spans) {
            assertTrue("段长必须为正：${utterance.startSample}/${utterance.samples.size}", utterance.samples.size > 0)
            // 单位判据①：落在 1 ms = 16 样本的网格上
            assertEquals("start=${utterance.startSample} 不在 1 ms(16 样本) 网格上", 0L, utterance.startSample % 16L)
            // 单位判据②：start 若按毫秒解释会超过整篇长度，只有当样本下标才在范围内
            assertTrue(
                "start+len=${utterance.endSample} 超出整篇样本数 ${run.totalSamples}",
                utterance.endSample <= run.totalSamples,
            )
            assertTrue("段倒挂或重叠：prevEnd=$previousEnd start=${utterance.startSample}", utterance.startSample >= previousEnd)
            previousEnd = utterance.endSample
        }

        val minSpeechSamples = AudioFormats.VAD_MIN_SPEECH_SEC * AudioFormats.ASR_SAMPLE_RATE
        val maxSpeechSamples = AudioFormats.VAD_MAX_SPEECH_SEC * AudioFormats.ASR_SAMPLE_RATE
        // 断句由 minSpeechDuration 保证；最后一截是 finish() 里 flush 出来的，模型没承诺它够长
        assertTrue(
            "有段短于 minSpeechDuration(${minSpeechSamples} 样本)：${spans.map { it.samples.size }}",
            spans.dropLast(1).all { it.samples.size >= minSpeechSamples },
        )
        assertTrue(
            "有段长于 maxSpeechDuration(${maxSpeechSamples} 样本)，会把 >20s 音频喂进识别器：${spans.map { it.samples.size }}",
            spans.all { it.samples.size <= maxSpeechSamples + AudioFormats.VAD_WINDOW_SAMPLES },
        )
        // 语音段不能是静音：这台设备上另有 99% 字节全零的占位素材，用它当负例（见 PcmDecoderTest）
        assertTrue("语音段 rms 太低（等于没声音）：${speechRms(spans)}", speechRms(spans) > 0.001)
    }

    @Test
    fun `同一份音频不同喂入块长得出一模一样的分段`() {
        // 喂满同一批样本，只换每次 push 的长度：证明「攒不满一窗的尾巴要留住」真做对了。
        // （3000 与 512 不整除，最容易暴露窗缓冲的错位；上一版这里用「解到第 20 秒就停」当输入，
        //  结果 4096 与 1024 两种块长的停止点相差 3072 个样本，flush 出来的最后一截自然不等长。）
        val audio = DeviceAsrFixtures.decodedSeconds(20)
        val byNormal = feed(audio, 4_096)
        val bySmall = feed(audio, 1_024)
        val byOdd = feed(audio, 3_000)
        val bySingle = feed(audio, audio.size)

        assertTrue("20s 素材应该能出段：$byNormal", byNormal.isNotEmpty())
        assertEquals("4096 与 1024 不一致", byNormal, bySmall)
        assertEquals("4096 与 3000（不整除 512）不一致", byNormal, byOdd)
        assertEquals("4096 与单块不一致", byNormal, bySingle)
        Log.i(TAG, "同一份音频的分段（与块长无关）：$byNormal")
    }

    /** 按 [chunkSize] 切给 [VadSegmenter.push]，返回 (startSample, 段长) 序列。 */
    private fun feed(audio: FloatArray, chunkSize: Int): List<Pair<Long, Int>> {
        val segmenter = VadSegmenter(DeviceAsrFixtures.vadModel(context))
        return try {
            val out = mutableListOf<Pair<Long, Int>>()
            var offset = 0
            while (offset < audio.size) {
                val size = minOf(chunkSize, audio.size - offset)
                out += segmenter.push(audio.copyOfRange(offset, offset + size)).map { it.startSample to it.samples.size }
                offset += size
            }
            out += segmenter.finish().map { it.startSample to it.samples.size }
            out
        } finally {
            segmenter.release()
        }
    }

    @Test
    fun `release之后再喂要立刻报错`() {
        val segmenter = VadSegmenter(DeviceAsrFixtures.vadModel(context))
        segmenter.release()
        val outcome = runCatching { segmenter.push(FloatArray(512)) }
        assertTrue("重复使用已释放的 VAD 竟然没报错：${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is IllegalStateException)
        // release 幂等：管线异常收尾可能调两次，绝不能二次 free native 句柄
        runCatching { segmenter.release() }.onFailure { Assert.fail("第二次 release 不该抛：$it") }
    }

    @Test
    fun `主线程构造要直接拒绝`() {
        // 「推理不得在主线程」是 AGENTS.md §5 的硬规矩，靠构造函数里的 check 兜住
        val outcome = runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                VadSegmenter(DeviceAsrFixtures.vadModel(context)).release()
            }
        }
        assertTrue("主线程竟然能构造 VadSegmenter：${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `模型文件不存在要报人话而不是崩在native里`() {
        val missing = File(context.getExternalFilesDir(null), "nope/" + ModelCatalog.SILERO_VAD_MODEL_FILE)
        val outcome = runCatching { VadSegmenter(missing) }
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            "错误信息里要点名文件：${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull()?.message?.contains("VAD 模型文件不存在") == true,
        )
    }

    @Test
    fun `非16k的采样率要拒绝因为窗长是按16k定的`() {
        val outcome = runCatching {
            VadSegmenter(
                modelFile = DeviceAsrFixtures.vadModel(context),
                sampleRate = 8_000,
            ).release()
        }
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
    }

    private companion object {
        const val TAG = "VadSegmenterTest"
    }
}
