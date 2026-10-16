package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.conf.AudioFormats
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在**真设备、真 MP3、真 MediaCodec** 上跑一遍解码（Phase 6 第 3 项剩下的那一半）。
 *
 * 为什么必须是仪器化测试：`domain/asr` 那三个纯 Kotlin 类已经把算法在 JVM 里钉死了，
 * 但「这台机器的 MP3 解码器输出什么采样率、几个声道、单个缓冲多大、整段要多久」只有设备知道。
 * 猜错的后果不是崩，是**整条字幕时间线一起偏移**——肉眼最难发现的那类错。
 *
 * 素材是用户自己的音频，有版权，**不进仓库**：用 `adb push` 放进 App 自有的
 * external files 目录（读写自己的 external 目录不需要任何运行时权限，比给 App 加
 * `READ_MEDIA_AUDIO` 更干净）。缺素材时**直接 fail 并打印放文件的命令**，不用 `assumeTrue`
 * 静默跳过——跳过的测试等于没有测试，而这里失败只说明「机器上没放素材」，一眼能看懂。
 *
 * 早期这批用例曾发现 Phase 3~5 的「《三体》」素材其实是静音占位。现在静音反例改成测试自己生成的
 * 10 秒 PCM WAV：不依赖已丢失的外部文件，仍与真人语音形成正反对照。
 */
@RunWith(AndroidJUnit4::class)
class PcmDecoderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fixtureDir(): File =
        context.getExternalFilesDir(FIXTURE_DIR) ?: File(context.filesDir, FIXTURE_DIR).also { it.mkdirs() }

    private fun fixture(name: String): File {
        if (name == DeviceAsrFixtures.SILENT_WAV) {
            return DeviceAsrFixtures.fixture(name, context)
        }
        val file = File(fixtureDir(), name)
        if (!file.exists() || file.length() == 0L) {
            fail(
                "缺少测试素材：${file.path}（存在=${file.exists()}）。\n" +
                    "它是有版权的语音，不入库，请先放到设备上：\n" +
                    "  adb -s emulator-5554 push <宿主机上的 mp3> ${file.path}\n" +
                    "要求：**真有声音**的 MP3。书架那批《三体》素材不能拿来顶包——它们的帧体 99% 是 0x00，" +
                    "解出来就是全零（见本类那条静音对照用例）。"
            )
        }
        return file
    }

    /** 一次解码的全部观测量：日志与断言共用同一份数字，免得两处各说各话。 */
    private inner class Probe(val blockSize: Int) {
        var calls = 0
        var delivered = 0L
        var acceptedBlocks = 0
        var nextExpectedStart = 0L
        var contiguityBroken = -1L
        var peak = 0f
        var energy = 0.0
        var minAcceptedSize = Int.MAX_VALUE
        var maxAcceptedSize = 0

        fun onChunk(values: FloatArray, startSample: Long, accept: Boolean) {
            if (startSample != nextExpectedStart) contiguityBroken = startSample
            assertTrue("块长必须落在 1..$blockSize，实测 ${values.size}", values.size in 1..blockSize)
            if (accept) {
                acceptedBlocks++
                nextExpectedStart += values.size
                delivered += values.size
                minAcceptedSize = minOf(minAcceptedSize, values.size)
                maxAcceptedSize = maxOf(maxAcceptedSize, values.size)
            }
            for (value in values) {
                assertTrue("样本必须落在 [-1,1]，实测 $value", value in -1f..1f)
                peak = max(peak, abs(value))
                if (accept) energy += value.toDouble() * value.toDouble()
            }
            calls++
        }

        fun rms(): Double = if (delivered == 0L) 0.0 else sqrt(energy / delivered)
    }

    private data class Run(val summary: PcmDecodeSummary, val probe: Probe, val wallMs: Long, val fileBytes: Long)

    private fun run(name: String, stopAfterBlocks: Int = Int.MAX_VALUE, blockSize: Int = PcmDecoder.DEFAULT_BLOCK_SIZE): Run {
        val file = fixture(name)
        val probe = Probe(blockSize)
        val startedAt = SystemClock.elapsedRealtime()
        val summary = PcmDecoder(context, blockSize = blockSize).decode(Uri.fromFile(file)) { values, startSample ->
            val accept = probe.calls < stopAfterBlocks
            probe.onChunk(values, startSample, accept)
            accept
        }
        val wallMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val rtf = if (summary.durationMs > 0) wallMs.toDouble() / summary.durationMs else -1.0
        Log.i(
            TAG,
            "decode $name mime=${summary.mime} srcRate=${summary.sourceSampleRate} ch=${summary.channelCount} " +
                "containerMs=${summary.durationMs} outSamples=${summary.outputSamples} blocks=${probe.acceptedBlocks} " +
                "maxCodecBufferBytes=${summary.maxCodecBufferBytes} peak=${probe.peak} rms=${probe.rms()} " +
                "wallMs=$wallMs rtf=" + "%.4f".format(rtf) + " fileBytes=${file.length()}",
        )
        return Run(summary, probe, wallMs, file.length())
    }

    /** 解出来的样本数换算成毫秒，与容器时长的百分比偏差。 */
    private fun driftPct(summary: PcmDecodeSummary): Double {
        val impliedMs = summary.outputSamples * 1000 / AudioFormats.ASR_SAMPLE_RATE
        return abs(impliedMs - summary.durationMs).toDouble() / summary.durationMs * 100.0
    }

    @Test
    fun `真实素材解码：形态、时长对账、块连续、幅度都站得住`() {
        val run = run(FIXTURE_REAL)
        val summary = run.summary
        val probe = run.probe

        assertEquals("交付样本数必须与摘要一致", probe.delivered, summary.outputSamples)
        assertEquals("块必须首尾严格相接", -1L, probe.contiguityBroken)
        assertTrue("至少要出 10 个块，实测 ${probe.acceptedBlocks}", probe.acceptedBlocks >= 10)
        assertEquals("中途每块都必须是满块（只有末尾尾块例外）", PcmDecoder.DEFAULT_BLOCK_SIZE, probe.maxAcceptedSize)
        assertTrue("解码器必须上报输出采样率，实测 ${summary.sourceSampleRate}", summary.sourceSampleRate > 0)
        assertTrue("声道数应在 1..8，实测 ${summary.channelCount}", summary.channelCount in 1..8)
        assertTrue("容器时长必须可读，实测 ${summary.durationMs}", summary.durationMs > 0)
        assertEquals("没取消时不该有被拒收的块", probe.acceptedBlocks, probe.calls)
        assertTrue("mime 应是音频，实测 ${summary.mime}", summary.mime.startsWith("audio/"))

        // MP3 的容器时长来自 Xing 头或 CBR 估算，与真解出的样本数允许小偏差：
        // 所以这条先**量**出偏差，再由断言设一个明显宽于实测的上限
        val drift = driftPct(summary)
        Log.i(TAG, "时长对账 $FIXTURE_REAL: 解出 ${summary.outputSamples * 1000 / AudioFormats.ASR_SAMPLE_RATE}ms vs 容器 ${summary.durationMs}ms → 偏差 " + "%.2f".format(drift) + "%")
        assertTrue("样本数与容器时长偏差应 <2%，实测 ${"%.2f".format(drift)}%", drift < 2.0)

        // 读得到文件却解出静音，是最难看的假成功：峰值与均方根都必须像真声音
        assertTrue("真实音频不该是静音，实测 peak=${probe.peak}", probe.peak > 0.05f)
        assertTrue("真实音频不该是静音，实测 rms=${probe.rms()}", probe.rms() > 0.005)

        // 峰值内存证据：单个 codec 输出缓冲的上界，不随文件长度增长（NFR-05/07）
        assertTrue("单个 codec 缓冲不该超过 1 MB，实测 ${summary.maxCodecBufferBytes}", summary.maxCodecBufferBytes in 1..1_048_576)
        Log.i(TAG, "缓冲上界 ${summary.maxCodecBufferBytes} 字节 vs 文件 ${run.fileBytes} 字节 = " +
            "%.3f".format(summary.maxCodecBufferBytes.toDouble() / run.fileBytes) + " 倍")
    }

    /**
     * 反向对照：测试确定性生成的 10 秒 PCM WAV **本身就是静音**，解出全零是正确行为。
     * 这条存在有两个理由：
     * 1. 证明「peak=0」来自文件内容，不是解码链路把数据洗平了——真素材那条同时给出非零峰值。
     * 2. 长度对账与块连续性在两份素材上都要成立；静音文件的解码耗时也是有效样本（RTF 不靠内容）。
     */
    @Test
    fun `静音素材解出全零但长度照样对得上`() {
        val run = run(DeviceAsrFixtures.SILENT_WAV)
        assertEquals(0f, run.probe.peak, 0f)
        assertEquals(0.0, run.probe.rms(), 0.0)
        assertEquals(-1L, run.probe.contiguityBroken)
        assertEquals(run.probe.delivered, run.summary.outputSamples)
        assertTrue("静音也要按长度产出样本，实测 ${run.summary.outputSamples}", run.summary.outputSamples > 100_000)
        val drift = driftPct(run.summary)
        assertTrue("静音素材的时长也要对得上，偏差应 <2%，实测 ${"%.2f".format(drift)}%", drift < 2.0)
    }

    @Test
    fun `回调返回false就立刻停手已交付样本数仍然自洽`() {
        val run = run(FIXTURE_REAL, stopAfterBlocks = 3, blockSize = CANCEL_BLOCK_SIZE)
        assertTrue("必须标记为已取消", run.summary.cancelled)
        assertEquals("应当正好交付 3 块", 3, run.probe.acceptedBlocks)
        assertEquals("第 4 块被拒绝，不该再往下解", 4, run.probe.calls)
        assertEquals(3L * CANCEL_BLOCK_SIZE, run.summary.outputSamples)
        assertEquals(-1L, run.probe.contiguityBroken)
        assertTrue("取消也必须读得出采样率（它解过数据）", run.summary.sourceSampleRate > 0)
    }

    @Test
    fun `非音频文件要给人话错误，不许静默产出零样本`() {
        val notAudio = File(fixtureDir(), "pcm-fixture-not-audio.txt")
        notAudio.writeText("1\n00:00:01,000 --> 00:00:03,000\n这不是音频文件\n")
        val message = try {
            PcmDecoder(context).decode(Uri.fromFile(notAudio)) { _, _ -> true }
            error("文本文件被当成音频解出来了，decode 正常返回")
        } catch (e: PcmDecodeException) {
            e.message ?: ""
        } finally {
            assertTrue("临时文件应被清掉", notAudio.delete())
        }
        Log.i(TAG, "非音频文件错误信息：$message")
        assertTrue("错误信息要说清是打不开容器还是没有音轨，实测「$message」",
            message.contains("容器") || message.contains("解码") || message.contains("音轨"))
    }

    private companion object {
        const val TAG = "PcmDecoderTest"
        const val FIXTURE_DIR = "asr-fixtures"

        /** 真音频：2.38 MB 的一首歌（帧体只有 1.4% 是 0x00）。有版权，不入库，靠 adb push */
        const val FIXTURE_REAL = "pcm-fixture-real.mp3"

        const val CANCEL_BLOCK_SIZE = 1_024
    }
}
