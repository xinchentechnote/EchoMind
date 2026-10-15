package com.xinchen.echomind.ui.settings

import com.xinchen.echomind.asr.ModelFileSpec
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.ModelRole
import com.xinchen.echomind.asr.ModelStatus
import com.xinchen.echomind.asr.ModelVerification
import com.xinchen.echomind.ui.common.formatBytes

/**
 * 设置页「模型」那一节的文字（FR-510 / FR-511 / FR-901）。
 *
 * 与 `TranscriptDisplay` 同一个理由写成纯 Kotlin：「模型不可用时该给用户什么动作」这条要能在 JVM 里断言
 * （AGENTS.md §6）。**大小、哈希、就绪与否一律读 `ModelStatus`/`ModelCatalog`**，这里一个都不重算
 * （FR-901 明写了 UI 只许读它们）。
 *
 * 模型随安装包内置（FR-511）：这一节的动词是「恢复」，没有「下载」——
 * 谁也不该向用户许诺一个不存在的产品路径。
 */
object ModelDisplay {

    /** 这一节的开头一句：把 FR-501 的边界说清，模型就位不依赖任何网络 */
    const val SECTION_NOTE =
        "模型已随安装包内置：启动时会自动就位并逐文件校验，全程不需要网络，飞行模式下也能转录。" +
            "两个包都就绪了才能开始转录。"

    fun roleLabel(role: ModelRole): String = when (role) {
        ModelRole.Recognizer -> "识别模型：把语音写成带时间的字"
        ModelRole.VoiceActivityDetector -> "语音段切分：先找出哪些片段有人说话，只把这些送去识别"
    }

    /**
     * 一个包的整体状态。核对还没跑过时如实说「还没核对」——**不许**显示成「未下载」或「已就绪」，
     * 那两种说法都是在没有证据时下结论。
     */
    fun statusLabel(status: ModelStatus): String = when {
        status.isDownloading -> "下载中"
        status.isImporting -> "恢复中"
        status.verification == null -> "还没核对"
        status.isReady -> "已就绪"
        else -> "不可用"
    }

    /** 不可用时差在哪：直接引用核对结论（`ModelManager.describeFailure`），不另写一套判据 */
    fun problemLabel(verification: ModelVerification?): String? {
        val failures = verification?.failures ?: return null
        if (failures.isEmpty()) return null
        return failures.joinToString("；") { ModelManager.describeFailure(it) }
    }

    /** 单个文件的状态词。没核对过就是没核对过 */
    fun fileStateLabel(verification: ModelVerification?, fileName: String): String {
        if (verification == null) return "还没核对"
        val failure = verification.failures.firstOrNull { it.fileName == fileName }
        return if (failure == null) "已核对通过" else ModelManager.describeFailure(failure)
    }

    /**
     * 校验值的来源标注（FR-510 要求「来源标注清楚」）。
     * `tokens.txt` 官方就没发布校验值，这一行必须说出来，不能让它看起来和官方值等价。
     */
    fun checksumLabel(fileSpec: ModelFileSpec): String =
        if (fileSpec.hasOfficialChecksum) "校验值来自官方发布"
        else "官方没有发布校验值，用的是本机首次取回时钉住的那份"

    /**
     * 进度那一行。总数未知时只报已收字节，**不报百分比**——
     * 与转录页同一条规矩：假进度比没有进度更糟。
     */
    fun progressLabel(status: ModelStatus): String? {
        val progress = status.download ?: return null
        val verb = if (status.isImporting) "正在从安装包恢复" else "正在下载"
        if (progress.totalBytes <= 0) {
            return "$verb ${progress.fileName}：已收 ${formatBytes(progress.receivedBytes)}（对方没给总大小，不报百分比）"
        }
        val percent = progress.receivedBytes * 100 / progress.totalBytes
        return "$verb ${progress.fileName}：${formatBytes(progress.receivedBytes)} / " +
            "${formatBytes(progress.totalBytes)}（$percent%）"
    }

    /** 进度条的比例；总数未知时返回 null，页面据此不画进度条 */
    fun progressFraction(status: ModelStatus): Float? {
        val progress = status.download ?: return null
        if (progress.totalBytes <= 0) return null
        return (progress.receivedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
    }

    /** 就绪的包不给恢复按钮：`provision` 对齐全的包什么都不做，点下去什么也不会发生 */
    fun canRestore(status: ModelStatus): Boolean =
        !status.isReady && !status.isDownloading && !status.isImporting

    fun restoreButtonLabel(status: ModelStatus): String =
        "从安装包恢复（${formatBytes(status.spec.totalBytes)}）"

    fun canDelete(status: ModelStatus): Boolean = !status.isDownloading && !status.isImporting

    fun deleteButtonLabel(status: ModelStatus): String =
        if (status.verification == null) "删除" else if (status.isReady) "删除" else "删除残留文件"

    /** 删除前要用户确认：这一刀删掉的是 243 MB 的本机文件；好在恢复不需要网络 */
    fun deleteConfirmText(status: ModelStatus): String =
        "删掉「${status.spec.displayName}」在本机的文件（清单大小 ${formatBytes(status.spec.totalBytes)}）。" +
            "字幕与收听历史一律不动。删掉之后转录会提示模型没就位，随时可以在这一页从安装包恢复（不需要网络），播放照常可用。"

    fun restoreSucceededLabel(status: ModelStatus): String =
        "已从安装包恢复并核对通过：${status.spec.displayName}（${formatBytes(status.spec.totalBytes)}）"

    fun deletedLabel(status: ModelStatus): String =
        "已删除「${status.spec.displayName}」的本机文件。播放不受影响；要再转录，在这一页点「从安装包恢复」即可（不需要网络）"
}
