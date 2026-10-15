package com.xinchen.echomind.data.repo

import android.net.Uri
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.data.db.AudioBookDao
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.ImportedFolderDao
import com.xinchen.echomind.data.db.ImportedFolderEntity
import com.xinchen.echomind.data.db.ShelfDao
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.scan.AudioMetadataReader
import com.xinchen.echomind.domain.importing.AudioImportPlanner
import com.xinchen.echomind.domain.importing.AudioTreeWalker
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import com.xinchen.echomind.domain.importing.TreeBrowser
import com.xinchen.echomind.domain.model.ExistingAudio
import com.xinchen.echomind.domain.model.ScannedAudioFile
import com.xinchen.echomind.domain.model.TranscriptionTarget
import com.xinchen.echomind.domain.text.TitleDeriver
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

enum class ShelfTab { All, Recent, Favorites }

/** 一次导入/重扫的结果统计（UI 提示与测试断言都用它） */
data class ImportOutcome(
    val folderId: String,
    val added: Int,
    val changed: Int,
    val vanished: Int,
    /** 之前 MISSING、这次又扫到的文件数 */
    val revived: Int,
    val unchanged: Int,
    /** 同一文件被两个文件夹覆盖到时，书留在首次导入它的文件夹，不重复建条目（FR-106） */
    val alreadyOnShelf: Int,
    val truncated: Boolean,
    /** 重扫时该文件夹的授权已失效：本次跳过它，其他文件夹继续扫（FR-110） */
    val permissionRevoked: Boolean = false,
)

interface AudioRepository {
    fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>>
    fun lastPlayed(): Flow<ShelfRow?>
    fun folders(): Flow<List<ImportedFolderEntity>>

    /**
     * 转录要读的那一本书（`asr/TranscriptCoordinator` 的唯一入口）。
     *
     * worker 也不许直接查 DAO（`domain/transcript/TranscriptRepository` 的约定），
     * 所以「 uri 在哪、文件还在不在、要不要 SAF 授权、授权挂在哪棵树上」由这里投影成纯 Kotlin 的 [TranscriptionTarget]。
     * 树 uri 必须在这里一起给出：文档 uri 与树 uri 长得不一样，拿文档 uri 查授权必然查不到。
     */
    suspend fun transcriptionTarget(audioId: String): TranscriptionTarget?

    suspend fun importFolder(treeUri: Uri): ImportOutcome
    suspend fun rescanAll(): List<ImportOutcome>
    suspend fun rename(audioId: String, title: String)
    suspend fun setFavorite(audioId: String, favorite: Boolean)
    suspend fun removeBook(audioId: String)
    suspend fun removeFolder(folderId: String)
    suspend fun markPermissionRevoked(folderId: String)
}

/** 由 URI 派生稳定 ID：同一本书从书架删除后重新导入，历史统计能自动接上（database-design.md §8） */
fun stableIdFromUri(uri: String): String = UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8)).toString()

class AudioRepositoryImpl(
    private val audioBookDao: AudioBookDao,
    private val folderDao: ImportedFolderDao,
    private val shelfDao: ShelfDao,
    private val treeBrowser: TreeBrowser,
    private val metadataReader: AudioMetadataReader,
    private val grants: TreeGrantStore,
    private val nowMs: () -> Long,
) : AudioRepository {

    override fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>> = when (tab) {
        ShelfTab.All -> shelfDao.observeAll(query)
        ShelfTab.Recent -> shelfDao.observeRecent(query)
        ShelfTab.Favorites -> shelfDao.observeFavorites(query)
    }

    override fun lastPlayed(): Flow<ShelfRow?> = shelfDao.observeLastPlayed()

    override fun folders(): Flow<List<ImportedFolderEntity>> = folderDao.observeAll()

    override suspend fun transcriptionTarget(audioId: String): TranscriptionTarget? =
        audioBookDao.getById(audioId)?.let { book ->
            val needsTreeGrant = book.uri.startsWith("content://")
            TranscriptionTarget(
                audioId = book.id,
                uri = book.uri,
                title = book.title,
                isFilePresent = book.fileStatus == AudioBookEntity.FileStatus.Present,
                needsTreeGrant = needsTreeGrant,
                // 只在 SAF 那侧查：folder_id 是外键（ON DELETE CASCADE），书还在则文件夹行必在
                treeUri = if (needsTreeGrant) folderDao.byId(book.folderId)?.treeUri else null,
            )
        }

    override suspend fun importFolder(treeUri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val uriText = treeUri.toString()
        // 用户可能在系统里撤销过授权；takePersistable 幂等，重复调用无害。
        // 失败原因不能静默吞掉，否则「刚授权却提示失效」无从定位。
        val takeError = grants.persistRead(uriText)
        val folderId = stableIdFromUri(uriText)
        val existingFolder = folderDao.byTreeUri(uriText)
        if (!grants.hasReadGrant(uriText)) {
            folderDao.setPermissionState(folderId, ImportedFolderEntity.PermissionState.Revoked)
            throw PermissionRevokedException(
                "没有该文件夹的可持久化读取授权：tree=$uriText；" +
                    "takePersistable=$takeError；已授权列表=${grants.grantedTreeUris()}",
            )
        }
        val now = nowMs()
        val folder = existingFolder ?: ImportedFolderEntity(
            id = folderId,
            treeUri = uriText,
            displayName = treeBrowser.treeDisplayName(uriText) ?: "未命名文件夹",
            grantedAtMs = now,
        )
        folderDao.upsert(folder.copy(permissionState = ImportedFolderEntity.PermissionState.Granted))

        val walk = AudioTreeWalker(treeBrowser, AudioFormats.SUPPORTED_EXTENSIONS).walk(uriText)
        val scanned = walk.files.map { file ->
            // 时长读取失败不阻塞导入：durationMs=0 + durationReadFailed（architecture §8）
            val duration = metadataReader.durationMs(file.uri)
            file to duration
        }

        val existing = audioBookDao.existingForFolder(folderId).map {
            ExistingAudio(it.id, it.uri, it.sizeBytes, it.lastModifiedMs, it.titleIsManual, it.fileMissing)
        }
        val plan = AudioImportPlanner.plan(existing, scanned.map { it.first })

        val newUris = plan.newFiles.map { it.uri }.toSet()
        val newEntities = scanned.filter { it.first.uri in newUris }.map { (file, duration) ->
            AudioBookEntity(
                id = stableIdFromUri(file.uri),
                uri = file.uri,
                folderId = folderId,
                relativePath = file.relativePath,
                fileName = file.fileName,
                title = TitleDeriver.defaultTitle(file.fileName),
                titleIsManual = false,
                sizeBytes = file.sizeBytes,
                fileLastModifiedMs = file.lastModifiedMs,
                durationMs = duration ?: 0,
                durationReadFailed = duration == null,
                isFavorite = false,
                fileStatus = AudioBookEntity.FileStatus.Present,
                transcriptionState = AudioBookEntity.TranscriptionState.None,
                addedAtMs = now,
                lastScannedAtMs = now,
                updatedAtMs = now,
            )
        }
        // 同一文件被两棵树覆盖到时保留原条目：不重新挂接文件夹，否则书架状态会在两个文件夹间抖动
        val alreadyOnShelf = if (newEntities.isEmpty()) emptySet() else audioBookDao.presentIds(newEntities.map { it.id }).toSet()
        val entitiesToInsert = newEntities.filterNot { it.id in alreadyOnShelf }
        audioBookDao.upsertAll(entitiesToInsert)

        val durationByUri = scanned.associate { it.first.uri to it.second }
        for (change in plan.changed) {
            val duration = durationByUri[change.scanned.uri]
            val current = audioBookDao.getById(change.existing.id)
            if (current == null) continue
            audioBookDao.upsert(
                current.copy(
                    relativePath = change.scanned.relativePath,
                    fileName = change.scanned.fileName,
                    title = if (current.titleIsManual) current.title else TitleDeriver.defaultTitle(change.scanned.fileName),
                    sizeBytes = change.scanned.sizeBytes,
                    fileLastModifiedMs = change.scanned.lastModifiedMs,
                    durationMs = duration ?: current.durationMs,
                    durationReadFailed = duration == null,
                    fileStatus = AudioBookEntity.FileStatus.Present,
                    // 内容变了，旧字幕不再可信（FR-107）
                    transcriptionState = if (current.transcriptionState == AudioBookEntity.TranscriptionState.Ready) {
                        AudioBookEntity.TranscriptionState.Expired
                    } else {
                        current.transcriptionState
                    },
                    lastScannedAtMs = now,
                    updatedAtMs = now,
                ),
            )
        }

        if (plan.vanishedIds.isNotEmpty()) {
            audioBookDao.setFileStatus(plan.vanishedIds, AudioBookEntity.FileStatus.Missing, now)
        }
        // 文件回来了（U 盘重插、误删后恢复）：内容没变也必须翻回 PRESENT，否则书架永远灰着
        if (plan.revivedIds.isNotEmpty()) {
            audioBookDao.setFileStatus(plan.revivedIds, AudioBookEntity.FileStatus.Present, now)
        }
        folderDao.markScanned(folderId, now)

        ImportOutcome(
            folderId = folderId,
            added = entitiesToInsert.size,
            changed = plan.changed.size,
            vanished = plan.vanishedIds.size,
            revived = plan.revivedIds.size,
            unchanged = plan.unchangedCount,
            alreadyOnShelf = alreadyOnShelf.size,
            truncated = walk.truncated,
        )
    }

    /** 一个文件夹失去授权不能拖垮整批重扫：它标成 REVOKED 并回报，其余照常扫描（FR-110） */
    override suspend fun rescanAll(): List<ImportOutcome> = folderDao.getAll().map { folder ->
        if (!grants.hasReadGrant(folder.treeUri)) {
            folderDao.setPermissionState(folder.id, ImportedFolderEntity.PermissionState.Revoked)
            ImportOutcome(
                folderId = folder.id,
                added = 0,
                changed = 0,
                vanished = 0,
                revived = 0,
                unchanged = 0,
                alreadyOnShelf = 0,
                truncated = false,
                permissionRevoked = true,
            )
        } else {
            importFolder(Uri.parse(folder.treeUri))
        }
    }

    override suspend fun rename(audioId: String, title: String) =
        audioBookDao.rename(audioId, title.trim(), nowMs())

    override suspend fun setFavorite(audioId: String, favorite: Boolean) =
        audioBookDao.setFavorite(audioId, favorite, nowMs())

    /** 删书：级联删播放记忆，但 listening_session 无外键，历史保留（NFR-04） */
    override suspend fun removeBook(audioId: String) {
        audioBookDao.delete(audioId)
    }

    override suspend fun removeFolder(folderId: String) {
        folderDao.delete(folderId)
    }

    override suspend fun markPermissionRevoked(folderId: String) =
        folderDao.setPermissionState(folderId, ImportedFolderEntity.PermissionState.Revoked)
}
