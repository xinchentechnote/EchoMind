package com.xinchen.echomind.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 时间轴探针（字幕比语音快的用户报告，2026-10-16）：字幕时间轴用**解码样本计数**，
 * 播放时间轴（ExoPlayer position）用**容器 PTS**。两者若有分歧且随时间增长，
 * 字幕就会越到后面越提前。这条测试把「第 N 个输出样本 ↔ 它的 PTS」逐段量出来。
 *
 * 判据：每个采样点上 |ptsUs - samples/rate*1e6| 应在毫秒级内不随时间增长；
 * 若线性增长（斜率 = 漂移速率），机制即坐实，锚点必须换 PTS。
 */
@RunWith(AndroidJUnit4::class)
class PcmTimelineProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 逐段量解码样本数与容器PTS的对应关系() {
        val uri = Uri.fromFile(DeviceAsrFixtures.fixture(context = context))
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }!!
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var sawEos = false
            var samples = 0L
            var channels = 1
            var rate = 44_100
            var lastLogAtSamples = 0L
            var maxAbsDriftUs = 0L
            var driftAtEndUs = 0L
            var inputPtsUs = 0L
            var lastInputPtsUs = -1L
            var maxInputStepUs = 0L

            while (!sawEos) {
                if (!inputDone) {
                    while (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(0)
                        if (inIndex < 0) break
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            if (lastInputPtsUs >= 0) {
                                maxInputStepUs = maxOf(maxInputStepUs, pts - lastInputPtsUs)
                            }
                            lastInputPtsUs = pts
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        val pcmBytes = info.size
                        val sampleCount = pcmBytes / (2 * channels)
                        if (sampleCount > 0 && info.presentationTimeUs > 0) {
                            val expectedUs = samples * 1_000_000L / rate
                            val drift = info.presentationTimeUs - expectedUs
                            if (samples - lastLogAtSamples >= 30L * rate) {
                                Log.i(TAG, "samples=$samples（${samples / rate}s） pts=${info.presentationTimeUs}us 期望=${expectedUs}us 漂移=${drift / 1000f}ms")
                                lastLogAtSamples = samples
                            }
                            maxAbsDriftUs = maxOf(maxAbsDriftUs, Math.abs(drift))
                            driftAtEndUs = drift
                        }
                        samples += sampleCount
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                    }
                }
            }
            Log.i(
                TAG,
                "总结：输出 $samples 样本（${samples / rate}s，$rate Hz/$channels ch），" +
                    "末段漂移 ${driftAtEndUs / 1000f} ms，全程最大 |漂移| ${maxAbsDriftUs / 1000f} ms，" +
                    "最大单帧输入 PTS 步进 ${maxInputStepUs / 1000f} ms，容器 PTS 末端 $lastInputPtsUs us",
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "PcmTimelineProbe"
    }
}
