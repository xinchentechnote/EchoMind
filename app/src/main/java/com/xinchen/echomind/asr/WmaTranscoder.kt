package com.xinchen.echomind.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** 转码被调用方的取消位叫停（不是失败）；输出文件是半截的，由调用方删除。 */
class WmaTranscodeCancelledException : Exception("WMA 转码被取消")

/**
 * WMA 转码（D40/D44）：目标 M4A（AAC-LC，导入默认）或 MP3（LAME；转录管线固定走它，D44）。
 *
 * **为什么转码而不是让播放器直接解**：ExoPlayer 走系统 MediaCodec，而 Android 没有 WMA 解码器
 * （`DeviceCodecProbeTest` 为证）；给 Media3 配 FFmpeg 渲染器要动播放器架构，而转录侧已经
 * 为了字幕时间必须自持解码。转码一次（实测几十倍速），播放与转录就都走在既有的、被测试覆盖的
 * MediaCodec 链路上——这是最小改动面。
 *
 * 管线：`libwma` 解出交错 s16（源采样率/声道）→ MediaCodec AAC 编码 → MediaMuxer 写 .m4a；
 * 或 → LAME → .mp3。时间戳按「已写入样本数 × 1e6 / 采样率」推进，不读墙钟。
 *
 * 只能在非主线程调用；失败抛 [java.io.IOException]，半截的输出文件由调用方删除。
 * libwma 对单个坏包是跳过而不是中止（`wma_jni.c` 文件头纪律 2），跳过了几个在收尾写 logcat——
 * 「半本」要可见，不能静默。
 */
class WmaTranscoder(private val context: Context, private val bitrate: Int = DEFAULT_BITRATE) {

    /**
     * WMA → MP3。返回转码后时长 ms（WMA 容器值；mp3 流本身不带精确容器时长），容器没给就是 0。
     *
     * @param isCancelled 每解一块（≈186 ms 音频）检查一次，为 true 抛 [WmaTranscodeCancelledException]
     * @param onPosition 已解出的音频位置与容器时长（时长未知为 -1），每块回调一次，节流由调用方做
     */
    fun transcodeToMp3(
        uri: Uri,
        output: File,
        isCancelled: () -> Boolean = { false },
        onPosition: (decodedMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    ): Long {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("打不开 WMA 文件（$uri）：系统没有交出文件描述符")
        var wmaHandle = 0L
        var mp3Handle = 0L
        val info = IntArray(3)
        try {
            wmaHandle = WmaJni.nativeOpen(pfd.fd, info)
            if (wmaHandle == 0L) throw java.io.IOException("打不开 WMA 容器（$uri）：libwma 返回失败")
            val (sampleRate, channels, containerDurationMs) = Triple(info[0], info[1], info[2].toLong())
            if (sampleRate <= 0 || channels !in 1..2) {
                throw java.io.IOException("不支持的 WMA 形态：${sampleRate}Hz × $channels 声道")
            }
            mp3Handle = WmaJni.nativeMp3Open(sampleRate, channels, MP3_BITRATE_KBPS)
            if (mp3Handle == 0L) throw java.io.IOException("MP3 编码器初始化失败")
            var samplesPerChannelTotal = 0L
            FileOutputStream(output).use { out ->
                val pcm = ByteArray(READ_CHUNK_BYTES)
                // LAME 要求输出缓冲 ≥ 1.25 × 每声道样本数 + 7200；单声道一块最多 32768 样本 → 48 KB，留足
                val mp3Buf = ByteArray(READ_CHUNK_BYTES * 2)
                while (true) {
                    if (isCancelled()) throw WmaTranscodeCancelledException()
                    val n = WmaJni.nativeRead(wmaHandle, pcm)
                    if (n < 0) throw java.io.IOException("WMA 解码失败（libwma 一个样本都没解出来）")
                    if (n == 0) break
                    val samplesPerChannel = n / (2 * channels)
                    val written = WmaJni.nativeMp3Write(mp3Handle, pcm, samplesPerChannel, mp3Buf)
                    if (written < 0) throw java.io.IOException("MP3 编码失败")
                    if (written > 0) out.write(mp3Buf, 0, written)
                    samplesPerChannelTotal += samplesPerChannel
                    onPosition(samplesPerChannelTotal * 1000 / sampleRate, containerDurationMs)
                }
                val flushed = WmaJni.nativeMp3Flush(mp3Handle, mp3Buf)
                if (flushed > 0) out.write(mp3Buf, 0, flushed)
            }
            logSkippedPackets(wmaHandle, uri, samplesPerChannelTotal * 1000 / sampleRate, containerDurationMs)
            return if (containerDurationMs > 0) containerDurationMs else samplesPerChannelTotal * 1000 / sampleRate
        } finally {
            if (mp3Handle != 0L) runCatching { WmaJni.nativeMp3Close(mp3Handle) }
            if (wmaHandle != 0L) runCatching { WmaJni.nativeClose(wmaHandle) }
            runCatching { pfd.close() }
        }
    }

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
            val durationMs = transcode(handle, sampleRate, channels, containerDurationMs, output)
            logSkippedPackets(handle, uri, durationMs, containerDurationMs)
            return durationMs
        } finally {
            if (handle != 0L) runCatching { WmaJni.nativeClose(handle) }
            runCatching { pfd.close() }
        }
    }

    private fun logSkippedPackets(handle: Long, uri: Uri, decodedMs: Long, containerDurationMs: Long) {
        val skipped = WmaJni.nativeBadPackets(handle)
        if (skipped > 0) {
            Log.w(TAG, "WMA 转码跳过了 $skipped 个坏包（$uri）：解出 $decodedMs ms，容器时长 $containerDurationMs ms；产物是能解出来的那部分")
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
        if (n < 0) throw java.io.IOException("WMA 解码失败（libwma 一个样本都没解出来）")
        if (n == 0) return true
        bufferInfo.size = n
        return false
    }

    private fun samplesToUs(samples: Long, sampleRate: Int): Long = samples * 1_000_000L / sampleRate

    companion object {
        private const val TAG = "EchoMindWma"

        /** 128 kbps 对书解语音绰绰有余，与常见 m4a 书目同量级 */
        const val DEFAULT_BITRATE = 128_000

        /** mp3 输出码率：与 m4a 同量级 */
        const val MP3_BITRATE_KBPS = 128

        /** 单次喂给编码器的 PCM 量：约 64 KB = 44.1kHz 双声道 ~186ms，编码器缓冲足够容纳 */
        const val READ_CHUNK_BYTES = 64 * 1024

        const val MAX_INPUT_SIZE = 64 * 1024
        private const val TIMEOUT_US = 10_000L
    }
}
