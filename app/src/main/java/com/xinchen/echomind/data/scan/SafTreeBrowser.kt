package com.xinchen.echomind.data.scan

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xinchen.echomind.domain.importing.TreeBrowser
import com.xinchen.echomind.domain.importing.TreeEntry

/**
 * 用 SAF 的 DocumentFile 列目录。DocumentFile 每次 listFiles 都要走一次 ContentProvider
 * binder 调用，所以遍历必须在 IO 线程（FR-111）。
 */
class SafTreeBrowser(private val context: Context) : TreeBrowser {

    override fun children(dirUri: String): List<TreeEntry> {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri))
            ?: throw IllegalStateException("无法打开目录：$dirUri")
        return dir.listFiles().map { file ->
            TreeEntry(
                name = file.name ?: "",
                uri = file.uri.toString(),
                isDirectory = file.isDirectory,
                sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                lastModifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
            )
        }
    }

    /** 树根自身的显示名（设置页与文件夹列表用） */
    override fun treeDisplayName(treeUri: String): String? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
}
