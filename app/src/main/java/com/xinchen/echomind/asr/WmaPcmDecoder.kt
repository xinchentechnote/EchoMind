package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.asr.PcmConversion
import com.xinchen.echomind.domain.asr.SincResampler

/**
 * WMA → 16 kHz 单声道 float 的流式解码（D40；Android 无 WMA 解码器，走自建 `libwma.so`）。
 *
 * 输出契约与 [PcmDecoder] 完全一致（同一个 [PcmChunkSink]、同一份 [PcmDecodeSummary]），
 * 下混与重采样同样交给 `domain/asr` 的纯 Kotlin——JNI 壳只负责「浮点平面 → 交错 s16le」。
 * 峰值内存：一次持有的只有 JNI 读取缓冲（64 KB）+ 解码器单帧（≤ [WmaJni] 壳里的 MAX_FRAME_BYTES 32 KB），
 * 与音频总长无关（NFR-05 的同一句承诺）。
 *
 * [decode] 只能在非主线程调用（AGENTS.md §5）；取消由 [PcmChunkSink] 返回 false 表达。
 * `close()` 成对：pfd 与 JNI 句柄都在 finally 里释放，且不吞掉真正的失败原因。
 */
class WmaPcmDecoder(
    private val targetSampleRate: Int = AudioFormats.ASR_SAMPLE_RATE,
    private val blockSize: Int = PcmDecoder.DEFAULT_BLOCK_SIZE,
) {

    fun decode(context: Context, uri: Uri, sink: PcmChunkSink): PcmDecodeSummary {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw PcmDecodeException("打不开音频文件（$uri）：系统没有交出文件描述符")
        var handle = 0L
        try {
            val info = IntArray(3)
            handle = WmaJni.nativeOpen(pfd.fd, info)
            if (handle == 0L) {
                throw PcmDecodeException("打不开 WMA 容器（$uri）：libwma 返回失败")
            }
            val (sourceSampleRate, channelCount, durationMs) = Triple(info[0], info[1], info[2].toLong())
            if (sourceSampleRate <= 0) {
                throw PcmDecodeException("WMA 解码器没报告输出采样率，无法换算字幕时间")
            }
            if (channelCount !in 1..2) {
                throw PcmDecodeException("不支持的 WMA 声道数：$channelCount（只处理单/双声道）")
            }
            return pump(handle, bufSize = READ_CHUNK_BYTES, sourceSampleRate, channelCount, durationMs, sink)
        } finally {
            if (handle != 0L) runCatching { WmaJni.nativeClose(handle) }
            runCatching { pfd.close() }
        }
    }

    private fun pump(
        handle: Long,
        bufSize: Int,
        sourceSampleRate: Int,
        channelCount: Int,
        durationMs: Long,
        sink: PcmChunkSink,
    ): PcmDecodeSummary {
        val accumulator = PcmDecoder.BlockAccumulator(blockSize)
        val resampler = SincResampler(sourceSampleRate, targetSampleRate)
        val buffer = ByteArray(bufSize)
        var maxReadBytes = 0

        while (!accumulator.stopped) {
            val n = WmaJni.nativeRead(handle, buffer)
            when {
                n < 0 -> throw PcmDecodeException("WMA 解码中途失败（libwma 返回错误）")
                n == 0 -> break
                else -> {
                    maxReadBytes = maxOf(maxReadBytes, n)
                    val mono = PcmConversion.pcm16LeToFloatMono(buffer, 0, n, channelCount)
                    accumulator.append(resampler.resample(mono, isFinal = false), sink)
                }
            }
        }
        if (!accumulator.stopped) {
            accumulator.append(resampler.resample(FloatArray(0), isFinal = true), sink)
            accumulator.flush(sink)
        }
        return PcmDecodeSummary(
            sourceSampleRate = sourceSampleRate,
            channelCount = channelCount,
            mime = "audio/x-ms-wma",
            durationMs = durationMs,
            outputSamples = accumulator.deliveredSamples,
            maxCodecBufferBytes = maxReadBytes,
            cancelled = accumulator.stopped,
        )
    }

    companion object {
        /** 64 KB：约 44.1kHz 双声道 186ms，一次 JNI 往返的量级与转码器一致 */
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}
