package com.xinchen.echomind.domain.importing

import com.xinchen.echomind.domain.model.ScannedAudioFile

/** 树里的一个条目（目录或文件）。uri 用字符串，保证这段逻辑能在 JVM 单测里跑。 */
data class TreeEntry(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/** 由 data 层实现的「列目录」能力（SAF DocumentFile 或测试替身） */
interface TreeBrowser {
    fun children(dirUri: String): List<TreeEntry>

    /** 树根的显示名；读不到返回 null，由调用方兜底 */
    fun treeDisplayName(treeUri: String): String?
}

/** 授权被用户撤销（读取时抛 SecurityException） */
class PermissionRevokedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 递归遍历音频文件（FR-103）。
 *
 * 深度与数量上限是防呆：SAF 树上出现自引用或异常巨大的目录时不能把主进程拖死。
 */
class AudioTreeWalker(
    private val browser: TreeBrowser,
    private val extensions: Set<String>,
    private val maxDepth: Int = 12,
    private val maxFiles: Int = 20_000,
) {
    fun walk(rootUri: String, rootPrefix: String = ""): WalkResult {
        val out = ArrayList<ScannedAudioFile>()
        var truncated = false
        val stack = ArrayDeque<Pair<String, String>>() // dirUri to relativePath
        stack.addLast(rootUri to rootPrefix)
        val depths = HashMap<String, Int>()
        depths[rootUri] = 0

        while (stack.isNotEmpty()) {
            val (dirUri, prefix) = stack.removeLast()
            val depth = depths[dirUri] ?: 0
            val children = try {
                browser.children(dirUri)
            } catch (e: SecurityException) {
                throw PermissionRevokedException("读取文件夹失败，授权可能已被撤销：$dirUri", e)
            }
            for (entry in children) {
                if (entry.isDirectory) {
                    if (depth + 1 > maxDepth) {
                        truncated = true
                        continue
                    }
                    val childPrefix = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                    stack.addLast(entry.uri to childPrefix)
                    depths[entry.uri] = depth + 1
                } else if (matchesExtension(entry.name)) {
                    if (out.size >= maxFiles) {
                        truncated = true
                        break
                    }
                    out.add(
                        ScannedAudioFile(
                            uri = entry.uri,
                            relativePath = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}",
                            fileName = entry.name,
                            sizeBytes = entry.sizeBytes,
                            lastModifiedMs = entry.lastModifiedMs,
                        ),
                    )
                }
            }
        }
        return WalkResult(files = out, truncated = truncated)
    }

    private fun matchesExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in extensions
    }

    data class WalkResult(val files: List<ScannedAudioFile>, val truncated: Boolean)
}
