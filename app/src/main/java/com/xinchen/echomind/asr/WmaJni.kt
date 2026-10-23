package com.xinchen.echomind.asr

/**
 * libwma.so 的 JNI 面（`app/src/main/cpp/wma_jni.c`，FFmpeg 6.1.1 最小裁剪 + LAME 3.100，D40/D44）。
 *
 * 解码侧三个原语，形状刻意与 `PcmDecoder` 对齐：
 * - [nativeOpen] 在一个**已打开的文件描述符**上建解码上下文（自定义 AVIO 直读，绝不重开路径——
 *   SAF 的 fd 走 `/proc/self/fd` 会被 scoped storage 拒，实测踩过），返回句柄（0 = 打不开），
 *   `outInfo` 填 `[采样率, 声道数, 时长ms（未知 -1）]`；fd 的所有权在 Kotlin 侧（pfd），C 不 close；
 * - [nativeRead] 读交错 s16le PCM，返回 >0 = 本次字节量、0 = EOF、-1 = 错误。
 *   **单个坏包不算错误**：C 侧记数后跳过继续解（老素材中间有坏包是常态），只有整条流一个样本都没解出
 *   才返回 -1；跳过了多少包由 [nativeBadPackets] 读出来写日志，让「半本」这件事可见而不是被静默；
 * - [nativeClose] 释放；`release()` 成对原则（AGENTS.md §5）由调用方的 finally 负责。
 *
 * 只能在非主线程调用（与 MediaCodec 路径同一条纪律，AGENTS.md §5）。
 */
object WmaJni {
    init {
        System.loadLibrary("wma")
    }

    external fun nativeOpen(fd: Int, outInfo: IntArray): Long

    external fun nativeRead(handle: Long, out: ByteArray): Int

    /** 到目前为止被跳过的坏包数（诊断用，不参与控制流） */
    external fun nativeBadPackets(handle: Long): Int

    external fun nativeClose(handle: Long)

    // ── MP3 编码（LAME，转码目标的 mp3 支路）──────────────────────────────

    /** 建 MP3 编码器；返回句柄（0 = 失败）。[bitrateKbps] 常用 128。 */
    external fun nativeMp3Open(sampleRate: Int, channels: Int, bitrateKbps: Int): Long

    /**
     * 编码一块交错 s16le PCM。注意 [samples] 是**每声道样本数**（= pcm 字节数 / 2 / 声道数）。
     * 单声道在 C 侧走 `lame_encode_buffer`（交错版只认双声道）。
     * 返回写入 [out] 的 mp3 字节数（≥0），-1 = 错误。[out] 至少要 `1.25 × samples + 7200` 字节（LAME 的要求）。
     */
    external fun nativeMp3Write(handle: Long, pcm: ByteArray, samples: Int, out: ByteArray): Int

    /** 冲刷编码器尾部，返回最后的 mp3 字节数（≥0）。调用后句柄作废，须 [nativeMp3Close]。 */
    external fun nativeMp3Flush(handle: Long, out: ByteArray): Int

    external fun nativeMp3Close(handle: Long)
}
