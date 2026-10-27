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
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
import com.xinchen.echomind.domain.importing.DeviceAudioFolderRef
import com.xinchen.echomind.domain.importing.DeviceAudioSource
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
    /** 已交给后台准备的 WMA 数量；不计入导入 busy，避免一部长书拖住整批。 */
    val preparingWma: Int = 0,
)

/**
 * WMA 后台准备的接缝（实现在 [AsyncWmaImportPreparer]，AppGraph 装配）。
 * [schedule] 必须立即返回；耗时转码不能占住“导入中”的 UI 状态。
 */
interface WmaImportPreparer {
    fun schedule(audioId: String, uri: String, force: Boolean = false): Boolean
    fun cancel(audioId: String)
}

interface AudioRepository {
    fun shelf(tab: ShelfTab, query: String): Flow<List<ShelfRow>>
    fun lastPlayed(): Flow<ShelfRow?>
    fun folders(): Flow<List<ImportedFolderEntity>>

    /**
     * 转录要读的那一本书（`asr/TranscriptCoordinator` 的唯一入口）。
     *
     * worker 也不许直接查 DAO（`domain/transcript/TranscriptRepository` 的约定），
     * 所以「 uri 在哪、文件还在不在、要什么权限」由这里投影成纯 Kotlin 的 [TranscriptionTarget]：
     * SAF 书需要树授权（[TranscriptionTarget.needsTreeGrant]），本机音频书需要媒体读取权限
     * （[TranscriptionTarget.needsMediaPermission]）。
     */
    suspend fun transcriptionTarget(audioId: String): TranscriptionTarget?

    suspend fun importFolder(treeUri: Uri): ImportOutcome

    /**
     * 本机音频（MediaStore 伪文件夹，见 [DeviceAudioFolders]）导入：该目录子树（含子层级）。
     * 媒体读取权限缺失时抛 [PermissionRevokedException]。
     */
    suspend fun importDeviceFolder(volume: String, relativePath: String): ImportOutcome

    /** 本机音频单文件导入：只差量这一个文件，同伪文件夹下的其他书不受影响 */
    suspend fun importDeviceFile(file: DeviceAudioEntry): ImportOutcome

    /** 全量扫描本机支持格式的音频（本机音频浏览页每次进入重建索引，不落库） */
    suspend fun scanDeviceAudio(): List<DeviceAudioEntry>

    /** 当前在书架上的全部 uri（本机音频页的「已在书架」角标） */
    suspend fun deviceImportedUris(): Set<String>

    /** 本机音频的运行时权限状态：UI 的权限门与请求都以此为准 */
    val mediaAudioAccess: MediaAudioAccess?

    suspend fun rescanAll(): List<ImportOutcome>

    /** 只重扫一个文件夹（书架目录管理的「重扫此文件夹」）；文件夹已删则抛 [NoSuchElementException] */
    suspend fun rescanFolder(folderId: String): ImportOutcome
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
    /** WMA 转码产物的存放/取用约定；null（部分测试）= 不做 WMA 转码，.wma 照样导入但播放会失败 */
    private val wmaStore: WmaPlaybackStore? = null,
    /** WMA 后台准备器；null（部分测试）时只入库，不生成可播放的 m4a。 */
    private val wmaPrepare: WmaImportPreparer? = null,
    /** 本机音频（MediaStore）扫描源；null（部分测试）= 本机音频导入不可用 */
    private val deviceSource: DeviceAudioSource? = null,
    /** 本机音频读取权限；与 [deviceSource] 成对装配 */
    private val mediaAudio: MediaAudioAccess? = null,
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
            // folder_id 是外键（ON DELETE CASCADE），书还在则文件夹行必在
            val folderTreeUri = folderDao.byId(book.folderId)?.treeUri
            val isDevice = folderTreeUri != null && DeviceAudioFolders.isDeviceFolder(folderTreeUri)
            TranscriptionTarget(
                audioId = book.id,
                uri = book.uri,
                title = book.title,
                fileName = book.fileName,
                isFilePresent = book.fileStatus == AudioBookEntity.FileStatus.Present,
                needsTreeGrant = !isDevice && book.uri.startsWith("content://"),
                needsMediaPermission = isDevice,
                treeUri = folderTreeUri,
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
            // WMA 不交给系统解码器探测：真机厂商实现可能长时间卡住，真实时长由后台转码回填。
            val duration = if (WmaPlaybackStore.isWma(file.fileName)) null else metadataReader.durationMs(file.uri)
            file to duration
        }
        importScanned(folder, scanned, truncated = walk.truncated)
    }

    override suspend fun importDeviceFolder(volume: String, relativePath: String): ImportOutcome =
        withContext(Dispatchers.IO) {
            ensureMediaAudioGranted()
            val source = deviceSource ?: error("本机音频导入未装配 DeviceAudioSource")
            val uriText = DeviceAudioFolders.treeUri(volume, relativePath)
            val folder = upsertDeviceFolder(uriText, volume, relativePath)
            importScanned(
                folder,
                source.filesUnder(volume, relativePath).map { it.toScanned() to it.readDuration() },
                truncated = false,
            )
        }

    override suspend fun importDeviceFile(file: DeviceAudioEntry): ImportOutcome = withContext(Dispatchers.IO) {
        ensureMediaAudioGranted()
        val uriText = DeviceAudioFolders.treeUri(file.volume, file.relativePath)
        val folder = upsertDeviceFolder(uriText, file.volume, file.relativePath)
        // restrictToUri：差量只看这一个文件。不过滤的话，existingForFolder 会把同伪文件夹下
        // 这次没扫的其他书全标成 MISSING——单文件导入只知道自己这一个文件在不在。
        importScanned(
            folder,
            listOf(file.toScanned() to file.readDuration()),
            truncated = false,
            restrictToUri = file.uri,
        )
    }

    override suspend fun scanDeviceAudio(): List<DeviceAudioEntry> = withContext(Dispatchers.IO) {
        val source = deviceSource ?: error("本机音频扫描未装配 DeviceAudioSource")
        source.scanAll()
    }

    override suspend fun deviceImportedUris(): Set<String> = audioBookDao.allUris().toSet()

    override val mediaAudioAccess: MediaAudioAccess? get() = mediaAudio

    /** 全量重扫 = 对每个文件夹跑同一段分支逻辑（FR-110：失效只跳过，不拖垮整批） */
    override suspend fun rescanAll(): List<ImportOutcome> = folderDao.getAll().map { rescanOne(it) }

    override suspend fun rescanFolder(folderId: String): ImportOutcome {
        val folder = folderDao.byId(folderId) ?: throw NoSuchElementException("文件夹已不存在：$folderId")
        return rescanOne(folder)
    }

    private suspend fun rescanOne(folder: ImportedFolderEntity): ImportOutcome = when {
        DeviceAudioFolders.isDeviceFolder(folder.treeUri) -> {
            val volumePath = DeviceAudioFolders.parse(folder.treeUri)
                ?: error("伪文件夹 tree_uri 无法解析：${folder.treeUri}")
            // 装配漏了权限接缝时宁可炸出来，也不能把「没检查」当成「没授权」误标 REVOKED
            val access = mediaAudio ?: error("重扫遇到本机音频伪文件夹（${folder.treeUri}）但装配缺少 MediaAudioAccess")
            if (!access.isGranted()) {
                folderDao.setPermissionState(folder.id, ImportedFolderEntity.PermissionState.Revoked)
                revokedOutcome(folder.id)
            } else {
                importDeviceFolder(volumePath.first, volumePath.second)
            }
        }

        !grants.hasReadGrant(folder.treeUri) -> {
            folderDao.setPermissionState(folder.id, ImportedFolderEntity.PermissionState.Revoked)
            revokedOutcome(folder.id)
        }

        else -> importFolder(Uri.parse(folder.treeUri))
    }

    private fun revokedOutcome(folderId: String) = ImportOutcome(
        folderId = folderId,
        added = 0,
        changed = 0,
        vanished = 0,
        revived = 0,
        unchanged = 0,
        alreadyOnShelf = 0,
        truncated = false,
        permissionRevoked = true,
    )

    override suspend fun rename(audioId: String, title: String) =
        audioBookDao.rename(audioId, title.trim(), nowMs())

    override suspend fun setFavorite(audioId: String, favorite: Boolean) =
        audioBookDao.setFavorite(audioId, favorite, nowMs())

    /** 删书：级联删播放记忆，但 listening_session 无外键，历史保留（NFR-04）；WMA 书连转码产物一起清 */
    override suspend fun removeBook(audioId: String) {
        // 有准备器时，取消与文件清理由它在同一把 generation 锁内完成。这里再删一次会留下窗口：
        // 快速重导入的新任务刚创建 `.part`，却被旧 removeBook 的第二次 delete 误删。
        if (wmaPrepare != null) wmaPrepare.cancel(audioId) else wmaStore?.delete(audioId)
        audioBookDao.delete(audioId)
    }

    override suspend fun removeFolder(folderId: String) {
        folderDao.delete(folderId)
    }

    override suspend fun markPermissionRevoked(folderId: String) =
        folderDao.setPermissionState(folderId, ImportedFolderEntity.PermissionState.Revoked)

    // ── 内部：SAF 与 MediaStore 两条导入路共享的差量管线 ─────────────────────────────

    /**
     * 拿到扫描结果之后的所有事：差量判定、插新、改变更、标消失/回来、WMA 后台准备、markScanned。
     * SAF 树与本机伪文件夹只在前半段（怎么扫出 [ScannedAudioFile] 列表）不同，后半段必须同一份。
     */
    private suspend fun importScanned(
        folder: ImportedFolderEntity,
        scanned: List<Pair<ScannedAudioFile, Long?>>,
        truncated: Boolean,
        restrictToUri: String? = null,
    ): ImportOutcome {
        val now = nowMs()
        val existing = audioBookDao.existingForFolder(folder.id).map {
            ExistingAudio(it.id, it.uri, it.sizeBytes, it.lastModifiedMs, it.titleIsManual, it.fileMissing)
        }.let { list -> if (restrictToUri == null) list else list.filter { e -> e.uri == restrictToUri } }
        val plan = AudioImportPlanner.plan(existing, scanned.map { it.first })

        val newUris = plan.newFiles.map { it.uri }.toSet()
        val newEntities = scanned.filter { it.first.uri in newUris }.map { (file, duration) ->
            AudioBookEntity(
                id = stableIdFromUri(file.uri),
                uri = file.uri,
                folderId = folder.id,
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
        val entitiesToInsert = newEntities
            .filterNot { it.id in alreadyOnShelf }
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
        // v0.1.2 同步转码被强停时可能留下非空但未完成的 `<id>.m4a`。
        // NEW/CHANGED 都强制重做，不能只凭旧文件非空就把它当成可播放产物。
        val forcePrepareIds = plan.changed.mapTo(HashSet()) { it.existing.id }
        forcePrepareIds += entitiesToInsert.map { it.id }
        val preparingWma = scanned.count { (file, _) ->
            if (!WmaPlaybackStore.isWma(file.fileName)) return@count false
            val audioId = stableIdFromUri(file.uri)
            wmaPrepare?.schedule(audioId, file.uri, force = audioId in forcePrepareIds) == true
        }
        folderDao.markScanned(folder.id, now)

        return ImportOutcome(
            folderId = folder.id,
            added = entitiesToInsert.size,
            changed = plan.changed.size,
            vanished = plan.vanishedIds.size,
            revived = plan.revivedIds.size,
            unchanged = plan.unchangedCount,
            alreadyOnShelf = alreadyOnShelf.size,
            truncated = truncated,
            preparingWma = preparingWma,
        )
    }

    /** MediaStore 伪文件夹行：首次出现就建，重扫/单文件导入复用同一行 */
    private suspend fun upsertDeviceFolder(treeUri: String, volume: String, relativePath: String): ImportedFolderEntity {
        val folderId = stableIdFromUri(treeUri)
        val existing = folderDao.byTreeUri(treeUri)
        val now = nowMs()
        // 伪文件夹没有「授权时刻」，grantedAtMs 记首次导入时刻，语义是「开始用这个文件夹的时间」
        val folder = existing ?: ImportedFolderEntity(
            id = folderId,
            treeUri = treeUri,
            displayName = deviceFolderDisplayName(volume, relativePath),
            grantedAtMs = now,
        )
        folderDao.upsert(folder.copy(permissionState = ImportedFolderEntity.PermissionState.Granted))
        return folder
    }

    private fun deviceFolderDisplayName(volume: String, relativePath: String): String {
        val volumeName = DeviceAudioFolderRef.volumeDisplayName(volume)
        val path = relativePath.trim('/')
        return if (path.isEmpty()) volumeName else "$volumeName/$path"
    }

    private fun ensureMediaAudioGranted() {
        val access = mediaAudio ?: error("本机音频导入需要装配 MediaAudioAccess")
        if (!access.isGranted()) {
            throw PermissionRevokedException("没有「音乐和音频」读取权限，无法读取本机音频（${access.permissionName()}）")
        }
    }

    /** MediaStore 行 → 导入差量判定的输入；书的 relative_path 与 SAF 导入同口径（目录/文件名） */
    private fun DeviceAudioEntry.toScanned() = ScannedAudioFile(
        uri = uri,
        relativePath = bookRelativePath,
        fileName = fileName,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
    )

    /** WMA 与「MediaStore 没给时长」都返回 null（= 时长未知），真实时长由后台转码回填 */
    private fun DeviceAudioEntry.readDuration(): Long? =
        if (WmaPlaybackStore.isWma(fileName)) null else durationMs.takeIf { it > 0 }
}
