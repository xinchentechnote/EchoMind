/*
 * WMA（ASF 容器，wmav1/wmav2）→ 16-bit LE PCM 的最小 JNI 解码壳。
 *
 * 只为这一件事存在：Android 没有 WMA 解码器（`asr/DeviceCodecProbeTest` 在设备上枚举过证据），
 * 而转录与「导入时转码 m4a」都需要 PCM。链接进来的 libwma 静态库是 FFmpeg 6.1.1 的最小裁剪
 * （--disable-everything + wmav1/wmav2 解码器 + asf demuxer + file protocol，LGPL-2.1-or-later），
 * 构建脚本与哈希钉在 `docs/architecture.md` D40 与 `app/libs/wma/README.md`。
 *
 * 输出形状与 `PcmDecoder` 对齐：交错 s16le、源采样率、源声道数——重采样与下混仍交给
 * `domain/asr` 的纯 Kotlin（AGENTS.md §3），这里只做「浮点平面 → 交错的 s16」这层 C 才好做的事。
 *
 * 输入是调用方持有的文件描述符（自定义 AVIO 直读，不重开路径）。
 * 读放大器以「完整字节流」为契约：nativeRead 返回 >0 = 本次字节量，0 = EOF，-1 = 读错误。
 * 解出的帧先落 pending 缓冲再按调用方的缓冲量搬走，不要求调用方按帧大小对齐。
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"

#define MAX_FRAME_BYTES (2 * 8192 * 2) /* 双声道 × 单帧最多 8192 样本 × 2 字节，wmav2 单帧 2048 样本，留足余量 */

#define AVIO_BUFFER_BYTES (64 * 1024)

typedef struct WmaReader {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    int streamIndex;
    uint8_t pending[MAX_FRAME_BYTES];
    int pendingLen;
    int pendingOff;
    int eof;
} WmaReader;

/** 解出的一帧转成交错 s16 追加进 pending；容量按解码器上限给足，装不下视为实现错误直接报错。 */
static int appendFrame(WmaReader *r, AVFrame *frame) {
    int channels = frame->ch_layout.nb_channels;
    if (channels <= 0 || channels > 2) return -1; /* 书解素材就是单/双声道，其他形态如实拒绝 */
    if (frame->format != AV_SAMPLE_FMT_FLTP) return -1; /* wmav1/2 解码器只输出浮点平面 */
    long bytes = (long) frame->nb_samples * channels * 2;
    if (r->pendingLen + bytes > MAX_FRAME_BYTES) return -1;
    for (int i = 0; i < frame->nb_samples; i++) {
        for (int ch = 0; ch < channels; ch++) {
            const float *plane = (const float *) frame->data[ch];
            float f = plane[i];
            /* ffmpeg 的浮点样本以 ±1.0 为满幅；截断而不是四舍五入，与 MediaCodec 的 s16 输出精度同级 */
            int32_t v = (int32_t) (f * 32767.0f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            r->pending[r->pendingLen++] = (uint8_t) (v & 0xff);
            r->pending[r->pendingLen++] = (uint8_t) ((v >> 8) & 0xff);
        }
    }
    return 0;
}

/** 喂一个包给解码器并抽干输出帧。返回 0 成功；-1 错误。 */
static int decodePacket(WmaReader *r, AVPacket *pkt) {
    if (avcodec_send_packet(r->dec, pkt) < 0) return -1;
    AVFrame *frame = av_frame_alloc();
    if (frame == NULL) return -1;
    int rc = 0;
    while (avcodec_receive_frame(r->dec, frame) == 0) {
        if (appendFrame(r, frame) < 0) { rc = -1; break; }
    }
    av_frame_free(&frame);
    return rc;
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
        if (dec != NULL) avcodec_free_context(&dec);
        /* avformat_open_input 成功后 fmt 的所有权归它（含 avio 与缓冲）；只有失败路径才需要手动清 */
        if (fmt != NULL) {
            if (fmt->pb != NULL) {
                av_freep(&fmt->pb->buffer);
                avio_context_free(&fmt->pb);
            }
            avformat_free_context(fmt);
        }
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
            /* 输入到头：冲刷解码器，把最后的帧抽干 */
            r->eof = 1;
            if (avcodec_send_packet(r->dec, NULL) < 0) break;
            while (avcodec_receive_frame(r->dec, frame) == 0) {
                if (appendFrame(r, frame) < 0) { rc = -1; goto release; }
            }
            continue;
        }
        if (pkt->stream_index != r->streamIndex) { av_packet_unref(pkt); continue; }
        if (decodePacket(r, pkt) < 0) { av_packet_unref(pkt); rc = -1; goto release; }
        av_packet_unref(pkt);
    }

release:
    (*env)->ReleasePrimitiveArrayCritical(env, jOut, out, 0);
done:
    av_packet_free(&pkt);
    av_frame_free(&frame);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_xinchen_echomind_asr_WmaJni_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    WmaReader *r = (WmaReader *) (intptr_t) handle;
    if (r->dec != NULL) avcodec_free_context(&r->dec);
    if (r->fmt != NULL) avformat_close_input(&r->fmt);
    free(r);
}
