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
 * WMA 解码与转码的设备端到端（D40）。
 *
 * 素材是仓库 `test-data/` 里用户提供的 34 分钟中文书解 WMA。容器时长、设备解码后的 16 kHz 样本数、
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

        assertEquals(44_100, summary.sourceSampleRate)
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

    private companion object {
        const val TAG = "WmaDecodeTest"
    }
}
