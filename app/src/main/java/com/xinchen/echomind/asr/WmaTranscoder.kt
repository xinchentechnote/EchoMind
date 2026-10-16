package com.xinchen.echomind.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File

/**
 * WMA → M4A（AAC-LC）的导入时转码（D40）。
 *
 * **为什么转码而不是让播放器直接解**：ExoPlayer 走系统 MediaCodec，而 Android 没有 WMA 解码器
 * （`DeviceCodecProbeTest` 为证）；给 Media3 配 FFmpeg 渲染器要动播放器架构，而转录侧已经
 * 为了字幕时间必须自持解码。转码一次（实测几十倍速），播放与转录就都走在既有的、被测试覆盖的
 * m4a 链路上——这是最小改动面。
 *
 * 管线：`libwma` 解出交错 s16（源采样率/声道）→ MediaCodec AAC 编码 → MediaMuxer 写 .m4a。
 * 时间戳按「已写入样本数 × 1e6 / 采样率」推进，不读墙钟。
 *
 * 只能在非主线程调用；失败抛 [java.io.IOException]，半截的输出文件由调用方删除。
 */
class WmaTranscoder(private val context: Context, private val bitrate: Int = DEFAULT_BITRATE) {

    /** 返回转码后音频时长 ms；容器没给时长就用「总样本数 ÷ 采样率」换算（解码器逐样本数的，不是猜的） */
    fun transcodeToM4a(uri: Uri, output: File): Long {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("打不开 WMA 文件（$uri）：系统没有交出文件描述符")
        var handle = 0L
        val info = IntArray(3)
        try {
            handle = WmaJni.nativeOpen(pfd.fd, info)
            if (handle == 0L) throw java.io.IOException("打不开 WMA 容器（$uri）：libwma 返回失败")
            val (sampleRate, channels, containerDurationMs) = Triple(info[0], info[1], info[2].toLong())
            if (sampleRate <= 0 || channels !in 1..2) {
                throw java.io.IOException("不支持的 WMA 形态：${sampleRate}Hz × $channels 声道")
            }
            return transcode(handle, sampleRate, channels, containerDurationMs, output)
        } finally {
            if (handle != 0L) runCatching { WmaJni.nativeClose(handle) }
            runCatching { pfd.close() }
        }
    }

    private fun transcode(
        handle: Long,
        sampleRate: Int,
        channels: Int,
        containerDurationMs: Long,
        output: File,
    ): Long {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val pcm = ByteArray(READ_CHUNK_BYTES)
        var totalSamples = 0L
        try {
            var inputDone = false
            var sawEos = false
            while (!sawEos) {
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = encoder.getInputBuffer(inIndex)
                            ?: throw java.io.IOException("拿不到编码器输入缓冲")
                        inBuffer.clear()
                        if (inputDoneRequested(handle, pcm, bufferInfo)) {
                            encoder.queueInputBuffer(inIndex, 0, 0, samplesToUs(totalSamples, sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = bufferInfo.size
                            inBuffer.put(pcm, 0, n)
                            val sampleCount = n / (2 * channels)
                            val ptsUs = samplesToUs(totalSamples, sampleRate)
                            totalSamples += sampleCount
                            encoder.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                        }
                    }
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex < 0 -> break
                        else -> {
                            val outBuffer = encoder.getOutputBuffer(outIndex)
                                ?: throw java.io.IOException("拿不到编码器输出缓冲")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0 // csd 已由 outputFormat 交给 muxer，本体不再写一遍
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                        }
                    }
                }
            }
            val fromSamples = samplesToUs(totalSamples, sampleRate) / 1000
            // 容器给的时长（如 -1 未知）优先于样本换算；两者都取不到才真是未知
            return if (containerDurationMs > 0) containerDurationMs else fromSamples
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            muxer.release()
        }
    }

    /** 读一块 PCM；EOF 返回 true 时 [bufferInfo].size 无意义，调用方走 EOS 分支 */
    private fun inputDoneRequested(handle: Long, pcm: ByteArray, bufferInfo: MediaCodec.BufferInfo): Boolean {
        val n = WmaJni.nativeRead(handle, pcm)
        if (n < 0) throw java.io.IOException("WMA 解码中途失败（libwma 返回错误）")
        if (n == 0) return true
        bufferInfo.size = n
        return false
    }

    private fun samplesToUs(samples: Long, sampleRate: Int): Long = samples * 1_000_000L / sampleRate

    companion object {
        /** 128 kbps 对书解语音绰绰有余，与常见 m4a 书目同量级 */
        const val DEFAULT_BITRATE = 128_000

        /** 单次喂给编码器的 PCM 量：约 64 KB = 44.1kHz 双声道 ~186ms，编码器缓冲足够容纳 */
        const val READ_CHUNK_BYTES = 64 * 1024

        const val MAX_INPUT_SIZE = 64 * 1024
        private const val TIMEOUT_US = 10_000L
    }
}
