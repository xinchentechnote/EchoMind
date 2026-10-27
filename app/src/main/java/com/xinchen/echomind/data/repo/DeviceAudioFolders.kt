package com.xinchen.echomind.data.repo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 本机音频「伪文件夹」的 URI 约定（2026-10-27，与本机音频扫描一起引入）。
 *
 * MediaStore 导入的书没有 SAF 树授权，但 `imported_folder` 的级联删除、书架归属、
 * 转录投影全都挂在文件夹行上。为这一半逻辑再建一张表只会造出第二份「文件夹」概念，
 * 所以复用 `imported_folder`，`tree_uri` 存一个**不对应 SAF 树**的伪 URI：
 *
 * ```
 * mediastore://<volume>/<relativePath>      例：mediastore://external_primary/Music/Audiobooks
 * mediastore://external_primary/            （卷根，路径为空）
 * ```
 *
 * 判别式是前缀 [SCHEME]：SAF 树 uri 以 `content://` 开头，两者不会混淆。
 * `database-design.md` §2 已同步这个语义。
 */
object DeviceAudioFolders {
    const val SCHEME = "mediastore://"

    fun treeUri(volume: String, relativePath: String): String {
        val path = relativePath.trim('/')
        return if (path.isEmpty()) "$SCHEME$volume/" else "$SCHEME$volume/$path"
    }

    fun isDeviceFolder(treeUri: String): Boolean = treeUri.startsWith(SCHEME)

    /** 不是伪 URI 返回 null；路径为空表示卷根 */
    fun parse(treeUri: String): Pair<String, String>? {
        if (!isDeviceFolder(treeUri)) return null
        val rest = treeUri.removePrefix(SCHEME)
        val volume = rest.substringBefore('/', "")
        if (volume.isEmpty()) return null
        return volume to rest.substringAfter('/', "")
    }
}

/**
 * 本机音频读取权限的接缝（与 [TreeGrantStore] 同一理由：授权状态无法在 JVM/仪器化测试里制造）。
 * API 33+ 用 `READ_MEDIA_AUDIO`（细粒度「音乐和音频」），26-32 用 `READ_EXTERNAL_STORAGE`。
 */
interface MediaAudioAccess {
    fun isGranted(): Boolean

    /** 当前 SDK 对应的运行时权限名：UI 的权限请求与这里必须一致 */
    fun permissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
}

class AndroidMediaAudioAccess(private val context: Context) : MediaAudioAccess {
    override fun isGranted(): Boolean =
        context.checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED
}
