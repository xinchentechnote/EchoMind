package com.xinchen.echomind.data.repo

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * WMA 转码产物的存放约定（`filesDir/transcoded/<audioId>.m4a`）。
 *
 * 三个使用方（导入时写、播放时换 URI、删书时清）都从这里拿路径，
 * 「同一个约定不许两处拼字符串」与 `ModelCatalog.MODELS_DIR_NAME` 同理。
 * 放在 `data/repo` 而不是 `asr`：data 不许反向依赖 asr（会成环），而 asr/ui 依赖 data 是既有方向。
 */
class WmaPlaybackStore(private val dir: File) {

    fun fileOf(audioId: String): File = File(dir, "$audioId.m4a")

    /**
     * 后台转码只写本次任务独占的临时文件；成功后才替换正式产物。
     *
     * generation 让已取消但仍卡在厂商 MediaCodec 里的旧任务与新任务隔离，
     * 否则删除后快速重导入时，两次转码可能同时写同一个 `.part`。
     */
    fun partFileOf(audioId: String, generation: String): File =
        File(dir, "$audioId.$generation.m4a.part")

    fun isReady(audioId: String): Boolean = fileOf(audioId).let { it.isFile && it.length() > 0L }

    /** 播放用的 URI：WMA 书换成本地转码产物，其余原样返回 */
    fun playbackUriFor(fileName: String, originalUri: String, audioId: String): String =
        if (isWma(fileName) && isReady(audioId)) fileOf(audioId).toURI().toString() else originalUri

    /** 转码完成后把转码出来的时长放回书（WMA 读不到 Media3 的时长，导入编排用它替换 durationReadFailed 口径） */
    fun prepareDir() {
        dir.mkdirs()
    }

    /** 同目录原子替换；文件系统不支持 ATOMIC_MOVE 时退化为同目录覆盖移动。 */
    fun commit(audioId: String, generation: String) {
        val source = partFileOf(audioId, generation).toPath()
        val target = fileOf(audioId).toPath()
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun deleteParts(audioId: String) {
        dir.listFiles()
            ?.filter { it.name.startsWith("$audioId.") && it.name.endsWith(".m4a.part") }
            ?.forEach(File::delete)
    }

    fun delete(audioId: String) {
        fileOf(audioId).delete()
        deleteParts(audioId)
    }

    companion object {
        fun isWma(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() == "wma"
    }
}
