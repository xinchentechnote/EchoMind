package com.xinchen.echomind.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedFolderDao {

    /** 同样不能用 REPLACE：它会级联删掉该文件夹下的全部书与播放记忆 */
    @Upsert
    suspend fun upsert(folder: ImportedFolderEntity)

    @Query("SELECT * FROM imported_folder ORDER BY granted_at_ms ASC")
    suspend fun getAll(): List<ImportedFolderEntity>

    @Query("SELECT * FROM imported_folder ORDER BY granted_at_ms ASC")
    fun observeAll(): Flow<List<ImportedFolderEntity>>

    @Query("SELECT * FROM imported_folder WHERE tree_uri = :treeUri LIMIT 1")
    suspend fun byTreeUri(treeUri: String): ImportedFolderEntity?

    /** 转录前查这本书所在文件夹的树 uri：授权发在树上，文档 uri 查不出授权（见 `TranscriptionTarget.treeUri`） */
    @Query("SELECT * FROM imported_folder WHERE id = :folderId LIMIT 1")
    suspend fun byId(folderId: String): ImportedFolderEntity?

    @Query("UPDATE imported_folder SET last_scan_at_ms = :nowMs WHERE id = :folderId")
    suspend fun markScanned(folderId: String, nowMs: Long)

    @Query("UPDATE imported_folder SET permission_state = :state WHERE id = :folderId")
    suspend fun setPermissionState(folderId: String, state: String)

    @Query("DELETE FROM imported_folder WHERE id = :folderId")
    suspend fun delete(folderId: String)
}
