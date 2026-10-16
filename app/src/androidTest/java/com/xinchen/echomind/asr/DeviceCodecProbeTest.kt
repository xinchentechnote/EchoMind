package com.xinchen.echomind.asr

import android.media.MediaCodecList
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备解码能力的**证据性探针**（2026-10-16 放开 m4a/flac/wav 白名单时落的）。
 *
 * 「Android 支持哪些音频格式」不能凭记忆写白名单（AGENTS.md §2/§5），这里把两件事钉在设备上：
 * 1. 把本机全部**音频解码器**与其支持的 MIME 打进 logcat——放行任何新格式前先来这儿查一眼；
 * 2. 断言没有 WMA 解码器：这就是 `AudioFormats.SUPPORTED_EXTENSIONS` 不收 `wma` 的证据——
 *    Android（AOSP）从无 WMA 解码，支持它只能引入 FFmpeg 量级的第三方框架。
 *    （本条证据来自 emulator-5554 的 AOSP 镜像；个别厂商 ROM 历史上带过私有 WMA 组件，
 *    换真机时这条用例就是现成的复查工具——它红不了，但 logcat 会告诉你真机有什么。）
 */
@RunWith(AndroidJUnit4::class)
class DeviceCodecProbeTest {

    @Test
    fun 本机没有wma解码器并列出全部音频解码类型() {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        val audioTypes = decoders
            .flatMap { codec -> codec.supportedTypes.filter { it.startsWith("audio/") } }
            .distinct()
            .sorted()
        Log.i(TAG, "本机音频解码器 ${decoders.size} 个，可解类型：$audioTypes")

        val wmaTypes = audioTypes.filter { it.contains("wma", ignoreCase = true) || it.contains("x-ms-wma") }
        assertTrue("出现了 WMA 解码器 $wmaTypes——白名单可以重新评估了", wmaTypes.isEmpty())
        // 给白名单兜底的几类必须在：这是 m4a/flac/wav/mp3 放行的前提
        listOf("audio/mpeg", "audio/mp4a-latm", "audio/flac", "audio/raw").forEach { required ->
            assertTrue("缺 $required 解码器，白名单该收回了", required in audioTypes)
        }
    }

    private companion object {
        const val TAG = "DeviceCodecProbe"
    }
}
