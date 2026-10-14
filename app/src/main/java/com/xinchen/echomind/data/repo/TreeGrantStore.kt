package com.xinchen.echomind.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * SAF 树授权的接缝。抽出来的理由：导入的其余步骤（遍历、去重、级联）想在仪器测试里用真库跑通，
 * 就必须能伪造「有没有授权」，而 ContentResolver 的持久化授权无法在测试里授予。
 */
interface TreeGrantStore {
    /** 把选择器返回的临时授权升级为可持久化。返回 null 表示成功，否则返回失败原因。 */
    fun persistRead(treeUri: String): String?

    fun hasReadGrant(treeUri: String): Boolean

    fun grantedTreeUris(): List<String>
}

class SafTreeGrantStore(private val context: Context) : TreeGrantStore {

    override fun persistRead(treeUri: String): String? =
        runCatching {
            // 参数里只能放模式位：带上 FLAG_GRANT_PERSISTABLE_URI_PERMISSION(0x40) 会被拿去和
            // 「该授权可持久化的模式」(0x3) 比较，API 36 上实测抛
            // IllegalArgumentException("Requested flags 0x41, but only 0x3 are allowed")
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.exceptionOrNull()?.let { "${it.javaClass.simpleName}(${it.message})" }

    override fun hasReadGrant(treeUri: String): Boolean = treeUri in grantedTreeUris()

    override fun grantedTreeUris(): List<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
}
