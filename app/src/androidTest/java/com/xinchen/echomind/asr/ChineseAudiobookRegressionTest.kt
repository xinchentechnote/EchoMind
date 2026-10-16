package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.asr.AsrChunkPlanner
import com.xinchen.echomind.domain.asr.SentenceSplitter
import com.xinchen.echomind.domain.model.RecognizedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 当前产品范围（D42）的真实素材回归：用户提供的中文书解前两分钟必须走生产同款
 * `PcmDecoder -> Silero VAD -> Paraformer -> SentenceSplitter`，并产出可用的中文句级字幕。
 *
 * 这不是 CER 测试：素材尚无人工逐字稿，所以这里只能钉住语种、时间戳、切句和内容形状，不能据此声称
 * “识别准确率达到 X%”。有逐字稿后应把本测试升级为按同一时间窗计算 CER。
 */
@RunWith(AndroidJUnit4::class)
class ChineseAudiobookRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `中文书解前两分钟产出以汉字为主且时间单调的句级字幕`() {
        val fixture = DeviceAsrFixtures.fixture(DeviceAsrFixtures.USER_BOOK_MP3, context)
        val segmenter = VadSegmenter(DeviceAsrFixtures.vadModel(context))
        val engine = SherpaOnnxEngine.open(DeviceAsrFixtures.modelRoot(context), ModelCatalog.paraformer)
        val recognized = mutableListOf<RecognizedSegment>()
        var decodedEndSample = 0L
        var chunkCount = 0
        var recognizeWallMs = 0L

        fun recognize(utterance: VadUtterance) {
            val tokens = AsrChunkPlanner.plan(listOf(utterance.span)).flatMap { chunk ->
                val from = (chunk.startSample - utterance.startSample).toInt()
                val startedAt = SystemClock.elapsedRealtime()
                val result = engine.recognize(
                    chunk,
                    utterance.samples.copyOfRange(from, from + chunk.sampleCount),
                )
                recognizeWallMs += SystemClock.elapsedRealtime() - startedAt
                chunkCount++
                result.tokens
            }
            if (tokens.isNotEmpty()) {
                recognized += RecognizedSegment(
                    startMs = AsrChunkPlanner.msOf(utterance.startSample, AudioFormats.ASR_SAMPLE_RATE),
                    endMs = AsrChunkPlanner.msOf(utterance.endSample, AudioFormats.ASR_SAMPLE_RATE),
                    tokens = tokens,
                )
            }
        }

        try {
            val limit = AUDIO_SECONDS.toLong() * AudioFormats.ASR_SAMPLE_RATE
            PcmDecoder(context).decode(Uri.fromFile(fixture)) { samples, startSample ->
                decodedEndSample = startSample + samples.size
                segmenter.push(samples).forEach(::recognize)
                decodedEndSample < limit
            }
            segmenter.finish().forEach(::recognize)
        } finally {
            segmenter.release()
            engine.release()
        }

        val drafts = SentenceSplitter.split(recognized)
        val tokens = recognized.flatMap { it.tokens }
        val modelText = tokens.joinToString(separator = "") { it.token }
        val content = modelText.filter(Char::isLetterOrDigit)
        val hanChars = content.count(::isHan)
        val hanRatio = if (content.isEmpty()) 0.0 else hanChars.toDouble() / content.length
        val decodedMs = AsrChunkPlanner.msOf(decodedEndSample, AudioFormats.ASR_SAMPLE_RATE)
        val sentenceChars = drafts.sumOf { draft -> draft.text.count(Char::isLetterOrDigit) }
        val averageSentenceChars = if (drafts.isEmpty()) 0.0 else sentenceChars.toDouble() / drafts.size

        Log.i(
            TAG,
            "中文听书回归：解码 ${decodedMs}ms，${recognized.size} 个语音段 / $chunkCount 块 / " +
                "${tokens.size} token -> ${drafts.size} 句；汉字占比=${"%.3f".format(hanRatio)}，" +
                "平均每句=${"%.1f".format(averageSentenceChars)} 字，识别 wall=${recognizeWallMs}ms",
        )
        drafts.take(20).forEachIndexed { index, draft ->
            Log.i(TAG, "句$index ${draft.startMs}~${draft.endMs}「${draft.text}」")
        }

        assertTrue("没有识别出任何语音段", recognized.isNotEmpty())
        assertTrue("没有识别出任何带时间戳的 token", tokens.isNotEmpty())
        assertTrue("没有形成句级字幕", drafts.isNotEmpty())
        assertTrue("两分钟书解只得到 ${content.length} 个有效字符：$modelText", content.length >= MIN_CONTENT_CHARS)
        assertTrue("中文书解输出的汉字占比只有 $hanRatio：$modelText", hanRatio >= MIN_HAN_RATIO)
        assertTrue("平均每句只有 $averageSentenceChars 字，退化成逐字字幕", averageSentenceChars >= MIN_AVERAGE_SENTENCE_CHARS)

        assertTrue("token 时间倒挂", tokens.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue("字幕时间倒挂", drafts.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue("字幕互相重叠", drafts.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        drafts.forEach { draft ->
            assertTrue("字幕时长必须为正：$draft", draft.endMs > draft.startMs)
            assertTrue("字幕越过实际解码窗口 ${decodedMs}ms：$draft", draft.endMs <= decodedMs)
            assertTrue("字幕为空：$draft", draft.text.isNotBlank())
            assertTrue(
                "单句超过 ${AudioFormats.ASR_MAX_SENTENCE_CHARS} 字：$draft",
                draft.text.count(Char::isLetterOrDigit) <= AudioFormats.ASR_MAX_SENTENCE_CHARS,
            )
        }

        // 切句只允许重排与补展示标点，不得改写模型给出的正文。
        assertEquals(content, drafts.joinToString(separator = "") { it.text }.filter(Char::isLetterOrDigit))
    }

    private fun isHan(char: Char): Boolean =
        Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

    private companion object {
        const val TAG = "ChineseBookRegression"
        const val AUDIO_SECONDS = 120
        const val MIN_CONTENT_CHARS = 100
        const val MIN_HAN_RATIO = 0.90
        const val MIN_AVERAGE_SENTENCE_CHARS = 3.0
    }
}
