package com.xinchen.echomind.data.scan

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
import com.xinchen.echomind.domain.importing.DeviceAudioIndex
import com.xinchen.echomind.domain.importing.DeviceAudioSource
import com.xinchen.echomind.domain.importing.PermissionRevokedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore 扫描源（本机音频，2026-10-27 引入）。
 *
 * - API 29+：按卷查询，`RELATIVE_PATH` 直接给目录；
 * - API 26-28：查 `EXTERNAL_CONTENT_URI`，目录从 `DATA` 绝对路径推导（[DeviceAudioIndex.relativePathFromData]）；
 * - 白名单过滤在内存里做（`DeviceAudioIndex.matchesExtension`，白名单只有 `core/conf/AudioFormats` 一处）：扩展名大小写组合太多，
 *   SQL `LIKE` 拼六条不如扫完再滤，音频总量在几千行的量级，不是瓶颈。
 *
 * MediaStore URI（`content://media/<volume>/audio/media/<id>`）只有持有媒体读取权限时能打开，
 * 权限检查由仓库层经 [com.xinchen.echomind.data.repo.MediaAudioAccess] 做，这里不重复查。
 */
class MediaStoreAudioSource(private val context: Context) : DeviceAudioSource {

    override suspend fun scanAll(): List<DeviceAudioEntry> = withContext(Dispatchers.IO) {
        volumes().flatMap { volume -> queryVolume(volume, subtree = null) }
    }

    override suspend fun filesUnder(volume: String, relativePath: String): List<DeviceAudioEntry> =
        withContext(Dispatchers.IO) {
            queryVolume(volume, subtree = relativePath)
        }

    private fun volumes(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).toList()
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    /** subtree=null 查整卷；否则查该目录子树（含该层） */
    private fun queryVolume(volume: String, subtree: String?): List<DeviceAudioEntry> {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val baseUri = if (legacy) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.getContentUri(volume)
        }
        val selection: String?
        val args: Array<String>?
        when {
            subtree == null -> {
                selection = null
                args = null
            }
            legacy -> {
                // 旧系统没有 RELATIVE_PATH，只能用 DATA 绝对路径做前缀匹配；挂载点别名（/sdcard）一并覆盖
                val prefix = if (subtree.isEmpty()) "" else "${DeviceAudioIndex.normalizeRelativePath(subtree)}/"
                selection = "(" + DeviceAudioIndex.DEFAULT_ROOTS.joinToString(" OR ") {
                    "${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'"
                } + ")"
                args = DeviceAudioIndex.DEFAULT_ROOTS.map { root ->
                    DeviceAudioIndex.escapeLike("$root/$prefix") + "%"
                }.toTypedArray()
            }
            subtree.isEmpty() -> {
                selection = null
                args = null
            }
            else -> {
                val normalized = "${DeviceAudioIndex.normalizeRelativePath(subtree)}/"
                selection =
                    "(${MediaStore.Audio.Media.RELATIVE_PATH} = ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? ESCAPE '\\')"
                args = arrayOf(normalized, "${DeviceAudioIndex.escapeLike(normalized)}%")
            }
        }

        return try {
            context.contentResolver.query(baseUri, PROJECTION, selection, args, null)?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val idxSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val idxModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val idxRelative = if (legacy) -1 else cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val out = mutableListOf<DeviceAudioEntry>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(idxName) ?: continue
                    if (!DeviceAudioIndex.matchesExtension(name)) continue
                    val relativePath = if (idxRelative >= 0) {
                        DeviceAudioIndex.normalizeRelativePath(cursor.getString(idxRelative))
                    } else {
                        DeviceAudioIndex.relativePathFromData(cursor.getString(idxData) ?: "")
                    }
                    out += DeviceAudioEntry(
                        volume = volume,
                        relativePath = relativePath,
                        fileName = name,
                        uri = android.content.ContentUris.withAppendedId(baseUri, cursor.getLong(idxId)).toString(),
                        sizeBytes = cursor.getLong(idxSize),
                        lastModifiedMs = cursor.getLong(idxModified) * 1000,
                        durationMs = cursor.getLong(idxDuration),
                    )
                }
                out
            } ?: emptyList()
        } catch (e: SecurityException) {
            throw PermissionRevokedException("没有本机音频的读取权限（MediaStore 查询被拒）：$baseUri", e)
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
        ).let { base -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) base else base.dropLast(1).toTypedArray() }
    }
}
