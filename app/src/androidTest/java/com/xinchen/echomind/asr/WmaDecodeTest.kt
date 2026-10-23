package com.xinchen.echomind.asr

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WMA 解码与转码的设备端到端（D40/D44）。
 *
 * 素材是真歌的 WMA 版（`test-data/fixtures/real148.wma`，150 s）。容器时长、设备解码后的 16 kHz 样本数、
 * 转码产物时长三方必须对得上；再检查不是静音，避免“文件读完但没有音频”的假成功。
 */
@RunWith(AndroidJUnit4::class)
class WmaDecodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 解码wma给出正确形态与样本数() {
        val firstChunk = mutableListOf<FloatArray>()
        var total = 0L
        val summary = WmaPcmDecoder().decode(
            context,
            Uri.fromFile(DeviceAsrFixtures.fixture(DeviceAsrFixtures.REAL_WMA, context)),
        ) { samples, _ ->
            if (firstChunk.isEmpty()) firstChunk += samples
            total += samples.size
            true
        }

        assertEquals(32_000, summary.sourceSampleRate)
        assertEquals(2, summary.channelCount)
        assertEquals("audio/x-ms-wma", summary.mime)
        assertTrue(
            "容器时长 ${summary.durationMs} 不在预期内",
            summary.durationMs in (DeviceAsrFixtures.REAL_WMA_DURATION_MS - 500)..(DeviceAsrFixtures.REAL_WMA_DURATION_MS + 500),
        )
        val expected = summary.durationMs * 16_000 / 1_000
        assertTrue(
            "16k 输出样本 ${summary.outputSamples} 与容器时长换算值 $expected 偏离超过容差",
            Math.abs(summary.outputSamples - expected) < 4_000,
        )
        assertTrue("解出来是静音——解码没真正工作", firstChunk.single().any { it != 0f })
        Log.i(
            TAG,
            "WMA 解码：${summary.outputSamples} 样本（参考≈$expected），单次读取上界 ${summary.maxCodecBufferBytes} B",
        )
    }

    @Test
    fun 转码产物能被系统读取且时长一致() {
        val output = File(context.cacheDir, "wma-transcode-test.m4a")
        output.delete()
        try {
            val durationMs = WmaTranscoder(context).transcodeToM4a(
                Uri.fromFile(DeviceAsrFixtures.fixture(DeviceAsrFixtures.REAL_WMA, context)),
                output,
            )
            assertTrue("转码时长 $durationMs 不在预期内", durationMs in (DeviceAsrFixtures.REAL_WMA_DURATION_MS - 500)..(DeviceAsrFixtures.REAL_WMA_DURATION_MS + 500))
            assertTrue("转码产物不存在", output.isFile)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                assertTrue("转码产物里没有音轨", format != null)
                assertEquals("audio/mp4a-latm", format!!.getString(MediaFormat.KEY_MIME))
                val containerMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1
                assertTrue(
                    "产物时长 $containerMs 与源 $durationMs 不符",
                    Math.abs(containerMs - durationMs) < 1_000,
                )
                Log.i(TAG, "转码产物：${output.length()} B，${format.getString(MediaFormat.KEY_MIME)}，${containerMs} ms")
            } finally {
                extractor.release()
            }
        } finally {
            output.delete()
        }
    }

    /** D44：转录管线走的是 mp3 支路（LAME）。产物必须是系统 MediaExtractor 认得的 mp3，位置回调要单调且走到结尾。 */
    @Test
    fun 转成mp3的产物能被系统读取且位置回调走到结尾() {
        val output = File(context.cacheDir, "wma-transcode-test.mp3")
        output.delete()
        val positions = mutableListOf<Long>()
        var reportedDuration = -2L
        try {
            val durationMs = WmaTranscoder(context).transcodeToMp3(
                Uri.fromFile(DeviceAsrFixtures.fixture(DeviceAsrFixtures.REAL_WMA, context)),
                output,
                onPosition = { decodedMs, containerMs ->
                    positions += decodedMs
                    reportedDuration = containerMs
                },
            )
            assertTrue("转码时长 $durationMs 不在预期内", durationMs in (DeviceAsrFixtures.REAL_WMA_DURATION_MS - 500)..(DeviceAsrFixtures.REAL_WMA_DURATION_MS + 500))
            assertTrue("转码产物不存在或为空", output.isFile && output.length() > 100_000)
            assertTrue("位置回调一次都没来", positions.isNotEmpty())
            assertTrue("位置必须单调：${positions.take(5)}…", positions.zipWithNext().all { (a, b) -> b >= a })
            assertTrue("最后一次位置 ${positions.last()} 没走到结尾 $durationMs", Math.abs(positions.last() - durationMs) < 1_000)
            assertEquals(durationMs, reportedDuration)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                assertTrue("mp3 产物里没有音轨", format != null)
                assertEquals("audio/mpeg", format!!.getString(MediaFormat.KEY_MIME))
                val containerMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1
                assertTrue("mp3 产物时长 $containerMs 与源 $durationMs 不符", Math.abs(containerMs - durationMs) < 1_500)
                // 协调器就是靠这个读分母的：mp3 必须报得出时长，否则进度条画不出百分比
                assertTrue(PcmDecoder.containerDurationMs(context, Uri.fromFile(output)) > 0)
                Log.i(TAG, "mp3 产物：${output.length()} B，${containerMs} ms，位置回调 ${positions.size} 次")
            } finally {
                extractor.release()
            }
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val TAG = "WmaDecodeTest"
    }
}
