/*
 * WMA（ASF 容器，wmav1/wmav2）→ 16-bit LE PCM 的最小 JNI 解码壳。
 *
 * 只为这一件事存在：Android 没有 WMA 解码器（`asr/DeviceCodecProbeTest` 在设备上枚举过证据），
 * 而转录与「导入时转码 m4a/mp3」都需要 PCM。链接进来的 libwma 静态库是 FFmpeg 6.1.1 的最小裁剪
 * （--disable-everything + wmav1/wmav2 解码器 + asf demuxer + file protocol，LGPL-2.1-or-later），
 * 构建脚本与哈希钉在 `docs/architecture.md` D40 与 `app/libs/wma/README.md`。
 *
 * 输出形状与 `PcmDecoder` 对齐：交错 s16le、源采样率、源声道数——重采样与下混仍交给
 * `domain/asr` 的纯 Kotlin（AGENTS.md §3），这里只做「浮点平面 → 交错的 s16」这层 C 才好做的事。
 *
 * 输入是调用方持有的文件描述符（自定义 AVIO 直读，不重开路径）。
 * 读放大器以「完整字节流」为契约：nativeRead 返回 >0 = 本次字节量，0 = EOF，-1 = 读错误。
 * 解出的帧先落 pending 缓冲再按调用方的缓冲量搬走，不要求调用方按帧大小对齐。
 *
 * 两条容错纪律（2026-10-24，真机 0415 素材「WMA 解码中途失败」的教训）：
 * 1. pending **按需增长**。wmav2 一个 AVPacket 可以是带比特储备的超帧，解码器一次吐
 *    `nb_frames × frame_len` 个样本（nb_frames 4 bit，最多 15；frame_len 最高 8192），
 *    固定 32 KB 会在第 5 帧就溢出——表现为「第一包就报中途失败、已识别 0 段」。
 * 2. 单包解码失败只跳过不中止。老磁带/网络下载的 WMA 中间有坏包是常态，FFmpeg 自己的
 *    CLI 也是记录后继续；整次解码只有「一个样本都没解出来」才配得上 -1。
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "lame.h"

#define WMA_LOG_TAG "wma_jni"

#define AVIO_BUFFER_BYTES (64 * 1024)

/** pending 的安全上限（防御性）：够装 15 帧 × 8192 样本 × 双声道 × s16 还有余量，再大视为实现错误 */
#define MAX_PENDING_BYTES (16 * 1024 * 1024)

typedef struct WmaReader {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    int streamIndex;
    uint8_t *pending;   /* 解出未取走的交错 s16；容量按需增长（见文件头纪律 1） */
    int pendingCap;
    int pendingLen;
    int pendingOff;
    int eof;
    int badPackets;     /* 被跳过的坏包数（诊断用，Kotlin 侧经 nativeBadPackets 读取） */
    int64_t decodedSamplesPerCh; /* 累计解出的每声道样本数；全 0 且有坏包 = 真解不出 */
} WmaReader;

static void wmaLogAvError(const char *where, int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    __android_log_print(ANDROID_LOG_WARN, WMA_LOG_TAG, "%s: %s（包已跳过，继续解码）", where, buf);
}

/** 确保 pending 装得下 [extra] 字节；成功 0，超安全上限 -1。 */
static int ensurePending(WmaReader *r, int extra) {
    if (r->pendingLen + extra <= r->pendingCap) return 0;
    int cap = r->pendingCap > 0 ? r->pendingCap : 32 * 1024;
    while (cap < r->pendingLen + extra) {
        if (cap > MAX_PENDING_BYTES / 2) return -1;
        cap *= 2;
    }
    uint8_t *grown = realloc(r->pending, (size_t) cap);
    if (grown == NULL) return -1;
    r->pending = grown;
    r->pendingCap = cap;
    return 0;
}

/** 解出的一帧转成交错 s16 追加进 pending；样本值域按 FFmpeg 浮点平面 ±1.0 截断。 */
static int appendFrame(WmaReader *r, AVFrame *frame) {
    int channels = frame->ch_layout.nb_channels;
    if (channels <= 0 || channels > 2) return -1; /* 书解素材就是单/双声道，其他形态如实拒绝 */
    if (frame->format != AV_SAMPLE_FMT_FLTP) return -1; /* wmav1/2 解码器只输出浮点平面 */
    long bytes = (long) frame->nb_samples * channels * 2;
    if (ensurePending(r, (int) bytes) < 0) return -1;
    for (int i = 0; i < frame->nb_samples; i++) {
        for (int ch = 0; ch < channels; ch++) {
            const float *plane = (const float *) frame->data[ch];
            float f = plane[i];
            int32_t v = (int32_t) (f * 32767.0f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            r->pending[r->pendingLen++] = (uint8_t) (v & 0xff);
            r->pending[r->pendingLen++] = (uint8_t) ((v >> 8) & 0xff);
        }
    }
    r->decodedSamplesPerCh += frame->nb_samples;
    return 0;
}

/**
 * 喂一个包给解码器并抽干输出帧。返回 0 = 继续（含「这包坏了，跳过」）；-1 = 内部错误，放弃整次读。
 * 坏包（AVERROR_INVALIDDATA 等）记数后跳过：见文件头纪律 2。
 */
static int decodePacket(WmaReader *r, AVPacket *pkt) {
    int rc = avcodec_send_packet(r->dec, pkt);
    if (rc == AVERROR(EAGAIN)) {
        /* 输出没抽干就来了新包：抽干后重喂一次（正常循环不会走到，防御性） */
        AVFrame *drain = av_frame_alloc();
        if (drain != NULL) {
            while (avcodec_receive_frame(r->dec, drain) == 0) {
                if (appendFrame(r, drain) < 0) { av_frame_free(&drain); return -1; }
                av_frame_unref(drain);
            }
            av_frame_free(&drain);
        }
        rc = avcodec_send_packet(r->dec, pkt);
    }
    if (rc < 0) {
        r->badPackets++;
        wmaLogAvError("send_packet", rc);
        return 0;
    }
    AVFrame *frame = av_frame_alloc();
    if (frame == NULL) return -1;
    int fatal = 0;
    while (1) {
        rc = avcodec_receive_frame(r->dec, frame);
        if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) break;
        if (rc < 0) {
            r->badPackets++;
            wmaLogAvError("receive_frame", rc);
            break;
        }
        if (appendFrame(r, frame) < 0) { fatal = -1; break; }
        av_frame_unref(frame);
    }
    av_frame_free(&frame);
    return fatal;
}

/* SAF 文件描述符的 AVIO 回调：读 / seek / 尺寸。fd 的所有权在 Kotlin 侧（pfd），这里绝不 close。 */
static int wmaReadCb(void *opaque, uint8_t *buf, int bufSize) {
    return (int) read((int) (intptr_t) opaque, buf, (size_t) bufSize);
}

static int64_t wmaSeekCb(void *opaque, int64_t offset, int whence) {
    int fd = (int) (intptr_t) opaque;
    if (whence == AVSEEK_SIZE) {
        struct stat st;
        if (fstat(fd, &st) < 0) return -1;
        return st.st_size;
    }
    return lseek(fd, offset, whence);
}

static void wmaFree(WmaReader *r) {
    if (r == NULL) return;
    if (r->dec != NULL) avcodec_free_context(&r->dec);
    if (r->fmt != NULL) avformat_close_input(&r->fmt);
    free(r->pending);
    free(r);
}

JNIEXPORT jlong JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeOpen(JNIEnv *env, jclass clazz, jint jFd, jintArray jInfo) {
    WmaReader *r = calloc(1, sizeof(WmaReader));
    AVFormatContext *fmt = avformat_alloc_context();
    AVCodecContext *dec = NULL;
    jlong handle = 0;
    int fd = (int) jFd;

    /* SAF 的 fd 不能走 /proc/self/fd 路径让 FFmpeg 重开——scoped storage 下沿符号链接直接 open 会被拒
     * （实测踩过：file:// 素材能过、SAF content:// 必挂）。所以用自定义 AVIO 直接在这个 fd 上 read/seek。 */
    unsigned char *avioBuf = av_malloc(AVIO_BUFFER_BYTES);
    AVIOContext *avio = NULL;
    if (avioBuf != NULL) {
        avio = avio_alloc_context(avioBuf, AVIO_BUFFER_BYTES, 0, (void *) (intptr_t) fd, wmaReadCb, NULL, wmaSeekCb);
    }
    if (avio != NULL) fmt->pb = avio;

    if (r == NULL || avio == NULL || fmt == NULL) goto fail;
    if (avformat_open_input(&fmt, "", NULL, NULL) < 0) goto fail;
    if (avformat_find_stream_info(fmt, NULL) < 0) goto fail;

    r->streamIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (r->streamIndex < 0) goto fail;
    AVStream *stream = fmt->streams[r->streamIndex];
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (codec == NULL) goto fail;

    dec = avcodec_alloc_context3(codec);
    if (dec == NULL) goto fail;
    if (avcodec_parameters_to_context(dec, stream->codecpar) < 0) goto fail;
    if (avcodec_open2(dec, codec, NULL) < 0) goto fail;

    r->fmt = fmt;
    r->dec = dec;

    {
        jint info[3];
        info[0] = dec->sample_rate;
        info[1] = (jint) dec->ch_layout.nb_channels;
        /* fmt->duration 是 AV_TIME_BASE（微秒）单位；没给就是 -1，与 PcmDecodeSummary 的口径一致 */
        info[2] = fmt->duration < 0 ? -1 : (jint) (fmt->duration / (AV_TIME_BASE / 1000));
        (*env)->SetIntArrayRegion(env, jInfo, 0, 3, info);
    }

    handle = (jlong) (intptr_t) r;
fail:
    if (handle == 0) {
        /* avformat_open_input 成功后 fmt 的所有权归它（含 avio 与缓冲）；只有失败路径才需要手动清 */
        if (dec != NULL) avcodec_free_context(&dec);
        if (fmt != NULL && fmt->pb != NULL && handle == 0) {
            av_freep(&fmt->pb->buffer);
            avio_context_free(&fmt->pb);
        }
        if (fmt != NULL) avformat_free_context(fmt);
        free(r);
    }
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeRead(JNIEnv *env, jclass clazz, jlong handle, jbyteArray jOut) {
    if (handle == 0) return -1;
    WmaReader *r = (WmaReader *) (intptr_t) handle;
    jsize outBytes = (*env)->GetArrayLength(env, jOut);
    if (outBytes <= 0) return 0;

    /* 先把上次剩的搬走 */
    jint copied = 0;
    if (r->pendingLen > r->pendingOff) {
        jint have = r->pendingLen - r->pendingOff;
        jint take = have < outBytes ? have : outBytes;
        (*env)->SetByteArrayRegion(env, jOut, 0, take, (const jbyte *) (r->pending + r->pendingOff));
        r->pendingOff += take;
        if (r->pendingOff == r->pendingLen) { r->pendingOff = 0; r->pendingLen = 0; }
        copied = take;
        if (copied == outBytes) return copied;
    }

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    jint rc = -1;
    if (pkt == NULL || frame == NULL) goto done;

    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, jOut, NULL);
    if (out == NULL) goto done;

    rc = copied;
    while (rc < outBytes) {
        if (r->pendingLen > r->pendingOff) {
            jint have = r->pendingLen - r->pendingOff;
            jint take = have < (outBytes - rc) ? have : (outBytes - rc);
            memcpy(out + rc, r->pending + r->pendingOff, take);
            r->pendingOff += take;
            if (r->pendingOff == r->pendingLen) { r->pendingOff = 0; r->pendingLen = 0; }
            rc += take;
            continue;
        }
        if (r->eof) {
            /* EOF：手里还有字节就先交出去（break 带 rc>0 返回），没货了返回 0 */
            if (rc > 0) break;
            rc = 0;
            break;
        }
        if (av_read_frame(r->fmt, pkt) < 0) {
            /* 输入到头（或 demuxer 中途放弃）：冲刷解码器，把最后的帧抽干 */
            r->eof = 1;
            if (avcodec_send_packet(r->dec, NULL) < 0) continue;
            while (avcodec_receive_frame(r->dec, frame) == 0) {
                if (appendFrame(r, frame) < 0) { rc = -1; goto release; }
            }
            continue;
        }
        if (pkt->stream_index != r->streamIndex) { av_packet_unref(pkt); continue; }
        int fatal = decodePacket(r, pkt); /* 坏包在内部记数跳过，不会中止整次读 */
        av_packet_unref(pkt);
        if (fatal < 0) { rc = -1; goto release; }
    }

release:
    /* 整条流一个样本都没解出来还一路报错，才是真「解不出」；有产出就照常交付（宁可半本不空白） */
    if (rc >= 0 && r->decodedSamplesPerCh == 0 && r->badPackets > 0) rc = -1;
    (*env)->ReleasePrimitiveArrayCritical(env, jOut, out, 0);
done:
    av_packet_free(&pkt);
    av_frame_free(&frame);
    return rc;
}

/** 被跳过的坏包数（诊断：Kotlin 侧在收尾 log，不参与控制流）。 */
JNIEXPORT jint JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeBadPackets(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return 0;
    WmaReader *r = (WmaReader *) (intptr_t) handle;
    return r->badPackets;
}

JNIEXPORT void JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    wmaFree((WmaReader *) (intptr_t) handle);
}

/* ── MP3 编码（LAME 3.100，转码目标的 mp3 支路）────────────────────────────
 * 与解码侧同一份 libwma.so：wma 解出 s16 → lame 编成 mp3。返回值约定与解码对偶：
 * >0 = 写入 out 的 mp3 字节数，-1 = 错误。PCM 输入是交错 s16le（与 WMA 解码输出同形）。
 * 句柄侧包一层记下声道数：lame_encode_buffer_interleaved 只认双声道交错输入，
 * 单声道必须走 lame_encode_buffer（步长 1），否则会隔样本取值（半速升调）。 */

typedef struct Mp3Encoder {
    lame_global_flags *gfp;
    int channels;
} Mp3Encoder;

JNIEXPORT jlong JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeMp3Open(JNIEnv *env, jclass clazz, jint sampleRate, jint channels, jint bitrateKbps) {
    if (sampleRate <= 0 || channels < 1 || channels > 2) return 0;
    lame_global_flags *gfp = lame_init();
    if (gfp == NULL) return 0;
    lame_set_in_samplerate(gfp, sampleRate);
    lame_set_num_channels(gfp, channels);
    lame_set_brate(gfp, bitrateKbps);
    lame_set_quality(gfp, 5); /* 中等质量：转码是一次性后台活，速度优先于极致码率 */
    if (channels == 1) lame_set_mode(gfp, MONO);
    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return 0;
    }
    Mp3Encoder *enc = malloc(sizeof(Mp3Encoder));
    if (enc == NULL) {
        lame_close(gfp);
        return 0;
    }
    enc->gfp = gfp;
    enc->channels = channels;
    return (jlong) (intptr_t) enc;
}

/** 输入交错 s16；返回写入 out 的字节数（≥0），-1 = 编码错误。samples = 每声道样本数。 */
JNIEXPORT jint JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeMp3Write(JNIEnv *env, jclass clazz, jlong handle, jbyteArray jPcm, jint samples, jbyteArray jOut) {
    if (handle == 0) return -1;
    Mp3Encoder *enc = (Mp3Encoder *) (intptr_t) handle;
    jsize pcmBytes = (*env)->GetArrayLength(env, jPcm);
    if (samples <= 0 || pcmBytes < samples * 2 * enc->channels) return -1;
    jbyte *pcm = (*env)->GetByteArrayElements(env, jPcm, NULL);
    jbyte *out = (*env)->GetByteArrayElements(env, jOut, NULL);
    if (pcm == NULL || out == NULL) {
        if (pcm != NULL) (*env)->ReleaseByteArrayElements(env, jPcm, pcm, JNI_ABORT);
        if (out != NULL) (*env)->ReleaseByteArrayElements(env, jOut, out, JNI_ABORT);
        return -1;
    }
    short *s = (short *) pcm;
    jint written = enc->channels == 1
        ? lame_encode_buffer(enc->gfp, s, s, samples, (unsigned char *) out, (int) (*env)->GetArrayLength(env, jOut))
        : lame_encode_buffer_interleaved(enc->gfp, s, samples, (unsigned char *) out, (int) (*env)->GetArrayLength(env, jOut));
    (*env)->ReleaseByteArrayElements(env, jPcm, pcm, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jOut, out, 0);
    if (written < 0) return -1;
    return written;
}

/** 冲刷编码器尾部；返回写入 out 的字节数（≥0），-1 = 错误。 */
JNIEXPORT jint JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeMp3Flush(JNIEnv *env, jclass clazz, jlong handle, jbyteArray jOut) {
    if (handle == 0) return -1;
    Mp3Encoder *enc = (Mp3Encoder *) (intptr_t) handle;
    jbyte *out = (*env)->GetByteArrayElements(env, jOut, NULL);
    if (out == NULL) return -1;
    jint written = lame_encode_flush(enc->gfp, (unsigned char *) out, (int) (*env)->GetArrayLength(env, jOut));
    (*env)->ReleaseByteArrayElements(env, jOut, out, 0);
    if (written < 0) return -1;
    return written;
}

JNIEXPORT void JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeMp3Close(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    Mp3Encoder *enc = (Mp3Encoder *) (intptr_t) handle;
    lame_close(enc->gfp);
    free(enc);
}
