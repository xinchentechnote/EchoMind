package com.xinchen.echomind.domain.importing

import org.junit.Assert.assertEquals
import org.junit.Test

/** 本机音频索引：分组、逐层下钻、子树导入过滤与路径推导（纯 JVM，无 Android 依赖） */
class DeviceAudioIndexTest {

    private fun entry(volume: String = "external_primary", path: String, name: String, uri: String = "content://media/$volume/$path/$name") =
        DeviceAudioEntry(volume, path, name, uri, sizeBytes = 10L, lastModifiedMs = 1L, durationMs = 2L)

    @Test
    fun 根层列出顶层文件夹与根文件并带递归计数() {
        val index = DeviceAudioIndex(
            listOf(
                entry(path = "Music", name = "a.mp3"),
                entry(path = "Music/Podcasts", name = "b.mp3"),
                entry(path = "Music/Podcasts/2026", name = "c.mp3"),
                entry(path = "Audiobooks", name = "d.mp3"),
                entry(path = "", name = "loose.mp3"),
            ),
        )
        val root = index.childrenOf(DeviceAudioFolderRef("external_primary", ""))

        assertEquals(
            listOf("Audiobooks" to 1, "Music" to 3),
            root.folders.map { it.ref.path.substringAfterLast('/') to it.fileCount },
        )
        assertEquals(listOf("loose.mp3"), root.files.map { it.fileName })
    }

    @Test
    fun 下钻只见该卷该层的直接内容() {
        val index = DeviceAudioIndex(
            listOf(
                entry(path = "Music", name = "a.mp3"),
                entry(path = "Music/Podcasts", name = "b.mp3"),
                entry(volume = "sdcard1", path = "Music", name = "on-sd.mp3"),
            ),
        )
        val music = index.childrenOf(DeviceAudioFolderRef("external_primary", "Music"))

        // 子文件夹 ref 必须是全路径：下钻与整夹导入都拿它当键，存相对段就查空（2026-10-27 实测抓到）
        assertEquals(listOf("Music/Podcasts"), music.folders.map { it.ref.path })
        assertEquals(listOf("Podcasts"), music.folders.map { it.ref.displayName })
        assertEquals(listOf("a.mp3"), music.files.map { it.fileName })

        // 同名文件夹在另一卷上是独立身份，不能串进来
        val sdMusic = index.childrenOf(DeviceAudioFolderRef("sdcard1", "Music"))
        assertEquals(0, sdMusic.folders.size)
        assertEquals(listOf("on-sd.mp3"), sdMusic.files.map { it.fileName })
    }

    @Test
    fun 深层子文件夹的ref是完整路径且计数正确() {
        val index = DeviceAudioIndex(
            listOf(
                entry(path = "Music/有声书/上册", name = "a.mp3"),
                entry(path = "Music/有声书/上册", name = "b.mp3"),
                entry(path = "Music/有声书/下册", name = "c.mp3"),
            ),
        )
        val shang = index.childrenOf(DeviceAudioFolderRef("external_primary", "Music/有声书"))
        assertEquals(
            listOf("Music/有声书/上册", "Music/有声书/下册"),
            shang.folders.map { it.ref.path },
        )
        assertEquals(listOf(2, 1), shang.folders.map { it.fileCount })
    }

    @Test
    fun 文件夹排序不分大小写() {
        val index = DeviceAudioIndex(
            listOf(entry(path = "apple", name = "1.mp3"), entry(path = "Beta", name = "2.mp3")),
        )
        val root = index.childrenOf(DeviceAudioFolderRef("external_primary", ""))
        assertEquals(listOf("apple", "Beta"), root.folders.map { it.ref.displayName })
    }

    @Test
    fun 子树导入包含该层与全部子层级() {
        val index = DeviceAudioIndex(
            listOf(
                entry(path = "Music", name = "a.mp3"),
                entry(path = "Music/Podcasts", name = "b.mp3"),
                entry(path = "MusicPodcasts", name = "similar.mp3"),
                entry(volume = "sdcard1", path = "Music", name = "on-sd.mp3"),
            ),
        )
        val under = index.entriesUnder(DeviceAudioFolderRef("external_primary", "Music"))
        assertEquals(listOf("Music/a.mp3", "Music/Podcasts/b.mp3"), under.map { it.bookRelativePath })
    }

    @Test
    fun 前缀相似目录不误入子树() {
        val index = DeviceAudioIndex(listOf(entry(path = "Music1", name = "x.mp3")))
        assertEquals(0, index.entriesUnder(DeviceAudioFolderRef("external_primary", "Music")).size)
    }

    @Test
    fun 相对路径规范化去首尾斜杠() {
        assertEquals("Music/Podcasts", DeviceAudioIndex.normalizeRelativePath("Music/Podcasts/"))
        assertEquals("Music", DeviceAudioIndex.normalizeRelativePath("/Music"))
        assertEquals("", DeviceAudioIndex.normalizeRelativePath("/"))
        assertEquals("", DeviceAudioIndex.normalizeRelativePath(null))
        assertEquals("", DeviceAudioIndex.normalizeRelativePath(""))
    }

    @Test
    fun 旧系统从DATA推导相对目录() {
        val roots = DeviceAudioIndex.DEFAULT_ROOTS
        assertEquals("Music/Podcasts", DeviceAudioIndex.relativePathFromData("/storage/emulated/0/Music/Podcasts/x.mp3", roots))
        assertEquals("", DeviceAudioIndex.relativePathFromData("/storage/emulated/0/x.mp3", roots))
        assertEquals("Music", DeviceAudioIndex.relativePathFromData("/sdcard/Music/x.mp3", roots))
        // 未知挂载点推不出目录：归卷根，不伪造
        assertEquals("", DeviceAudioIndex.relativePathFromData("/mnt/other/Music/x.mp3", roots))
        assertEquals("", DeviceAudioIndex.relativePathFromData("", roots))
    }

    @Test
    fun 卷与文件夹的展示名() {
        assertEquals("内部存储", DeviceAudioFolderRef.volumeDisplayName("external_primary"))
        assertEquals("sdcard1", DeviceAudioFolderRef.volumeDisplayName("sdcard1"))
        assertEquals("Music", DeviceAudioFolderRef("external_primary", "Music").displayName)
        assertEquals("Podcasts", DeviceAudioFolderRef("external_primary", "Music/Podcasts").displayName)
        assertEquals("内部存储", DeviceAudioFolderRef("external_primary", "").displayName)
    }

    @Test
    fun 文件夹的上级与根的上级() {
        assertEquals("Music", DeviceAudioFolderRef("v", "Music/Podcasts").parent!!.path)
        assertEquals("", DeviceAudioFolderRef("v", "Music").parent!!.path)
        assertEquals(null, DeviceAudioFolderRef("v", "").parent)
        assertEquals("v", DeviceAudioFolderRef("v", "Music").parent!!.volume)
    }

    @Test
    fun 书的相对路径与SAF导入同口径() {
        assertEquals("Music/a.mp3", entry(path = "Music", name = "a.mp3").bookRelativePath)
        assertEquals("a.mp3", entry(path = "", name = "a.mp3").bookRelativePath)
    }

    @Test
    fun 扩展名白名单判定与SAF遍历同一规则() {
        // 白名单内的格式大小写不敏感
        for (name in listOf("a.mp3", "b.M4A", "c.Flac", "d.WAV", "e.Wma", "f.aac")) {
            assert(DeviceAudioIndex.matchesExtension(name)) { name }
        }
        // 白名单外与无扩展名一律不放行
        for (name in listOf("a.ogg", "b.opus", "c.txt", "d", "e.", "f.mp3.bak")) {
            assert(!DeviceAudioIndex.matchesExtension(name)) { name }
        }
    }

    @Test
    fun LIKE转义挡住通配符目录名() {
        assertEquals("My\\\\Music", DeviceAudioIndex.escapeLike("My\\Music"))
        assertEquals("My\\_Music", DeviceAudioIndex.escapeLike("My_Music"))
        assertEquals("100\\%\\%纯", DeviceAudioIndex.escapeLike("100%%纯"))
        assertEquals("普通目录", DeviceAudioIndex.escapeLike("普通目录"))
    }
}
