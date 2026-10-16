package com.xinchen.echomind.asr

/**
 * libwma.so 的 JNI 面（`app/src/main/cpp/wma_jni.c`，FFmpeg 6.1.1 最小裁剪，D40）。
 *
 * 三个原语，形状刻意与 `PcmDecoder` 对齐：
 * - [nativeOpen] 在一个**已打开的文件描述符**上建解码上下文（自定义 AVIO 直读，绝不重开路径——
 *   SAF 的 fd 走 `/proc/self/fd` 会被 scoped storage 拒，实测踩过），返回句柄（0 = 打不开），
 *   `outInfo` 填 `[采样率, 声道数, 时长ms（未知 -1）]`；fd 的所有权在 Kotlin 侧（pfd），C 不 close；
 * - [nativeRead] 读交错 s16le PCM，返回 >0 = 本次字节量、0 = EOF、-1 = 错误；
 * - [nativeClose] 释放；`release()` 成对原则（AGENTS.md §5）由 [WmaPcmDecoder] 的 finally 负责。
 *
 * 只能在非主线程调用（与 MediaCodec 路径同一条纪律，AGENTS.md §5）。
 */
object WmaJni {
    init {
        System.loadLibrary("wma")
    }

    external fun nativeOpen(fd: Int, outInfo: IntArray): Long

    external fun nativeRead(handle: Long, out: ByteArray): Int

    external fun nativeClose(handle: Long)
}
