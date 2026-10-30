package com.xinchen.echomind.asr

import android.util.Log
import com.xinchen.echomind.domain.transcript.TranscriptCheckpoint
import com.xinchen.echomind.domain.transcript.TranscriptCheckpointCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 转录断点的落盘（FR-512）：一本书一个文件（`<audioId>.checkpoint`），放在 `filesDir` 下的
 * [dir]（生产装配是 `filesDir/transcript_checkpoints`）。
 *
 * 生命周期由协调器管：识别中按 `AudioFormats.ASR_CHECKPOINT_SEGMENTS` 节奏重写；取消时补写一次；
 * **成功收口必删**（书已 READY，断点没有存在意义）；身份不符（文件变过 / 换模型）在下一轮
 * 开跑时读出来即删。删除书时级联删的是任务行，这个文件成了孤儿——同一本书下次转录会原样覆盖
 * 或作废它，最多一个文件/本，不特意清扫。
 *
 * 写盘是**先 `.part` 后原子改名**（与 `WmaPlaybackStore`、`AsyncWmaImportPreparer` 同一套约定）：
 * 进程被杀在写一半时，留下的是半截 `.part`，正式名永远指向上一份完整断点。
 */
class TranscriptCheckpointStore(private val dir: File) {

    init {
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "断点目录建不出来：${dir.path}（保存时会再试）")
        }
    }

    fun fileOf(audioId: String) = File(dir, "$audioId.checkpoint")

    /** 读不回来（不存在 / 半截 / 版本不符 / 损坏）一律 null；顺手把读不动的文件删掉，别让它一直躺在盘上 */
    fun load(audioId: String): TranscriptCheckpoint? {
        val file = fileOf(audioId)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                TranscriptCheckpointCodec.read(input)
            }
        } catch (e: Exception) {
            Log.w(TAG, "断点读不出来，按无断点处理并删除：${file.path}（${e.javaClass.simpleName} ${e.message}）")
            runCatching { file.delete() }
            null
        }
    }

    fun save(checkpoint: TranscriptCheckpoint) {
        val file = fileOf(checkpoint.audioId)
        if (!dir.isDirectory) dir.mkdirs()
        val part = File(dir, "${file.name}.part")
        try {
            DataOutputStream(FileOutputStream(part).buffered()).use { output ->
                TranscriptCheckpointCodec.write(checkpoint, output)
            }
            if (!part.renameTo(file)) {
                // 同一目录内的 rename 只在极端文件系统下才会失败，但静默丢断点不行——至少说出来
                Log.w(TAG, "断点改名失败：${part.path} → ${file.path}")
                part.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "断点写盘失败（不影响本轮转录）：${file.path}，${e.javaClass.simpleName} ${e.message}")
            runCatching { part.delete() }
        }
    }

    fun delete(audioId: String) {
        runCatching { fileOf(audioId).delete() }
    }

    companion object {
        private const val TAG = "EchoMindTranscript"
    }
}
