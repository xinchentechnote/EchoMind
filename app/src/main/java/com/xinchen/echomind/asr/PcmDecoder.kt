package com.xinchen.echomind.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.asr.PcmConversion
import com.xinchen.echomind.domain.asr.SincResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一次解码的实测摘要。所有数字都来自这次真解码，不是估算。
 *
 * @param durationMs 容器给的时长（微秒换算），没给就是 -1 —— 不用「字节数 ÷ 码率」去猜
 * @param maxCodecBufferBytes 单个 MediaCodec 输出缓冲的最大字节数，这是「峰值内存与音频总长无关」的直接证据
 */
data class PcmDecodeSummary(
    val sourceSampleRate: Int,
    val channelCount: Int,
    val mime: String,
    val durationMs: Long,
    val outputSamples: Long,
    val maxCodecBufferBytes: Int,
    val cancelled: Boolean,
)

class PcmDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 交给调用方的一个 16 kHz 单声道块。返回 false 表示要提前结束（取消位）。
 *
 * @param startSample 这块在整条时间线上的**绝对**起始样本（16k 栅格），毫秒 = `startSample * 1000 / 16000`
 */
fun interface PcmChunkSink {
    fun onChunk(samples: FloatArray, startSample: Long): Boolean
}

/**
 * MP3 → 16 kHz 单声道 float 的流式解码（`docs/asr-design.md` §5 的第 2~4 步）。
 *
 * 为什么用框架的 `MediaExtractor` + `MediaCodec` 而不是 Media3：实测 Media3 1.11.1 的 `media3-decoder`
 * 只有 `Buffer/Decoder/SimpleDecoder`、**没有** `MediaDecoder`，`media3-common` 里也没有重采样器
 * （决策与代价见 `docs/architecture.md` D26）。MP3 解码器是 Android 出厂必带项，不引新依赖。
 *
 * 三条形状约束：
 * - 同步模式逐缓冲走，每次只持有一个 codec 输出缓冲 + 一个 ≤ [blockSize] 的累加块，峰值内存与音频总长无关（NFR-05/07）。
 * - 声道混合与采样率转换不在这里做，交给 `domain/asr` 的两个纯 Kotlin 类，好让它们留在 JVM 单测里。
 * - 只能在非主线程调用（AGENTS.md §5）；取消由 [PcmChunkSink] 返回 false 表达，本类不碰协程也不碰线程。
 */
class PcmDecoder(
    private val context: Context,
    private val targetSampleRate: Int = AudioFormats.ASR_SAMPLE_RATE,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
) {

    init {
        require(targetSampleRate > 0) { "targetSampleRate 必须为正：$targetSampleRate" }
        require(blockSize > 0) { "blockSize 必须为正：$blockSize" }
    }

    fun decode(uri: Uri, sink: PcmChunkSink): PcmDecodeSummary {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw PcmDecodeException("打不开音频容器（$uri）：${e.javaClass.simpleName} ${e.message}", e)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw PcmDecodeException("容器里没有音轨（共 ${extractor.trackCount} 条）")
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "audio/??"
            extractor.selectTrack(trackIndex)

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw PcmDecodeException("这台机器没有 $mime 的解码器：${e.message}", e)
            }
            try {
                codec.configure(trackFormat, null, null, 0)
            } catch (e: Exception) {
                throw PcmDecodeException("解码器拒绝该格式（$mime）：${e.javaClass.simpleName} ${e.message}", e)
            }
            codec.start()
            return pump(extractor, codec, mime, sink)
        } finally {
            // stop/release 成对，且都不许把真正的失败原因盖掉（AGENTS.md §5）
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, mime: String, sink: PcmChunkSink): PcmDecodeSummary {
        val info = MediaCodec.BufferInfo()
        val accumulator = BlockAccumulator(blockSize)
        var inputDone = false
        var sawEos = false
        var stalledRounds = 0
        var maxBufferBytes = 0
        var sourceSampleRate = 0
        var channelCount = 0
        var pending = ByteArray(0)
        var resampler: SincResampler? = null

        while (!sawEos && !accumulator.stopped) {
            // 贪心喂入：只要 codec 有空输入缓冲就一直喂。先前的「一进一出」会让解码器经常没活干，
            // 每轮只能靠 dequeueOutputBuffer 的超时干等（实测每轮 2.4 ms、整段 RTF 0.19）
            var fed = false
            if (!inputDone) {
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val inBuffer: ByteBuffer = codec.getInputBuffer(inIndex)
                        ?: throw PcmDecodeException("拿不到输入缓冲（index=$inIndex）")
                    val size = extractor.readSampleData(inBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    fed = true
                }
            }

            var drained = false
            while (true) {
                // 本轮有进展就非阻塞继续取，一停就退出内层、回到上面把输入再喂一轮
                val outIndex = codec.dequeueOutputBuffer(info, if (fed || drained) 0 else TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val read = readFormat(codec.outputFormat)
                        sourceSampleRate = read.first
                        channelCount = read.second
                        resampler = SincResampler(sourceSampleRate, targetSampleRate)
                    }

                    outIndex < 0 -> break // 只剩已弃用的 INFO_OUTPUT_BUFFERS_CHANGED(-3)：忽略即可，按 index 取缓冲依然有效

                    else -> {
                        val sampler = resampler ?: run {
                            // 少数实现不发 FORMAT_CHANGED，形态直接体现在 outputFormat 上
                            val read = readFormat(codec.outputFormat)
                            sourceSampleRate = read.first
                            channelCount = read.second
                            SincResampler(sourceSampleRate, targetSampleRate).also { resampler = it }
                        }
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            maxBufferBytes = maxOf(maxBufferBytes, info.size)
                            val buffer = codec.getOutputBuffer(outIndex)
                                ?: throw PcmDecodeException("拿不到输出缓冲（index=$outIndex）")
                            val bytes = ByteArray(pending.size + info.size)
                            System.arraycopy(pending, 0, bytes, 0, pending.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            buffer.get(bytes, pending.size, info.size)
                            val usable = bytes.size - PcmConversion.leftoverBytes(bytes.size, channelCount)
                            pending = bytes.copyOfRange(usable, bytes.size)
                            val mono = PcmConversion.pcm16LeToFloatMono(bytes, 0, usable, channelCount)
                            // 中途一律 isFinal=false，末尾单独一次空输入 final 调用来抽干尾巴——
                            // 这个调用形状由 SincResamplerTest 钉住（分块+空尾 == 一次性）
                            accumulator.append(sampler.resample(mono, isFinal = false), sink)
                        }
                        sawEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        drained = true
                    }
                }
            }

            if (fed || drained) stalledRounds = 0 else if (++stalledRounds > MAX_STALLED_ROUNDS) {
                throw PcmDecodeException("输入与输出连续 $MAX_STALLED_ROUNDS 轮都不动，放弃（这台设备的解码器卡住了）")
            }
        }

        if (!accumulator.stopped) {
            val sampler = resampler
                ?: throw PcmDecodeException("解码器一个输出缓冲都没给（$mime），没有可换算时间的采样率")
            accumulator.append(sampler.resample(FloatArray(0), isFinal = true), sink)
            accumulator.flush(sink)
        }
        return PcmDecodeSummary(
            sourceSampleRate = sourceSampleRate,
            channelCount = channelCount,
            mime = mime,
            durationMs = durationMs(extractor),
            outputSamples = accumulator.deliveredSamples,
            maxCodecBufferBytes = maxBufferBytes,
            cancelled = accumulator.stopped,
        )
    }

    /**
     * 读解码器输出形态。MP3 的 track format 常常不带 sampleRate/channelCount，真实形态只在输出侧；
     * 拿不到采样率就必须停——所有字幕时间都要靠它换算，猜一个值等于把整条时间线歪掉。
     */
    private fun readFormat(format: MediaFormat): Pair<Int, Int> {
        if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            throw PcmDecodeException("解码器没报告输出采样率（$format），无法换算字幕时间")
        }
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
            // 32-bit float 的缓冲按 16-bit 读会得到听起来像噪声的乱码，宁可当场说清
            throw PcmDecodeException("不支持的输出编码 pcm-encoding=$encoding（只处理 16-bit PCM）")
        }
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }.coerceAtLeast(1)
        return format.getInteger(MediaFormat.KEY_SAMPLE_RATE) to channels
    }

    private fun durationMs(extractor: MediaExtractor): Long = runCatching {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return -1L
        val format = extractor.getTrackFormat(track)
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1L
    }.getOrDefault(-1L)

    /** 把 float 流切成固定上限的块交给 sink；[stopped] 一旦置真就不再推进计数，绝对起始样本因此始终正确。 */
    private class BlockAccumulator(val blockSize: Int) {
        private val buffer = FloatArray(blockSize)
        private var filled = 0
        var deliveredSamples = 0L
            private set
        var stopped = false
            private set

        fun append(values: FloatArray, sink: PcmChunkSink) {
            var offset = 0
            while (offset < values.size && !stopped) {
                val take = minOf(blockSize - filled, values.size - offset)
                System.arraycopy(values, offset, buffer, filled, take)
                filled += take
                offset += take
                if (filled == blockSize) flush(sink)
            }
        }

        fun flush(sink: PcmChunkSink) {
            if (filled == 0) return
            if (sink.onChunk(buffer.copyOf(filled), deliveredSamples)) {
                deliveredSamples += filled
                filled = 0
            } else {
                stopped = true
                filled = 0
            }
        }
    }

    companion object {
        /** 256 ms @16 kHz。太小则回调过密，太大就把「峰值内存受控」这句话变虚 */
        const val DEFAULT_BLOCK_SIZE = 4_096

        /** 单次 dequeue 等待上限。太长会让取消响应变慢，太短会白白空转 */
        private const val TIMEOUT_US = 10_000L

        /**
         * 解码器两头都不动的兜底上限。一轮至少等 [TIMEOUT_US]，所以 3,000 轮 ≈ 30 秒毫无进展就放弃——
         * 真走到这一步是设备/解码器的问题，但不能让它把转录任务无限挂住。
         */
        const val MAX_STALLED_ROUNDS = 3_000
    }
}
