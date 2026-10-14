package com.xinchen.echomind.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一个被授权的文件夹（SAF 树）。移除它会级联删掉其下书籍记录，但收听历史保留。 */
@Entity(
    tableName = "imported_folder",
    indices = [Index(value = ["tree_uri"], unique = true)],
)
data class ImportedFolderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "granted_at_ms") val grantedAtMs: Long,
    @ColumnInfo(name = "last_scan_at_ms") val lastScanAtMs: Long = 0,
    /** GRANTED / REVOKED（FR-110） */
    @ColumnInfo(name = "permission_state") val permissionState: String = PermissionState.Granted,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean = false,
) {
    object PermissionState {
        const val Granted = "GRANTED"
        const val Revoked = "REVOKED"
    }
}
