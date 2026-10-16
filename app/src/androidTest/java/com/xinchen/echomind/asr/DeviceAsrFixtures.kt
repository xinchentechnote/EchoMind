package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.xinchen.echomind.core.conf.AudioFormats
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert
import org.junit.Assert.assertTrue

/**
 * 设备上的真素材与真模型（真音频由宿主机搬上来；静音负例由测试确定性生成；见 `docs/testing.md` §4）。
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

    /**
     * 10 秒静音 WAV：只当「这段音频没有语音」的负例用。由 [fixture] 在设备上确定性生成，
     * 不再依赖早期那份已经丢失的《三体》占位 MP3。
     */
    const val SILENT_WAV = "pcm-fixture-silent.wav"

    /** [REAL_MP3] 的容器时长，宿主机数出来的（字幕时间越界检查要用它当上界） */
    const val REAL_MP3_DURATION_MS = 148_303L

    /** 用户的 34 分钟中文书解 WMA（wmav2 44.1 kHz 双声道，仓库 `test-data/` 的真实世界素材）。 */
    const val REAL_WMA = "pcm-fixture-real.wma"

    /** [REAL_WMA] 的容器时长；设备 libwma 与导入转码器独立读出相同值。 */
    const val REAL_WMA_DURATION_MS = 2_060_956L

    /** 用户的 56 分钟中文书解 mp3（中文识别质量打样素材） */
    const val USER_BOOK_MP3 = "user-test.mp3"

    /** 英文 TTS 朗读书页（27.3 s，有标准答案；仅保留给多语种模型打样，不是产品回归素材） */
    const val EN_TTS_WAV = "en-tts.wav"

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
        if (name == SILENT_WAV && !file.isFile) writeSilentWav(file)
        if (!file.isFile) {
            Assert.fail("缺少音频素材 ${file.path}：\n  adb -s emulator-5554 push <本机 mp3> ${file.path}")
        }
        return file
    }

    /** 16 kHz / mono / PCM16 LE；10 秒足够让 VAD 与解码器证明“有时长但没有语音”。 */
    private fun writeSilentWav(file: File) {
        val sampleRate = AudioFormats.ASR_SAMPLE_RATE
        val dataBytes = sampleRate * 10 * Short.SIZE_BYTES
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * Short.SIZE_BYTES)
            putShort(Short.SIZE_BYTES.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            output.write(header)
            output.write(ByteArray(dataBytes))
        }
        Log.i(TAG, "生成静音负例 ${file.path}（${file.length()} B）")
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
