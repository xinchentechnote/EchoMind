package com.xinchen.echomind.domain.importing

import com.xinchen.echomind.domain.model.ExistingAudio
import com.xinchen.echomind.domain.model.ImportPlan
import com.xinchen.echomind.domain.model.ScannedAudioFile

/**
 * 扫描结果与库内记录的差量判定（FR-106 去重 / FR-107 变更检测 / FR-108 消失检测）。
 *
 * 去重键是 URI 本身：SAF 的 document URI 内含 documentId，同一文件夹重复扫描必然相同。
 * 同一 URI 出现两次（理论上不该发生，但树里可能有符号链接式重复）按第一次处理。
 */
object AudioImportPlanner {

    fun plan(existing: List<ExistingAudio>, scanned: List<ScannedAudioFile>): ImportPlan {
        val existingByUri = HashMap<String, ExistingAudio>(existing.size)
        for (e in existing) existingByUri[e.uri] = e

        val seen = HashSet<String>(scanned.size)
        val newFiles = ArrayList<ScannedAudioFile>()
        val changed = ArrayList<ImportPlan.Change>()
        val revived = ArrayList<String>()
        val presentUris = HashSet<String>(scanned.size)

        for (file in scanned) {
            if (!seen.add(file.uri)) continue
            presentUris.add(file.uri)
            val old = existingByUri[file.uri]
            when {
                old == null -> newFiles.add(file)
                old.sizeBytes != file.sizeBytes || old.lastModifiedMs != file.lastModifiedMs ->
                    changed.add(ImportPlan.Change(old, file))
                old.fileMissing -> revived.add(old.id)
            }
        }

        val vanished = existing.filter { it.uri !in presentUris }.map { it.id }
        val unchanged = scanned.size - newFiles.size - changed.size - revived.size

        return ImportPlan(
            newFiles = newFiles,
            changed = changed,
            vanishedIds = vanished,
            revivedIds = revived,
            unchangedCount = unchanged.coerceAtLeast(0),
        )
    }
}
