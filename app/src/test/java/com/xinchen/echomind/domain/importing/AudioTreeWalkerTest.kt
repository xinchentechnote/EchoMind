package com.xinchen.echomind.domain.importing

import com.xinchen.echomind.core.conf.AudioFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 递归扫描的纯逻辑部分（FR-103 / FR-107）。
 * TreeBrowser 是假实现，所以深度、数量、权限这些边界都能在 JVM 里跑。
 */
class AudioTreeWalkerTest {

    private class FakeBrowser(private val tree: Map<String, List<TreeEntry>>) : TreeBrowser {
        val visited = mutableListOf<String>()
        override fun children(dirUri: String): List<TreeEntry> {
            visited.add(dirUri)
            return tree[dirUri] ?: emptyList()
        }

        override fun treeDisplayName(treeUri: String): String? = treeUri.substringAfterLast('/')
    }

    private fun dir(name: String, uri: String) = TreeEntry(name, uri, isDirectory = true, sizeBytes = 0, lastModifiedMs = 0)

    private fun file(name: String, uri: String, size: Long = 1000) =
        TreeEntry(name, uri, isDirectory = false, sizeBytes = size, lastModifiedMs = size * 7)

    private fun walker(tree: Map<String, List<TreeEntry>>, maxDepth: Int = 12, maxFiles: Int = 20_000): Pair<AudioTreeWalker, FakeBrowser> {
        val browser = FakeBrowser(tree)
        return AudioTreeWalker(browser, setOf("mp3"), maxDepth, maxFiles) to browser
    }

    @Test
    fun `平铺目录只收音频扩展名`() {
        val (walker, _) = walker(
            mapOf("root" to listOf(file("a.mp3", "root/a.mp3"), file("b.txt", "root/b.txt"), file("c.mp3", "root/c.mp3"))),
        )
        val result = walker.walk("root")
        assertEquals(listOf("a.mp3", "c.mp3"), result.files.map { it.relativePath })
        assertEquals(false, result.truncated)
    }

    @Test
    fun `扫描结果带上文件大小与修改时间`() {
        val (walker, _) = walker(mapOf("root" to listOf(file("a.mp3", "root/a.mp3", size = 4096))))
        val entry = walker.walk("root").files.single()
        assertEquals(4096L, entry.sizeBytes)
        assertEquals(4096L * 7, entry.lastModifiedMs)
        assertEquals("a.mp3", entry.fileName)
    }

    @Test
    fun `生产白名单的格式全部收进来且大小写不敏感`() {
        // 白名单扩展（放开 m4a/aac/flac/wav/wma）走的是同一条 walker 判定，这里用**真实清单**钉住：
        // 大写扩展名照收，白名单之外的 ogg（缺测试素材未实测）照丢
        val tree = mapOf(
            "root" to listOf(
                file("a.mp3", "root/a.mp3"), file("b.m4a", "root/b.m4a"), file("c.aac", "root/c.aac"),
                file("d.flac", "root/d.flac"), file("e.wav", "root/e.wav"), file("f.WAV", "root/f.WAV"),
                file("g.wma", "root/g.wma"), file("h.ogg", "root/h.ogg"), file("i.txt", "root/i.txt"),
            ),
        )
        val walker = AudioTreeWalker(FakeBrowser(tree), AudioFormats.SUPPORTED_EXTENSIONS)
        assertEquals(
            listOf("a.mp3", "b.m4a", "c.aac", "d.flac", "e.wav", "f.WAV", "g.wma"),
            walker.walk("root").files.map { it.fileName },
        )
    }

    @Test
    fun `扩展名大小写不敏感且无扩展名被忽略`() {
        val (walker, _) = walker(
            mapOf("root" to listOf(file("x.MP3", "root/x.MP3"), file("README", "root/README"), file("y.mp4", "root/y.mp4"))),
        )
        assertEquals(listOf("x.MP3"), walker.walk("root").files.map { it.fileName })
    }

    @Test
    fun `递归子目录并保留相对路径`() {
        val (walker, browser) = walker(
            mapOf(
                "root" to listOf(file("top.mp3", "root/top.mp3"), dir("sub", "root/sub")),
                "root/sub" to listOf(file("inner.mp3", "root/sub/inner.mp3"), dir("deep", "root/sub/deep")),
                "root/sub/deep" to listOf(file("deep.mp3", "root/sub/deep/deep.mp3")),
            ),
        )
        val files = walker.walk("root").files.associateBy { it.uri }
        assertEquals(setOf("root/top.mp3", "root/sub/inner.mp3", "root/sub/deep/deep.mp3"), files.keys)
        assertEquals("sub/inner.mp3", files["root/sub/inner.mp3"]?.relativePath)
        assertEquals("sub/deep/deep.mp3", files["root/sub/deep/deep.mp3"]?.relativePath)
        assertEquals(listOf("root", "root/sub", "root/sub/deep"), browser.visited)
    }

    @Test
    fun `超过深度上限时停止深入并标记截断`() {
        val (walker, browser) = walker(
            mapOf(
                "root" to listOf(dir("d1", "d1")),
                "d1" to listOf(dir("d2", "d2")),
                "d2" to listOf(file("c.mp3", "d2/c.mp3")),
            ),
            maxDepth = 1,
        )
        val result = walker.walk("root")
        assertTrue(result.truncated)
        assertEquals(0, result.files.size)
        assertEquals(listOf("root", "d1"), browser.visited)
    }

    @Test
    fun `数量上限生效时标记截断`() {
        val entries = (1..5).map { file("f$it.mp3", "root/f$it.mp3") }
        val (walker, _) = walker(mapOf("root" to entries), maxFiles = 2)
        val result = walker.walk("root")
        assertEquals(2, result.files.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `读取抛 SecurityException 时转成 PermissionRevokedException`() {
        val browser = object : TreeBrowser {
            override fun children(dirUri: String): List<TreeEntry> = throw SecurityException("permission revoked")
            override fun treeDisplayName(treeUri: String): String? = null
        }
        val walker = AudioTreeWalker(browser, setOf("mp3"))
        val error = runCatching { walker.walk("root") }.exceptionOrNull()
        assertTrue(error is PermissionRevokedException)
    }

    @Test
    fun `空目录返回空结果而不是报错`() {
        val (walker, _) = walker(mapOf("root" to emptyList()))
        val result = walker.walk("root")
        assertEquals(0, result.files.size)
        assertEquals(false, result.truncated)
    }
}
