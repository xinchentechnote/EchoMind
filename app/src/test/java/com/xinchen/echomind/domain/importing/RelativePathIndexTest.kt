package com.xinchen.echomind.domain.importing

import org.junit.Assert.assertEquals
import org.junit.Test

/** 书架目录浏览的分层索引：子树计数、逐层下钻、展示名（纯 JVM） */
class RelativePathIndexTest {

    @Test
    fun 夹根列出子文件夹与直属条目且计数含子层级() {
        val index = RelativePathIndex(
            listOf(
                "a/一/x.mp3" to "x",
                "a/一/y.mp3" to "y",
                "a/二/z.mp3" to "z",
                "top.mp3" to "t",
            ),
        )
        val root = index.childrenOf("")
        assertEquals(listOf("a"), root.folders.map { it.displayName })
        assertEquals("a", root.folders.single().path)
        assertEquals(3, root.folders.single().fileCount)
        assertEquals(listOf("t"), root.items)
    }

    @Test
    fun 逐层下钻只见该层内容且路径全拼() {
        val index = RelativePathIndex(
            listOf("a/一/x.mp3" to "x", "a/二/z.mp3" to "z", "a/direct.mp3" to "d"),
        )
        val a = index.childrenOf("a")
        assertEquals(listOf("a/一", "a/二"), a.folders.map { it.path })
        assertEquals(listOf("一", "二"), a.folders.map { it.displayName })
        assertEquals(listOf("d"), a.items)

        val yi = index.childrenOf("a/一")
        assertEquals(0, yi.folders.size)
        assertEquals(listOf("x"), yi.items)
    }

    @Test
    fun 相似前缀目录不串层() {
        val index = RelativePathIndex(listOf("ab/x.mp3" to "x"))
        val listing = index.childrenOf("a")
        assertEquals(0, listing.folders.size)
        assertEquals(0, listing.items.size)
    }

    @Test
    fun 文件夹按展示名不分大小写排序() {
        val index = RelativePathIndex(listOf("Beta/1.mp3" to 1, "apple/2.mp3" to 2))
        assertEquals(listOf("apple", "Beta"), index.childrenOf("").folders.map { it.displayName })
    }
}
