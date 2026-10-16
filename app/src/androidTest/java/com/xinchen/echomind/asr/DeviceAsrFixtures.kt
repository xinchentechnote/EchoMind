package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.xinchen.echomind.core.conf.AudioFormats
import java.io.File
import org.junit.Assert
import org.junit.Assert.assertTrue

/**
 * 设备上的真素材与真模型（**都由宿主机搬上来**，不是 App 从网上下的，见 `docs/testing.md` §4）。
 * 模型目录就是生产路径 `filesDir/models/<spec.id>/<fileName>`，所以 [ModelVerifier] 能原样核对。
 *
 * 摆放模型要用 `run-as`（先 `adb push` 到 `/data/local/tmp`，再 `run-as <pkg> cat` 进 filesDir）：
 * 直接 `adb shell mkdir` 在 external 下建的子目录属主是 root，App 读不到（实测 `isFile` 返回 false）。
 *
 * 缺文件一律 fail 并打印确切命令，不用 `assumeTrue` 静默跳过——跳过的推理测试等于没有测试。
 */
internal const val PACKAGE = "com.xinchen.echomind"

internal object DeviceAsrFixtures {

    /** 与 `AppGraph` 的 `modelsRoot` 同一个位置（AGENTS.md §3：路径也只许一处定义）。 */
    val MODEL_DIR: String = ModelCatalog.MODELS_DIR_NAME
    const val FIXTURE_DIR = "asr-fixtures"

    /** 真人的歌（用户素材 `那些花儿.mp3`，44.1 kHz 立体声，148,303 ms，峰值 0.99）。静音占位素材不用。 */
    const val REAL_MP3 = "pcm-fixture-real.mp3"

    /** 那批《三体》占位素材（帧体 99% 是 0x00，也就是**静音**）：只当「这段音频没有语音」的负例用 */
    const val SILENT_MP3 = "pcm-fixture-01.mp3"

    /** [REAL_MP3] 的容器时长，宿主机数出来的（字幕时间越界检查要用它当上界） */
    const val REAL_MP3_DURATION_MS = 148_303L

    /**
     * 同一份真语音的 WMA 版（宿主机自建 ffmpeg `-c:a wmav2 -b:a 128k` 转出，wmav2 44.1kHz 双声道，148,637 ms）。
     * WMA 解码走 `libwma`（D40），与 mp3 的 MediaCodec 路径互为对照。
     */
    const val REAL_WMA = "pcm-fixture-real.wma"

    /** [REAL_WMA] 的容器时长（宿主机 ffmpeg 报告） */
    const val REAL_WMA_DURATION_MS = 148_637L

    /** 宿主机把 [REAL_WMA] 解到 wav 的样本数（26,214,400 B / 4 B），做设备端解码样本数的对照基准 */
    const val REAL_WMA_REF_SAMPLES_44K = 6_553_600L

    private const val TAG = "DeviceAsrFixtures"

    fun modelRoot(context: Context = ApplicationProvider.getApplicationContext()): File =
        File(context.filesDir, MODEL_DIR)

    /** 模型包目录（`asr-models/<id>`），核对与引擎加载都用它。 */
    fun modelDir(spec: ModelSpec, context: Context = ApplicationProvider.getApplicationContext()): File =
        ModelVerifier.dirOf(modelRoot(context), spec)

    /** 取一个模型文件：先在设备上按清单核对，缺了或不合格直接 fail。 */
    fun modelFile(
        spec: ModelSpec,
        fileName: String,
        context: Context = ApplicationProvider.getApplicationContext(),
    ): File {
        val fileSpec = spec.files.firstOrNull { it.fileName == fileName }
            ?: error("清单 ${spec.id} 里没有文件 $fileName")
        val file = File(modelDir(spec, context), fileName)
        if (!file.isFile) {
            Assert.fail(
                "缺少模型 ${file.path}。这台设备连不上 GitHub，只能从宿主机搬：\n" +
                    "  adb -s emulator-5554 push app/libs/models/${hostDirOf(spec)}/$fileName /data/local/tmp/$fileName\n" +
                    "  adb -s emulator-5554 shell run-as $PACKAGE sh -c 'cat /data/local/tmp/$fileName > ${relativePathOf(spec, fileName)}'"
            )
        }
        val failure = ModelVerifier.checkFile(file, fileSpec)
        Assert.assertTrue("设备上的 $fileName 与清单不符：$failure", failure == null)
        Log.i(TAG, "$fileName 已在设备上按清单核对通过（size=${file.length()}，sha 与清单一致）")
        return file
    }

    /** VAD 模型（清单核对过的那个文件）。 */
    fun vadModel(context: Context = ApplicationProvider.getApplicationContext()): File =
        modelFile(ModelCatalog.sileroVad, ModelCatalog.SILERO_VAD_MODEL_FILE, context)

    fun fixture(name: String = REAL_MP3, context: Context = ApplicationProvider.getApplicationContext()): File {
        val dir = context.getExternalFilesDir(FIXTURE_DIR)
            ?: error("设备上没有 external files 目录（getExternalFilesDir 返回 null）")
        val file = File(dir, name)
        if (!file.isFile) {
            Assert.fail("缺少音频素材 ${file.path}：\n  adb -s emulator-5554 push <本机 mp3> ${file.path}")
        }
        return file
    }

    /** 用真解码器解出前 [seconds] 秒的 16k 单声道 float（只用于秒级取样，整章转录走流式）。 */
    fun decodedSeconds(
        seconds: Int,
        context: Context = ApplicationProvider.getApplicationContext(),
    ): FloatArray {
        val target = seconds.toLong() * AudioFormats.ASR_SAMPLE_RATE
        val collected = mutableListOf<FloatArray>()
        var total = 0
        PcmDecoder(context).decode(Uri.fromFile(fixture(context = context))) { samples, _ ->
            collected += samples
            total += samples.size
            total < target
        }
        val out = FloatArray(total)
        var offset = 0
        for (chunk in collected) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        Log.i(TAG, "取前 $seconds s：解出 ${out.size} 样本 = ${out.size * 1000 / AudioFormats.ASR_SAMPLE_RATE} ms")
        return out
    }

    /** filesDir 是 App 私有的，报错信息里给相对路径才好照着敲命令。 */
    private fun relativePathOf(spec: ModelSpec, fileName: String): String =
        "files/${MODEL_DIR}/${spec.id}/$fileName"

    /** 本机那些文件躺在 `app/libs/models/<下载时的目录名>/` 下（gitignore 内，不入库）。 */
    private fun hostDirOf(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.PARAFORMER_ID -> "sherpa-onnx-paraformer-zh-2023-09-14"
        else -> spec.id
    }
}
