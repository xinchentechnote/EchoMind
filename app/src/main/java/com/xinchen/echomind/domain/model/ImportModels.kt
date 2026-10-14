package com.xinchen.echomind.domain.model

/** 扫描到的一个音频文件（尚未入库）。uri 用字符串保存，domain 层不依赖 Android 类型。 */
data class ScannedAudioFile(
    val uri: String,
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/** 库里已有的、参与去重判定的最小信息 */
data class ExistingAudio(
    val id: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val titleIsManual: Boolean,
    /** 上次扫描时文件不在（FR-108 标过 MISSING）；回来后要翻回 PRESENT */
    val fileMissing: Boolean = false,
)

/**
 * 一次扫描的差量结果（FR-106/107/108）。
 * 纯数据，便于在 JVM 里对任意输入序列断言。
 */
data class ImportPlan(
    val newFiles: List<ScannedAudioFile>,
    /** 同一 URI 但 size 或 mtime 变了：需要更新元数据并让旧字幕失效 */
    val changed: List<Change>,
    /** 库里还在、树里已经找不到的：标记 MISSING，不删行、不删历史 */
    val vanishedIds: List<String>,
    /** 之前标成 MISSING、这次又扫到的：内容没变也必须翻回 PRESENT，否则移动回来的文件永远灰着 */
    val revivedIds: List<String>,
    val unchangedCount: Int,
) {
    data class Change(val existing: ExistingAudio, val scanned: ScannedAudioFile) {
        val sizeChanged: Boolean = existing.sizeBytes != scanned.sizeBytes
        val modifiedChanged: Boolean = existing.lastModifiedMs != scanned.lastModifiedMs
    }
}
