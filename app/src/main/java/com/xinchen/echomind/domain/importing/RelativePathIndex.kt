package com.xinchen.echomind.domain.importing

/**
 * 按「夹内相对路径」把一批条目组织成可逐层浏览的树（书架目录浏览用，2026-10-28）。
 *
 * 与 [DeviceAudioIndex] 的差别：这里没有卷的概念，路径是**相对某个已导入文件夹根**的
 * （SAF 书本来就相对树根；本机书由仓库层剥掉伪文件夹自己的设备路径前缀），条目类型泛型化——
 * data 层的 ShelfRow 不许进 domain，调用方自己带着 payload 走。
 */
class RelativePathIndex<T>(private val entries: List<Pair<String, T>>) {

    data class FolderNode(
        /** 相对夹根的完整子路径，下钻时原样传回 [childrenOf] */
        val path: String,
        /** 展示名（最后一段） */
        val displayName: String,
        /** 该子树（含下层）的条目数 */
        val fileCount: Int,
    )

    data class Listing<T>(
        val folders: List<FolderNode>,
        /** 直接位于这一层的条目，保持调用方给的顺序 */
        val items: List<T>,
    )

    /**
     * 当前层的直接子文件夹（递归计数）与条目。path = "" 是夹根。
     *
     * 条目路径**含文件名**（`a/一/x.mp3`），所以「直属」= 去掉层前缀后不含 `/`，
     * 「子文件夹」= 去掉层前缀后还有 `/`；前缀用 startsWith 判定再 removePrefix——
     * removePrefix 对不匹配的前缀会静默原样返回，`a` 层会凭空长出 `ab` 文件夹。
     */
    fun childrenOf(path: String): Listing<T> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val inLevel = entries.asSequence()
            .filter { it.first.startsWith(prefix) }
            .map { it.first.removePrefix(prefix) to it.second }
            .toList()
        val folders = inLevel.asSequence()
            .filter { (rel, _) -> rel.contains('/') }
            .groupingBy { (rel, _) ->
                val segment = rel.substringBefore('/')
                // 子节点的 path 存**全子路径**：下钻与计数都以它为键（存相对段会查空——
                // 本机音频页踩过同一类坑，见 DeviceAudioIndex.childrenOf 的注释）
                NodePath(path = if (path.isEmpty()) segment else "$path/$segment", displayName = segment)
            }
            .eachCount()
            .map { (node, count) -> FolderNode(node.path, node.displayName, count) }
            .sortedBy { it.displayName.lowercase() }
        val direct = inLevel.filter { (rel, _) -> !rel.contains('/') }.map { it.second }
        return Listing(folders, direct)
    }

    private data class NodePath(val path: String, val displayName: String)
}
