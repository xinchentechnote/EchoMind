#!/usr/bin/env python3
"""生成可解码的静音 MP3 测试素材（无第三方依赖）。

为什么需要它：本机没有任何真实 .mp3，而书架/时长读取/播放/转录的验收都必须落在
真实可解码的音频上。这里只造「合法帧序列」：MPEG-1 Layer III、128 kbps、44.1 kHz、
单声道、无 CRC、无填充 —— 每帧 417 字节，帧头之后全 0（part2_3_length=0，解码结果是静音）。
解码器按帧头计数算时长，所以时长是真实可验证的；内容不含语音，只用于验证管线。

用法：python3 tools/make_test_mp3.py <输出目录>
"""
from __future__ import annotations

import os

# MPEG-1 Layer III 帧头：sync(11) + version=01(MPEG1) + layer=01(LayerIII) + protection=1(无 CRC)
#                      + bitrate_idx=1001(128k) + samplerate_idx=00(44100) + padding=0 + private=0
#                      + channel_mode=11(mono) + mode_ext=00 + copyright=0 + original=0 + emphasis=00
HEADER = bytes((0xFF, 0xFB, 0x90, 0xC0))
FRAME_BYTES = 417  # floor(144 * 128000 / 44100)
SAMPLES_PER_FRAME = 1152
SAMPLE_RATE = 44100
ZERO_PAYLOAD = bytes(FRAME_BYTES - len(HEADER))


def frames_for_seconds(seconds: float) -> int:
    return max(1, round(seconds * SAMPLE_RATE / SAMPLES_PER_FRAME))


def write_silent_mp3(path: str, seconds: float) -> int:
    frames = frames_for_seconds(seconds)
    with open(path, "wb") as fh:
        for _ in range(frames):
            fh.write(HEADER)
            fh.write(ZERO_PAYLOAD)
    return frames


DURATION_FILES = {
    "01 - 三体.mp3": 30.0,
    "第03集 录音.mp3": 12.5,
    "04_长章节.mp3": 90.0,
    "5.MP3": 6.0,
}

NESTED_FILES = {
    "second/02 - 第二章.mp3": 45.0,
    "second/notes.txt": None,  # 非音频，必须被扫描忽略
    "third/deep - 深层.mp3": 20.0,
}


def main(out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for name, seconds in {**DURATION_FILES, **NESTED_FILES}.items():
        if seconds is None:
            continue
        path = os.path.join(out_dir, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        frames = write_silent_mp3(path, seconds)
        expected_sec = frames * SAMPLES_PER_FRAME / SAMPLE_RATE
        written.append((name, frames, os.path.getsize(path), round(expected_sec, 2)))

    txt = os.path.join(out_dir, "second/notes.txt")
    os.makedirs(os.path.dirname(txt), exist_ok=True)
    with open(txt, "w", encoding="utf-8") as fh:
        fh.write("不是音频文件，用来验证扫描过滤\n")
    written.append(("second/notes.txt", 0, os.path.getsize(txt), None))

    print(f"{'file':<28}{'frames':>8}{'bytes':>10}{'sec':>8}")
    for name, frames, size, sec in written:
        print(f"{name:<28}{frames:>8}{size:>10}{str(sec):>8}")
    print(f"输出目录：{out_dir}")


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
