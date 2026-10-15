package com.xinchen.echomind.ui.settings

import com.xinchen.echomind.asr.ModelFileSpec
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.ModelRole
import com.xinchen.echomind.asr.ModelStatus
import com.xinchen.echomind.asr.ModelVerification
import com.xinchen.echomind.ui.common.formatBytes
import java.net.URI

/**
 * 设置页「模型」那一节的文字（FR-510 / FR-901）。
 *
 * 与 `TranscriptDisplay` 同一个理由写成纯 Kotlin：「模型不可用时该给用户什么动作」这条要能在 JVM 里断言
 * （AGENTS.md §6）。**大小、哈希、就绪与否一律读 `ModelStatus`/`ModelCatalog`**，这里一个都不重算
 * （FR-901 明写了 UI 只许读它们）。
 */
object ModelDisplay {

    /** 这一节的开头一句：把 FR-501 的边界说清楚，别让用户以为转录要联网 */
    const val SECTION_NOTE =
        "转录只用本机模型，不调任何在线识别接口。准备模型这一步需要联网（或者从本地文件导入）；" +
            "准备好之后飞行模式也能转录。两个包都就绪了才能开始转录。"

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
        status.isImporting -> "导入中"
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
     * 下载源只报主机名：整条 URL 对用户没有信息量，还会占满一行（用户偏好：路径不外露）。
     * 解析不出来就退回「见清单」，不猜。
     */
    fun sourceLabel(fileSpec: ModelFileSpec): String {
        val hosts = fileSpec.candidateUrls.mapNotNull { hostOf(it) }.distinct()
        if (hosts.isEmpty()) return "来源见模型清单"
        val backups = fileSpec.candidateUrls.size - 1
        return if (backups > 0) "来源 ${hosts.first()}（另有 $backups 个备用地址）" else "来源 ${hosts.first()}"
    }

    /**
     * 进度那一行。总数未知（服务端没给 `Content-Length`）时只报已收字节，**不报百分比**——
     * 与转录页同一条规矩：假进度比没有进度更糟。
     */
    fun progressLabel(status: ModelStatus): String? {
        val progress = status.download ?: return null
        val verb = if (status.isImporting) "正在导入" else "正在下载"
        if (progress.totalBytes <= 0) {
            return "$verb ${progress.fileName}：已收 ${formatBytes(progress.receivedBytes)}（对方没给总大小，不报百分比）"
        }
        val percent = (progress.receivedBytes * 100 / progress.totalBytes).coerceIn(0, 100)
        return "$verb ${progress.fileName}：${formatBytes(progress.receivedBytes)} / " +
            "${formatBytes(progress.totalBytes)}（$percent%）"
    }

    /** 进度条的比例；总数未知时返回 null，页面据此不画进度条 */
    fun progressFraction(status: ModelStatus): Float? {
        val progress = status.download ?: return null
        if (progress.totalBytes <= 0) return null
        return (progress.receivedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
    }

    /** 已就绪的包不给下载按钮：`download` 会逐文件跳过已合格的，点下去什么也不会发生 */
    fun canDownload(status: ModelStatus): Boolean =
        !status.isReady && !status.isDownloading && !status.isImporting

    /** 断点续传只到文件级，所以「有些文件已经在」时说「继续」比说「下载」准确 */
    fun downloadButtonLabel(status: ModelStatus): String {
        val size = formatBytes(status.spec.totalBytes)
        val failures = status.verification?.failures
        val partial = failures != null && failures.size < status.spec.files.size
        return if (partial) "继续下载（$size）" else "下载（$size）"
    }

    fun canImport(status: ModelStatus): Boolean = !status.isDownloading && !status.isImporting

    fun canDelete(status: ModelStatus): Boolean = !status.isDownloading && !status.isImporting

    fun deleteButtonLabel(status: ModelStatus): String =
        if (status.verification == null) "删除" else if (status.isReady) "删除" else "删除残留文件"

    /** 删除前要用户确认：这一刀删掉的是 243 MB 的本机文件，重新取回要么联网要么再导一次 */
    fun deleteConfirmText(status: ModelStatus): String =
        "删掉「${status.spec.displayName}」在本机的文件（清单大小 ${formatBytes(status.spec.totalBytes)}）。" +
            "字幕与收听历史一律不动。删掉之后转录会失败并提示模型没就位，播放照常可用。"

    fun importSucceededLabel(verification: ModelVerification, fileName: String): String =
        if (verification.isReady) {
            "已导入并核对通过：$fileName。这个包现在齐了"
        } else {
            "已导入并核对通过：$fileName。这个包还差：${problemLabel(verification)}"
        }

    fun downloadSucceededLabel(status: ModelStatus): String =
        "已取回并核对通过：${status.spec.displayName}（${formatBytes(status.spec.totalBytes)}）"

    fun deletedLabel(status: ModelStatus): String =
        "已删除「${status.spec.displayName}」的本机文件。播放不受影响；要再转录得先把它准备回来"

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}
