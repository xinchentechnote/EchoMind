package com.xinchen.echomind.data.repo

import java.io.File

/**
 * WMA 转码产物的存放约定（`filesDir/transcoded/<audioId>.m4a`）。
 *
 * 三个使用方（导入时写、播放时换 URI、删书时清）都从这里拿路径，
 * 「同一个约定不许两处拼字符串」与 `ModelCatalog.MODELS_DIR_NAME` 同理。
 * 放在 `data/repo` 而不是 `asr`：data 不许反向依赖 asr（会成环），而 asr/ui 依赖 data 是既有方向。
 */
class WmaPlaybackStore(private val dir: File) {

    fun fileOf(audioId: String): File = File(dir, "$audioId.m4a")

    /** 播放用的 URI：WMA 书换成本地转码产物，其余原样返回 */
    fun playbackUriFor(fileName: String, originalUri: String, audioId: String): String =
        if (isWma(fileName)) fileOf(audioId).toURI().toString() else originalUri

    /** 转码完成后把转码出来的时长放回书（WMA 读不到 Media3 的时长，导入编排用它替换 durationReadFailed 口径） */
    fun prepareDir() {
        dir.mkdirs()
    }

    fun delete(audioId: String) {
        fileOf(audioId).delete()
    }

    companion object {
        fun isWma(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() == "wma"
    }
}
