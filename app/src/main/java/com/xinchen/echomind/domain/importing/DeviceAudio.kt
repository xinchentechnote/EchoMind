package com.xinchen.echomind.domain.importing

import com.xinchen.echomind.core.conf.AudioFormats

/**
 * MediaStore 扫描到的一个本机音频文件。纯 Kotlin：字段全部是 String/Long，
 * 让分组、排序与路径推导能在 JVM 单测里跑（与 [TreeEntry] 同一规矩）。
 *
 * `volume` 是 MediaStore 的卷名（`external_primary` / U 盘卷名…）；
 * `relativePath` 是规范化的相对目录（无首尾 `/`，`""` = 卷根）。
 */
data class DeviceAudioEntry(
    val volume: String,
    val relativePath: String,
    val fileName: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    /** MediaStore 的 DURATION 列；0 表示库里没有可信时长（WMA 等由导入方另行决定如何处理） */
    val durationMs: Long,
) {
    /** 与 audio_book.relative_path 同口径：目录/文件名，SAF 导入也这么存 */
    val bookRelativePath: String get() = if (relativePath.isEmpty()) fileName else "$relativePath/$fileName"
}

/**
 * 本机音频的「文件夹身份」：卷 + 相对路径。同一相对路径在不同卷上是两个文件夹，
 * 否则 SD 卡与内置存储的 `Music` 会被揉在一起，卸载 SD 卡后一半书 MISSING 却说不出是哪半。
 */
data class DeviceAudioFolderRef(val volume: String, val path: String) {

    /** 纯展示名：卷根显示卷名本身，其余显示最后一段 */
    val displayName: String
        get() = if (path.isEmpty()) volumeDisplayName(volume) else path.substringAfterLast('/')

    /** 卷根上面没有上级 */
    val parent: DeviceAudioFolderRef?
        get() = if (path.isEmpty()) null else DeviceAudioFolderRef(volume, path.substringBeforeLast('/', ""))

    companion object {
        /** MediaStore 主卷名（android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY 的字面值；domain 层不 import android） */
        const val VOLUME_EXTERNAL_PRIMARY = "external_primary"

        fun volumeDisplayName(volume: String): String = when (volume) {
            VOLUME_EXTERNAL_PRIMARY -> "内部存储"
            else -> volume
        }
    }
}

/** 一层目录里的内容：直接子文件夹（计数含其全部子层级）+ 该层文件，均已排序 */
data class DeviceAudioListing(
    val folders: List<FolderWithCount>,
    val files: List<DeviceAudioEntry>,
) {
    data class FolderWithCount(val ref: DeviceAudioFolderRef, val fileCount: Int)
}

/** data 层实现的「扫本机音频」能力（MediaStore 或测试替身）。接口无 android 类型。 */
interface DeviceAudioSource {
    /** 全量扫描（本机音频浏览页用）。实现必须在 IO 线程上跑。 */
    suspend fun scanAll(): List<DeviceAudioEntry>

    /** 某卷某目录子树下（含该层）的支持文件，导入与重扫用 */
    suspend fun filesUnder(volume: String, relativePath: String): List<DeviceAudioEntry>
}

/**
 * 把一批扫描结果组织成可逐层浏览的索引。纯内存结构：浏览页每次进入重新扫描重建，
 * 不落库——MediaStore 本身就是持久索引，再建一份只是把不一致的机会翻倍。
 */
class DeviceAudioIndex(private val entries: List<DeviceAudioEntry>) {

    private val byFolder: Map<DeviceAudioFolderRef, List<DeviceAudioEntry>> =
        entries.groupBy { DeviceAudioFolderRef(it.volume, it.relativePath) }

    /** 当前层的直接子文件夹（递归计数）与文件 */
    fun childrenOf(ref: DeviceAudioFolderRef): DeviceAudioListing {
        val direct = byFolder[ref].orEmpty()
        val prefix = childPrefix(ref.path)
        // 子文件夹的 ref 必须存**全路径**（Music/我的_有声书），否则下钻和整夹导入都对着一个
        // 不存在的键查（卷根层 removePrefix("") 恰好等于全路径，曾把这个 bug 盖住一轮实测）；
        // 展示名由 displayName 取最后一段。relativePath == ref.path 的是当前层自己的文件，不是子文件夹。
        val folders = entries.asSequence()
            .filter { it.volume == ref.volume && it.relativePath != ref.path && it.relativePath.startsWith(prefix) }
            .groupingBy { entry ->
                val segment = entry.relativePath.removePrefix(prefix).substringBefore('/')
                DeviceAudioFolderRef(entry.volume, if (ref.path.isEmpty()) segment else "${ref.path}/$segment")
            }
            .eachCount()
            .map { (child, count) -> DeviceAudioListing.FolderWithCount(child, count) }
            .sortedBy { it.ref.path.substringAfterLast('/').lowercase() }
        val files = direct.sortedBy { it.fileName.lowercase() }
        return DeviceAudioListing(folders, files)
    }

    /** 目录子树（含该层）下的全部文件：整夹导入用 */
    fun entriesUnder(ref: DeviceAudioFolderRef): List<DeviceAudioEntry> =
        entries.asSequence()
            .filter { it.volume == ref.volume && (it.relativePath == ref.path || it.relativePath.startsWith(childPrefix(ref.path))) }
            .sortedBy { it.bookRelativePath.lowercase() }
            .toList()

    fun isEmpty(): Boolean = entries.isEmpty()

    companion object {
        /**
         * 扩展名白名单判定（与 [AudioTreeWalker] 对 SAF 文件的判定同一规则）：
         * 无扩展名 / 以点结尾 / 大小写混合都不放行。
         */
        fun matchesExtension(fileName: String, extensions: Set<String> = AudioFormats.SUPPORTED_EXTENSIONS): Boolean {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot == fileName.length - 1) return false
            return fileName.substring(dot + 1).lowercase() in extensions
        }

        /** SQL LIKE 的转义：`_`/`%` 是通配符，目录名里出现时必须转义，否则 `My_Music` 会匹配 `MyxMusic` */
        fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** `Music` → `Music/`；卷根 `""` → `""`。前缀比较共用这一条，避免各处手拼出 `Music` 误匹配 `Music1` */
        fun childPrefix(path: String): String = if (path.isEmpty()) "" else "$path/"

        /** `Music/Podcasts/` → `Music/Podcasts`；`/Music` → `Music`；null/空 → `""`（卷根） */
        fun normalizeRelativePath(raw: String?): String =
            (raw ?: "").trim('/').let { if (it == "/") "" else it }

        /**
         * API 29 之前没有 RELATIVE_PATH，从 DATA 绝对路径推导目录。
         * 推不出（未知卷、卷根本身）返回 `""`，由调用方并进卷根显示——不伪造路径。
         */
        fun relativePathFromData(dataPath: String, roots: List<String> = DEFAULT_ROOTS): String {
            val dir = dataPath.substringBeforeLast('/', "")
            if (dir.isEmpty()) return ""
            for (root in roots) {
                if (dir == root) return ""
                if (dir.startsWith("$root/")) return normalizeRelativePath(dir.removePrefix(root))
            }
            return ""
        }

        /** API 29 之前最常见的内部存储挂载点；测试可注入别的根 */
        val DEFAULT_ROOTS: List<String> = listOf("/storage/emulated/0", "/sdcard")
    }
}
